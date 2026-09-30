package com.example.dink_smb_player.data.library

import android.content.Context
import com.example.dink_smb_player.data.index.IndexDao
import com.example.dink_smb_player.data.index.LibraryGrouping
import com.example.dink_smb_player.data.index.MediaIndex
import com.example.dink_smb_player.data.index.PlayStat
import com.example.dink_smb_player.data.index.SourceEntity
import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import com.example.dink_smb_player.data.index.TrackMerges
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.DinkApplication
import com.example.dink_smb_player.data.source.ReadResult
import com.example.dink_smb_player.data.source.SmbProbe
import com.example.dink_smb_player.data.source.TagReadGate
import com.example.dink_smb_player.data.source.TagReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Single source of truth for *imported* playable tracks across every source (local,
 * SMB, cloud). Wraps the previously-orphaned [MediaIndex] / [IndexDao]: source
 * screens (Local / SMB / Cloud) write here via [importSource]; library + Home
 * screens read [songs] / [recentlyAdded] and build bounded playback queues from
 * album / artist / folder views.
 *
 * The UI layer stays on the existing [Song] model; [TrackEntity.toSong] /
 * [Song.toTrackEntity] bridge the index's richer row shape.
 */
object LibraryRepository {

    private fun dao(context: Context): IndexDao = daoOverride ?: MediaIndex.get(context.applicationContext).dao

    /** Disk side of the index, swappable for tests. */
    internal interface IndexIo {
        suspend fun load(context: Context): LibraryStore.LoadResult
        suspend fun save(context: Context, snapshot: () -> Pair<List<TrackEntity>, List<SourceEntity>>): Result<Unit>
    }

    private object StoreIo : IndexIo {
        override suspend fun load(context: Context) = LibraryStore.load(context)
        override suspend fun save(context: Context, snapshot: () -> Pair<List<TrackEntity>, List<SourceEntity>>) =
            LibraryStore.save(context, snapshot)
    }

    @Volatile internal var io: IndexIo = StoreIo
    @Volatile internal var daoOverride: IndexDao? = null

    @Volatile private var restored = false
    private val restoreMutex = Mutex()

    // Observable mirror of [restored] for the UI. Boot restore of a 25k-row index takes
    // a few seconds; without this, library screens see an empty index and render the
    // "nothing imported — add a source" empty state over a library that's about to load.
    // Screens show a loading bar while this is false and the list is still empty.
    private val _restored = MutableStateFlow(false)
    val restoredState: StateFlow<Boolean> = _restored.asStateFlow()

    // Gates [persist]. Starts NotRestored: [persist] runs the restore first, so an early writer
    // (LocalStorageScreen's refresh, a worker, the media-button process) can't snapshot a
    // half-loaded index over the file. Disabled if a restore finds the on-disk index
    // present-but-unreadable (main AND backup) and it could NOT be quarantined aside:
    // persisting then would snapshot an index that is missing that file's tracks and
    // overwrite the (recoverable) file with an empty one — exactly the bug that wiped
    // imported SMB tracks on restart. (Once the bad file is moved aside there is nothing
    // left to clobber, so the gate stays open.) That lasts for the process; the next launch
    // retries the load. A transient load failure (I/O, OOM) also disables it, but a retry is
    // scheduled and [persist] retries the restore first; a successful retry re-enables it.
    private val persistGate = PersistGate()

    /** Transient-restore retries scheduled from [restore]: delay = base × 2^attempt. After the
     *  last one the UI's loading gate is released; writers still retry via [ensureRestored]. */
    @Volatile internal var restoreRetryBaseMs = 2_000L
    private const val RESTORE_RETRIES = 5
    @Volatile private var restoreRetryAttempt = 0
    private val restoreRetryPending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Test hook: back to a fresh-process state. */
    internal fun resetForTest(io: IndexIo, dao: IndexDao?) {
        this.io = io
        daoOverride = dao
        restored = false
        _restored.value = false
        persistGate.resetForTest()
        restoreRetryAttempt = 0
        restoreRetryPending.set(false)
        lastBatchPersistMs = 0L
    }

    /** Reload the on-disk index into memory. Runs under [restoreMutex] (via [ensureRestored]).
     *  The restore merge only fills rows the index doesn't have yet, so rows a writer put in
     *  the index before a (retried) restore finished survive it. */
    private suspend fun restore(context: Context) {
        when (val result = io.load(context)) {
            is LibraryStore.LoadResult.Missing -> persistGate.onLoaded()
            is LibraryStore.LoadResult.Ok -> {
                val dao = dao(context)
                result.snapshot.sources.forEach { dao.upsertSource(it) }
                // Seeds the live play-stats map from the rows' stored counts (LIB-15).
                dao.restoreTracks(result.snapshot.tracks)
                persistGate.onLoaded()
                // One-time migration: snapshots written before precompute existed carry null
                // grouping keys, and ones from before LIB-6 carry title-only album keys.
                // Recompute once and persist so subsequent boots (and the Albums/Artists views)
                // read ready-made keys instead of normalizing at display. The raw write: we
                // hold restoreMutex, which isn't reentrant.
                if (migrateGroupingKeys(dao, result.snapshot.tracks)) persistNow(context)
            }
            is LibraryStore.LoadResult.Corrupt -> {
                // The unreadable file(s) were quarantined aside (timestamped copies) when the
                // move succeeded: nothing is left at the index path to clobber, so keep writes on
                // — otherwise everything imported this session is silently lost on restart. Only
                // a file still in place (quarantine failed) keeps the gate closed.
                persistGate.onCorrupt(quarantined = result.quarantined)
                android.util.Log.e(
                    "LibraryRepository",
                    if (result.quarantined) "index unreadable — quarantined aside, starting from an empty index"
                    else "index restore failed — persistence disabled this session, to avoid wiping the on-disk library",
                    result.error,
                )
            }
            is LibraryStore.LoadResult.Transient -> {
                // Says nothing about the file: don't write, and leave [restored] false so the
                // next ensureRestored()/persist() retries. Keep the UI's loading gate closed while
                // a retry is scheduled, so screens don't show "no sources — add one" over a
                // library that is still coming.
                persistGate.onTransientFailure()
                android.util.Log.e("LibraryRepository", "index restore failed transiently — will retry; persistence off meanwhile", result.error)
                scheduleRestoreRetry(context)
                return
            }
        }
        restored = true
        _restored.value = true
    }

    /** One pending retry at a time, backing off; gives up (releasing the UI) after [RESTORE_RETRIES]. */
    private fun scheduleRestoreRetry(context: Context) {
        if (restoreRetryAttempt >= RESTORE_RETRIES) {
            android.util.Log.e("LibraryRepository", "index restore still failing after $RESTORE_RETRIES retries — showing what's loaded")
            _restored.value = true
            return
        }
        if (!restoreRetryPending.compareAndSet(false, true)) return
        val delayMs = restoreRetryBaseMs shl restoreRetryAttempt++
        val appCtx = context.applicationContext
        backgroundScope(appCtx).launch {
            delay(delayMs)
            restoreRetryPending.set(false)
            ensureRestored(appCtx)
        }
    }

    /**
     * Restore the on-disk index into the in-memory singleton once per process,
     * unless it's already been done. Critical before any mutate-and-[persist]:
     * the index is an empty singleton at process start, and a background entry
     * point (e.g. [com.example.dink_smb_player.data.source.MonitorWorker]) can run
     * in a cold process where the UI's boot restore never executed. Persisting an
     * unrestored index would overwrite library_index.json with a near-empty
     * snapshot, wiping every imported SMB/cloud track that isn't re-derived from a
     * live source. Every write path below calls this first, so a writer that races
     * the boot restore waits for it. Idempotent and cheap — guards on a process-level flag.
     */
    suspend fun ensureRestored(context: Context) {
        if (restored) return
        // Serialize concurrent callers (UI boot restore vs a cold-process worker)
        // so two restores can't interleave upserts on a half-populated index.
        restoreMutex.withLock { if (!restored) restore(context) }
    }

    /** Snapshot the current index to disk. Called after every mutation. Restores first if that
     *  hasn't happened (or failed transiently). Failure (write error, or the gate is closed so
     *  the change won't survive a restart) is logged and returned. */
    private suspend fun persist(context: Context): Result<Unit> {
        if (persistGate.needsRestore) ensureRestored(context)
        return persistNow(context)
    }

    /** [persist] without the restore step — for [restore] itself, which holds restoreMutex. */
    private suspend fun persistNow(context: Context): Result<Unit> {
        if (!persistGate.canPersist) {
            android.util.Log.w("LibraryRepository", "persist skipped (${persistGate.state}) — not clobbering on-disk index")
            return Result.failure(IllegalStateException("library persistence disabled (${persistGate.state})"))
        }
        val dao = dao(context)
        // The snapshot is taken inside LibraryStore's write lock, so writes land in order (LIB-12).
        // persistSnapshot folds the live play stats back into the rows (LIB-15).
        return io.save(context) { dao.persistSnapshot() }
            .onFailure { android.util.Log.e("LibraryRepository", "persist failed", it) }
    }

    /** Recompute the precomputed grouping keys (artistKey/albumKey/artistLabel) across the WHOLE
     *  library and upsert only the rows that changed. Collaboration attribution depends on
     *  library-wide solo-artist stats, so this must see every row — hence a single pass at
     *  authoritative write boundaries (import/retag) rather than per-batch. Off-main (Default).
     *  Does NOT persist — callers persist after, so the recompute and their other writes land in
     *  one snapshot. */
    internal suspend fun recomputeGroupingKeys(dao: IndexDao) {
        val all = dao.snapshot().first
        if (all.isEmpty()) return
        val updated = withContext(Dispatchers.Default) { LibraryGrouping.computeGroupingKeys(all) }
        // computeGroupingKeys preserves order and returns unchanged rows by data equality, so
        // upsert only what actually differs (typically all rows on first migration, few after).
        val bases = HashMap<String, TrackEntity>()
        val changed = ArrayList<TrackEntity>()
        for (i in all.indices) {
            if (all[i] != updated[i]) { bases[all[i].id] = all[i]; changed += updated[i] }
        }
        // Keys-only patch against the CURRENT rows: a play or a retag landing during the
        // recompute isn't reverted, and a row pruned meanwhile isn't re-created.
        if (changed.isNotEmpty()) dao.upsertTracks(changed, TrackMerges.keys(bases))
    }

    /** Recompute the grouping keys if any restored row's are missing or from an older scheme
     *  ([LibraryGrouping.keysStale]: pre-precompute nulls, pre-LIB-6 title-only album keys).
     *  Goes through [recomputeGroupingKeys], i.e. the keys-only merge: play stats (IndexDao's
     *  map), addedAt and every other field stay as restored. Returns true if it ran — the
     *  caller then persists once so the next boot finds current keys and skips this. */
    internal suspend fun migrateGroupingKeys(dao: IndexDao, restored: List<TrackEntity>): Boolean {
        if (restored.none(LibraryGrouping::keysStale)) return false
        recomputeGroupingKeys(dao)
        return true
    }

    // Process-cached, app-scoped projection of the index into the UI [Song] model.
    // Built once and shared via stateIn: the (up to 25k-row) sort + TrackEntity->Song
    // map runs ONCE per library mutation and the result is cached, so re-entering a
    // section (Songs/Albums/Artists/Folders/Search) reuses the already-computed list
    // instead of re-sorting + re-allocating the whole library on every navigation —
    // that per-visit recompute was the section-load lag. flowOn(Default) keeps the
    // heavy upstream off Main; stateIn's value flips on Main but that's just a ref set.
    @Volatile private var songsState: StateFlow<List<Song>>? = null

    fun songs(context: Context): StateFlow<List<Song>> {
        songsState?.let { return it }
        return synchronized(this) {
            songsState ?: buildSongsState(context).also { songsState = it }
        }
    }

    private fun buildSongsState(context: Context): StateFlow<List<Song>> {
        val appCtx = context.applicationContext
        // Prefer the process-lifetime appScope so the hot flow lives as long as the
        // index does; fall back to a private scope if invoked outside the app (tests).
        val scope = (appCtx as? DinkApplication)?.appScope
            ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return dao(appCtx).observeAllTracks().map { rows -> rows.map(TrackEntity::toSong) }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, songsNow(appCtx))
    }

    /** Synchronous current contents, for use as a [collectAsState] initial value so
     *  index-backed screens don't flash empty for a frame on (re)composition.
     *  Unsorted — consumers sort/group themselves (off-main), so don't pay a sort
     *  of the whole library on the main thread here. */
    fun songsNow(context: Context): List<Song> =
        dao(context).snapshot().first.map(TrackEntity::toSong)

    /** Synchronous count of indexed tracks — O(1) read of the in-memory list size, no
     *  per-row [Song] mapping. Reflects [restore]/[upsertTracks] immediately, BEFORE the
     *  async [songs] flow (which sorts on Dispatchers.Default) catches up. Screens use it
     *  to tell "restored, has tracks, flow still propagating" (show loading) apart from
     *  "restored, genuinely empty" (show empty state). */
    fun trackCountNow(context: Context): Int = dao(context).snapshot().first.size

    fun recentlyAdded(context: Context, limit: Int = 30): Flow<List<Song>> =
        dao(context).observeRecentlyAdded(limit).map { rows -> rows.map(TrackEntity::toSong) }
            .flowOn(Dispatchers.Default)

    /** Live play stats by track id (count + last played). Separate from [songs] so a play
     *  doesn't re-emit the whole library (LIB-15): read this where plays are shown or sorted on. */
    fun playStats(context: Context): StateFlow<Map<String, PlayStat>> = dao(context).playStats

    fun recentlyPlayed(context: Context, limit: Int = 20): Flow<List<Song>> =
        dao(context).observeRecentlyPlayed(limit).map { rows -> rows.map(TrackEntity::toSong) }
            .flowOn(Dispatchers.Default)

    fun songsForSource(context: Context, type: SourceType, sourceId: String): Flow<List<Song>> =
        dao(context).observeTracksFor(type, sourceId).map { rows -> rows.map(TrackEntity::toSong) }
            .flowOn(Dispatchers.Default)

    /** Current indexed tracks for a source, keyed by track id — used by importers to
     *  REUSE already-indexed rows (keep their tags, skip re-reading) so a re-import or
     *  monitor pass only tag-reads genuinely new files. */
    suspend fun sourceTrackMap(context: Context, type: SourceType, sourceId: String): Map<String, TrackEntity> =
        dao(context).observeTracksFor(type, sourceId).first().associateBy { it.id }

    /**
     * Replace the indexed contents of one source with [tracks] (upsert present,
     * prune absent), and refresh its [SourceEntity] stats. This is the local MediaStore
     * write path — call from a background coroutine. Restores first: merging into an
     * index the boot restore hasn't filled yet would be persisted without the SMB rows.
     */
    suspend fun importSource(
        context: Context,
        source: SourceEntity,
        tracks: List<TrackEntity>,
    ): Result<Unit> {
        ensureRestored(context)
        if (!mergeSource(dao(context), source, tracks)) return Result.success(Unit)
        return persist(context)
    }

    /** [importSource] minus the disk write. Returns false when nothing changed (the normal
     *  launch/monitor rescan): then no stats, no 25k-row grouping recompute, no file rewrite,
     *  and no re-emit of the library to every screen. */
    internal suspend fun mergeSource(dao: IndexDao, source: SourceEntity, tracks: List<TrackEntity>): Boolean {
        // Rows are rebuilt from the source each time; the Local merge carries over what only the
        // index knows (first-seen time, play stats, retag stamp, grouping keys) from the CURRENT
        // row. Without this every local refresh reset play history and re-dated every local
        // track as "new".
        val changed = dao.upsertTracks(tracks, TrackMerges.Local)
        val pruned = dao.pruneSource(source.type, source.id, tracks.map { it.uri })
        if (changed == 0 && pruned == 0) return false
        dao.upsertSource(source)
        dao.updateSourceStats(
            id = source.id,
            ts = System.currentTimeMillis(),
            count = tracks.size,
            size = tracks.sumOf { it.sizeBytes },
            statusJson = null,
        )
        recomputeGroupingKeys(dao)
        return true
    }

    /**
     * Import / re-import a bounded subtree of a source. Upserts the [SourceEntity]
     * and [freshTracks], and prunes only rows whose [TrackEntity.path] falls under
     * [scopePrefixes] and vanished from the scan — every other imported folder of
     * the same source is left untouched. This is the folder-scoped import/re-import
     * path: it does NOT re-walk or prune the rest of the share. Returns the source's
     * new total track count once it is on disk, or the persist failure — the change is
     * then in memory only and lost on restart, so callers must report an error, not
     * "Imported N". NOT authoritative: it covers one folder, so it never re-enables
     * persistence after a failed restore (that would overwrite the rest of the library on
     * disk with this folder alone).
     */
    suspend fun importScoped(
        context: Context,
        source: SourceEntity,
        freshTracks: List<TrackEntity>,
        scopePrefixes: List<String>,
        // Prune rows under [scopePrefixes] that the scan didn't return. Only safe when the
        // enumeration was COMPLETE — a partial/failed walk returns a subset, and pruning
        // against it deletes real, still-present files. Pass the walk's `complete` flag.
        prune: Boolean = true,
    ): Result<Int> {
        ensureRestored(context)
        val dao = dao(context)
        // Source removed while this walk ran: drop everything rather than re-create it (LIB-5).
        if (!dao.acceptsWrites(source.id)) return Result.success(0)
        val total = importScopedIn(dao, source, freshTracks, scopePrefixes, prune)
        return persist(context).map { total }
    }

    /** [importScoped] minus the disk write (and the removed-source early return). */
    internal suspend fun importScopedIn(
        dao: IndexDao,
        source: SourceEntity,
        freshTracks: List<TrackEntity>,
        scopePrefixes: List<String>,
        prune: Boolean,
    ): Int {
        dao.upsertSource(source)
        dao.upsertTracks(freshTracks, TrackMerges.Walk)
        if (prune) {
            val existing = dao.observeTracksFor(source.type, source.id).first()
            val keepUris = buildSet {
                existing.forEach { t -> if (scopePrefixes.none { t.path.startsWith(it) }) add(t.uri) }
                freshTracks.forEach { add(it.uri) }
            }
            dao.pruneSource(source.type, source.id, keepUris.toList())
        }
        val total = dao.observeTracksFor(source.type, source.id).first()
        dao.updateSourceStats(
            id = source.id,
            ts = System.currentTimeMillis(),
            count = total.size,
            size = total.sumOf { it.sizeBytes },
            statusJson = null,
        )
        recomputeGroupingKeys(dao)
        return total.size
    }

    /**
     * Monitor refresh for a subset of a source's folders. Re-scans only the monitored
     * folders ([freshTracks] is the enumeration of those folders, [monitoredPrefixes]
     * are their [TrackEntity.path] prefixes) and reconciles *within* them — upserting
     * present, pruning rows under a monitored prefix that disappeared — while leaving
     * imported-but-unmonitored folders untouched.
     */
    suspend fun refreshMonitored(
        context: Context,
        source: SourceEntity,
        freshTracks: List<TrackEntity>,
        monitoredPrefixes: List<String>,
        // Prune monitored-folder rows the scan didn't return (i.e. deleted on the source).
        // Only safe when the enumeration was COMPLETE. A flaky walk (NAS asleep right after
        // a TV boot, transient network drop) returns a subset; pruning against it deletes
        // real tracks and wipes the library. When false this is upsert-only — never deletes.
        prune: Boolean = true,
    ): Result<Unit> {
        ensureRestored(context)
        if (!reconcileMonitored(dao(context), source, freshTracks, monitoredPrefixes, prune)) {
            return Result.success(Unit)
        }
        return persist(context)
    }

    /** [refreshMonitored] minus the disk write. Upserts via the walk merge (only new or changed
     *  rows are rewritten) and returns false when the pass changed nothing — the normal case for
     *  a periodic monitor — so the caller skips the stats, the whole-library grouping recompute
     *  and the index-file rewrite. */
    internal suspend fun reconcileMonitored(
        dao: IndexDao,
        source: SourceEntity,
        freshTracks: List<TrackEntity>,
        monitoredPrefixes: List<String>,
        prune: Boolean,
    ): Boolean {
        if (monitoredPrefixes.isEmpty()) return false
        if (!dao.acceptsWrites(source.id)) return false
        val changed = dao.upsertTracks(freshTracks, TrackMerges.Walk)
        var pruned = 0
        if (prune) {
            val existing = dao.observeTracksFor(source.type, source.id).first()
            // Keep: every row that is NOT inside a monitored folder, plus the fresh scan
            // of the monitored folders. Anything under a monitored prefix and absent from
            // the fresh scan was deleted on the source and gets pruned.
            val keepUris = buildSet {
                existing.forEach { t -> if (monitoredPrefixes.none { t.path.startsWith(it) }) add(t.uri) }
                freshTracks.forEach { add(it.uri) }
            }
            pruned = dao.pruneSource(source.type, source.id, keepUris.toList())
        }
        if (changed == 0 && pruned == 0) return false
        val total = dao.observeTracksFor(source.type, source.id).first()
        dao.updateSourceStats(
            id = source.id,
            ts = System.currentTimeMillis(),
            count = total.size,
            size = total.sumOf { it.sizeBytes },
            statusJson = null,
        )
        recomputeGroupingKeys(dao)
        return true
    }

    /**
     * Persist a partial batch of freshly-enumerated tracks MID-import, so a restart or
     * crash during a long initial walk (25k SMB files = minutes) doesn't lose everything.
     * Upsert-only — no prune, no stats — the final [importScoped] reconciles the full set.
     * Because track ids are deterministic ([trackIdFor]), a resumed import reuses these
     * rows and skips re-reading their tags. NOT authoritative — never re-enables
     * persistence after a failed restore (see [importScoped]).
     */
    @Volatile private var lastBatchPersistMs = 0L
    private val BATCH_PERSIST_INTERVAL_MS = 3_000L

    suspend fun upsertBatch(context: Context, tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        ensureRestored(context)
        val dao = dao(context)
        // The index is in-memory, so this upsert is cheap and immediately re-emits to
        // the live `songs()` flow — that's what populates the Library view progressively.
        // Nothing new (a resumed walk re-flushing rows already indexed): no snapshot.
        if (dao.upsertTracks(tracks, TrackMerges.Walk) == 0) return
        // The disk snapshot (full JSON serialize) is the expensive part, so throttle it
        // mid-walk instead of snapshotting on every batch. The caller's final importScoped
        // persists unconditionally, so the completed import is always fully on disk; the
        // only exposure is a crash losing ≤ this interval of un-snapshotted rows, which the
        // next run re-reads cheaply (id reuse). Was: persist on every 500-track batch.
        val now = System.currentTimeMillis()
        if (now - lastBatchPersistMs >= BATCH_PERSIST_INTERVAL_MS) {
            lastBatchPersistMs = now
            persist(context)
        }
    }

    // Callers run this inside SourceLocks.remove, which tombstones [sourceId] first — from then
    // on the DAO drops any late write for it (LIB-5), so the delete below is final.
    suspend fun removeSource(context: Context, type: SourceType, sourceId: String) {
        ensureRestored(context)
        val dao = dao(context)
        dao.deleteSourceTracks(type, sourceId)
        dao.deleteSource(sourceId)
        persist(context)
    }

    /** Record a play (LIB-15: patches the play-stats map, not the track list) and schedule a
     *  debounced persist (LIB-3) — plays were never written to disk before. */
    suspend fun markPlayed(context: Context, trackId: String) {
        if (dao(context).markPlayed(trackId, System.currentTimeMillis())) playPersister(context).request()
    }

    /** Fire-and-forget [markPlayed] off-main — the player's play-credit sink, wired once per
     *  process (DinkApplication) so plays count with no UI (a media-key cold start).
     *  Restores the index first: in a cold process the played row may not be loaded yet. */
    fun recordPlay(context: Context, trackId: String) {
        val appCtx = context.applicationContext
        backgroundScope(appCtx).launch {
            ensureRestored(appCtx)
            markPlayed(appCtx, trackId)
        }
    }

    /** Process-lifetime scope on Dispatchers.Default (the app's, or a private one in tests). */
    private fun backgroundScope(appCtx: Context): CoroutineScope {
        val scope = (appCtx as? DinkApplication)?.appScope
            ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return CoroutineScope(scope.coroutineContext + Dispatchers.Default)
    }

    /** Write any play not yet on disk now. Called on process ON_STOP (DinkApplication). */
    suspend fun flushPendingWrites() {
        playPersister?.flush()
    }

    private const val PLAY_PERSIST_DEBOUNCE_MS = 2_000L
    @Volatile private var playPersister: DebouncedWriter? = null

    /** One app-lifetime writer: plays landing within [PLAY_PERSIST_DEBOUNCE_MS] share one
     *  snapshot. Goes through [persist], so the PersistGate still applies; a failed or gated
     *  write stays pending for the next play / flush. */
    private fun playPersister(context: Context): DebouncedWriter {
        playPersister?.let { return it }
        return synchronized(this) {
            playPersister ?: run {
                val appCtx = context.applicationContext
                DebouncedWriter(backgroundScope(appCtx), PLAY_PERSIST_DEBOUNCE_MS) {
                    persist(appCtx).isSuccess
                }.also { playPersister = it }
            }
        }
    }

    /** Progress of an in-flight [retagAll], for the Settings UI. null = idle.
     *  [ratePerSec] is rows processed / elapsed wall time — surfaced so the UI can show
     *  throughput and an ETA, and so a slow run reads as "2.7/s" rather than a frozen count. */
    data class RetagProgress(
        val done: Int,
        val total: Int,
        val changed: Int,
        val running: Boolean,
        val ratePerSec: Double = 0.0,
        /** Why the last write of the retagged rows failed, or null. Set = the updates are in
         *  memory only and will be lost on restart; the UI must not report them as saved. */
        val saveError: String? = null,
    ) {
        /** Seconds of work left at the current rate, or null until a rate is known. */
        val etaSeconds: Long?
            get() = if (ratePerSec > 0.0 && running) ((total - done) / ratePerSec).toLong() else null
    }

    private val _retagProgress = MutableStateFlow<RetagProgress?>(null)
    val retagProgress: StateFlow<RetagProgress?> = _retagProgress.asStateFlow()

    /** Max concurrent tag reads for TAIL-LOADED containers (M4A/MP4/AAC/MOV). Kept LOW:
     *  these pull the moov atom from end-of-file, so each read buffers multi-MB chunks and
     *  high concurrency multiplies peak heap and OOMs on a 25k library. Memory-bound —
     *  6 in flight is the safe cap. */
    private const val RETAG_CONCURRENCY_HEAVY = 6

    /** Max concurrent tag reads for FRONT-LOADED containers (MP3/FLAC/OGG/OPUS/WAV). Their
     *  header sits at the start of the file, so a read pulls only a small header and peak
     *  heap stays tiny regardless of concurrency. These are LATENCY-bound (waiting on SMB
     *  round-trips), so more in flight directly lifts throughput on the common mostly-MP3
     *  library. Sized well above the heavy cap; the two gates are independent. */
    private const val RETAG_CONCURRENCY_LIGHT = 16

    /** Container extensions whose metadata/duration live near END-OF-FILE (the moov atom),
     *  so a read buffers large tail chunks and must stay on the low-concurrency gate. */
    private val TAIL_LOADED_EXTS = setOf("m4a", "m4b", "mp4", "m4p", "aac", "mov")

    /** True when [path]'s container buffers from end-of-file (see [TAIL_LOADED_EXTS]); such
     *  rows go on the heavy (low-concurrency) gate, everything else on the light gate. */
    private fun isTailLoaded(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in TAIL_LOADED_EXTS

    /** Rows per persisted chunk — bounds the crash-loss window. Peak memory is capped
     *  by [RETAG_CONCURRENCY_HEAVY] (in-flight reads), not this, so it can be comfortably large
     *  to keep full-index snapshot writes infrequent over a 25k rescan. */
    private const val RETAG_CHUNK = 500

    /** Min gap between per-row progress emits. Keeps the count/rate visibly moving without
     *  flooding the StateFlow + recomposition from many concurrent reads. */
    private const val PROGRESS_EMIT_MS = 250L

    /** Implied-bitrate ceiling (kbps) above which a stored duration is treated as a bogus
     *  truncated-probe artifact and re-probed. Lossy formats top out ~320, lossless ~1411
     *  (CD) and a few thousand for hi-res; 700 sits clear of real lossy values while still
     *  catching the thousands-of-kbps artifacts. Small files with heavy ID3 art can read a
     *  little high — a harmless re-probe, idempotent if the duration was actually right. */
    private const val BOGUS_DURATION_KBPS = 700.0

    /**
     * Re-read embedded tags for every already-indexed REMOTE track (SMB/cloud) and
     * merge any improvements in place. Fixes libraries imported before tag reading
     * existed: those rows hold filename/folder-derived names, and a normal re-import
     * reuses them by id (incremental scan) so it never re-tags them. Local tracks are
     * skipped — they already carry clean MediaStore tags.
     *
     * Merge keeps non-name fields (playCount, lastPlayed, duration) and overwrites a
     * name field only when the file actually has that tag — so an untagged file keeps
     * its path-derived fallback, and play counts are never reset (unlike remove+reimport).
     * Reads run bounded-concurrent off the main thread; the index is persisted once at
     * the end, not per row. Call from a long-lived scope (appScope) — it can take minutes.
     */
    suspend fun retagAll(context: Context, force: Boolean = false): Int {
        if (_retagProgress.value?.running == true) return 0
        ensureRestored(context)
        val dao = dao(context)
        // Remote rows that still look filename-derived (title == file stem) OR are missing
        // a duration (durationMs <= 0 — imported before duration reading existed). A row
        // with a real name AND a duration is skipped, so this is cheap to re-run and resumes
        // after an interrupt instead of re-reading 25k. The same probe fills both.
        //
        // A row whose read was CONCLUSIVE (tags found, or the file genuinely has none) is stamped
        // with retagAttemptedMs, and a normal run skips rows that already carry one — so the
        // unfixable residue (correct titles that happen to equal the filename; genuinely untagged
        // files) is read ONCE and then never re-checked. A transient failure (NAS down, timeout)
        // is NOT stamped, so it's retried next run. A forced run ([force]) ignores the stamp and
        // re-reads the tags of EVERY remote row (as its Settings text says) — the way rows indexed
        // before a reader improvement (e.g. the album-artist tag) pick up the new field.
        val rows = dao.snapshot().first.filter {
            it.sourceType != SourceType.Local &&
                dao.acceptsWrites(it.sourceId) &&
                needsRetagAttempt(it, force) &&
                (force || looksFilenameDerived(it) || it.durationMs <= 0L || durationLooksBogus(it) || hasMojibake(it))
        }
        val total = rows.size
        if (total == 0) {
            _retagProgress.value = RetagProgress(0, 0, 0, false)
            return 0
        }
        _retagProgress.value = RetagProgress(0, total, 0, true)
        // Two independent gates: tail-loaded containers (M4A/MP4 — moov at EOF) buffer
        // multi-MB tails so stay capped low to bound heap; front-loaded ones (MP3/FLAC)
        // read a tiny header and are network-latency-bound, so run many more in flight.
        val heavyGate = Semaphore(RETAG_CONCURRENCY_HEAVY)
        val lightGate = Semaphore(RETAG_CONCURRENCY_LIGHT)
        val done = AtomicInteger(0)
        val changed = AtomicInteger(0)
        val failed = AtomicInteger(0)
        // Last persist failure, surfaced in the progress state (optimistic-persist rule).
        val saveError = java.util.concurrent.atomic.AtomicReference<String?>(null)
        suspend fun persistChecked() {
            persist(context).onFailure { t -> saveError.set(t.message ?: t::class.simpleName ?: "write failed") }
        }
        // Wall-clock start + last-emit guard: progress is published per ROW (not per 500-row
        // chunk) so the UI count moves continuously instead of jumping once a chunk and
        // looking frozen for minutes. Emits are time-throttled to avoid spamming the StateFlow
        // from up to RETAG_CONCURRENCY_LIGHT coroutines at once.
        val startMs = System.currentTimeMillis()
        val lastEmitMs = AtomicLong(0L)
        fun emitProgress(running: Boolean, force: Boolean = false) {
            val now = System.currentTimeMillis()
            val prev = lastEmitMs.get()
            if (!force && now - prev < PROGRESS_EMIT_MS) return
            if (!force && !lastEmitMs.compareAndSet(prev, now)) return // another row just emitted
            if (force) lastEmitMs.set(now)
            val d = done.get()
            val elapsedSec = (now - startMs) / 1000.0
            val rate = if (elapsedSec > 0.0) d / elapsedSec else 0.0
            _retagProgress.value = RetagProgress(d, total, changed.get(), running, rate, saveError.get())
        }
        // Process in chunks and persist after each: peak heap is bounded by the gates (only
        // RETAG_CONCURRENCY_HEAVY tail-loaded reads buffer big tails at once), and chunking
        // makes the rescan crash-resumable — a kill loses at most one chunk, and rows already
        // persisted with real titles/durations are skipped on rerun.
        withContext(Dispatchers.IO) {
            val bases = rows.associateBy { it.id }
            rows.chunked(RETAG_CHUNK).forEach { chunk ->
                val updates = Collections.synchronizedList(ArrayList<TrackEntity>())
                coroutineScope {
                    chunk.forEach { row ->
                        launch {
                            val gate = if (isTailLoaded(row.path)) heavyGate else lightGate
                            // Retag doesn't run under SourceLocks: a source deleted mid-run is
                            // skipped here (no SMB read), and the DAO drops any write for it.
                            if (dao.acceptsWrites(row.sourceId)) gate.withPermit {
                                // Read over SMB ONLY the field this row is actually missing.
                                // Title still filename-derived → re-read embedded tags; already
                                // a real title → skip the 1-3s tag retrieve. Duration missing →
                                // probe it; already present → skip a whole second SMB open.
                                val tagsNeeded = force || looksFilenameDerived(row)
                                val durationNeeded = row.durationMs <= 0L || durationLooksBogus(row)
                                // One shared SMB handle for this row's duration probe, its
                                // retriever fallback and the tail tag read (WP-I), as import does.
                                // Size is the last walk's listing; 0 (never recorded) → unknown,
                                // since the probe would otherwise trust it as the file length.
                                val knownSize = row.sizeBytes.takeIf { it > 0 } ?: -1L
                                // Inside the per-run caps, the process-wide TagReadGate keeps the
                                // retag plus any concurrent walks under Media3's retriever cap.
                                val result = TagReadGate.withPermit(row.path) {
                                    SmbProbe.session(row.uri, knownSize) {
                                        TagReader.readResult(
                                            context,
                                            row.uri,
                                            tagsNeeded = tagsNeeded,
                                            durationNeeded = durationNeeded,
                                        )
                                    }
                                }
                                if (result is ReadResult.Error) failed.incrementAndGet()
                                // Partial reads (Error.partial) still merge — only the stamp
                                // depends on whether the read was conclusive.
                                val tags = result.valueOrNull()
                                // Merge any tags we read over the existing row (unchanged when the
                                // read found nothing), then repair any field still carrying mojibake
                                // from a corrupt embedded tag by re-deriving it from the file path —
                                // the same folder/filename source used at import. Self-healing: once
                                // a row's fields are clean it no longer matches the retag filter.
                                var merged = if (tags != null) {
                                    row.copy(
                                        title = tags.title?.ifBlank { null } ?: row.title,
                                        artist = tags.artist?.ifBlank { null } ?: row.artist,
                                        albumTitle = tags.album?.ifBlank { null } ?: row.albumTitle,
                                        albumArtist = tags.albumArtist?.ifBlank { null } ?: row.albumArtist,
                                        year = tags.year ?: row.year,
                                        trackNumber = tags.trackNumber ?: row.trackNumber,
                                        durationMs = tags.durationMs?.takeIf { it > 0 } ?: row.durationMs,
                                    )
                                } else {
                                    row
                                }
                                if (looksMojibake(merged.title) || looksMojibake(merged.artist) ||
                                    looksMojibake(merged.albumTitle)
                                ) {
                                    val (pdTitle, pdArtist, pdAlbum) = pathDerivedNames(row.path)
                                    merged = merged.copy(
                                        title = if (looksMojibake(merged.title)) pdTitle else merged.title,
                                        artist = if (looksMojibake(merged.artist)) pdArtist else merged.artist,
                                        albumTitle = if (looksMojibake(merged.albumTitle)) pdAlbum else merged.albumTitle,
                                    )
                                }
                                // A real content change (name/duration/etc) counts toward the
                                // "updated N" the UI shows. A conclusive read stamps the row as
                                // attempted so a normal rerun skips it — this is what stops the
                                // residue from being re-read on every press; a transient failure
                                // leaves it unstamped for the next run.
                                val contentChanged = merged != row
                                if (contentChanged) changed.incrementAndGet()
                                val stamped = retagStamped(merged, result, System.currentTimeMillis())
                                if (stamped != row) updates.add(stamped)
                            }
                            done.incrementAndGet()
                            emitProgress(running = true)
                        }
                    }
                }
                if (updates.isNotEmpty()) {
                    // Patch only what the retag changed onto the CURRENT rows: plays and
                    // enrichment that landed while the chunk was reading survive, and a row
                    // pruned (or a source removed) meanwhile isn't re-created.
                    dao.upsertTracks(updates, TrackMerges.retag(bases))
                    persistChecked()
                }
                emitProgress(running = true, force = true)
            }
        }
        // Tags (artist/album) may have changed, so the grouping keys are now stale — recompute
        // across the whole library once and persist the corrected keys.
        recomputeGroupingKeys(dao)
        // A later successful write carries every earlier chunk too, so only the final
        // outcome decides whether the run is on disk.
        saveError.set(null)
        persistChecked()
        emitProgress(running = false, force = true)
        saveError.get()?.let { android.util.Log.e("LibraryRepository", "retag: results not saved — $it") }
        if (failed.get() > 0) {
            android.util.Log.i("LibraryRepository", "retag: ${failed.get()} of $total reads failed transiently; left unstamped for retry")
        }
        return changed.get()
    }

    /** Version of the retag stamping logic. Stamps written by an older version (retagVersion
     *  below this) count as unstamped, so the residue is re-read once under the new logic.
     *  v1: transient read failures are no longer stamped (v0 stamped every outcome). */
    internal const val RETAG_VERSION = 1

    /** Whether a normal (or [force]d) retag should read [row]: never conclusively read, or
     *  only stamped by an older [RETAG_VERSION]. */
    internal fun needsRetagAttempt(row: TrackEntity, force: Boolean): Boolean =
        force || row.retagAttemptedMs == null || row.retagVersion < RETAG_VERSION

    /** SRC-8: whether a walk should (re-)read the tags of already-indexed [stored] given the file's
     *  listing ([sizeBytes], last-write [mtimeMs]): the file changed in place, or the row still has
     *  no duration and no conclusive read is recorded for it (so it isn't re-read every pass). */
    internal fun needsRescanRead(stored: TrackEntity, sizeBytes: Long, mtimeMs: Long?): Boolean =
        TrackMerges.fileChanged(stored, sizeBytes, mtimeMs) ||
            (stored.durationMs <= 0L && needsRetagAttempt(stored, force = false))

    /** [merged] with its retag stamp decided by the read [result]: Found/Absent are conclusive
     *  → stamped now at [RETAG_VERSION]; Error (transient) → stamp left as it was, so the row
     *  is retried next run. */
    internal fun retagStamped(merged: TrackEntity, result: ReadResult<*>, nowMs: Long): TrackEntity =
        if (result is ReadResult.Error) merged
        else merged.copy(retagAttemptedMs = nowMs, retagVersion = RETAG_VERSION)

    /** True when the row's title still equals its filename stem — i.e. it was indexed
     *  from the path and never got real embedded tags. Cheap heuristic, no I/O. */
    private fun looksFilenameDerived(row: TrackEntity): Boolean {
        val stem = row.path.substringAfterLast('/').substringBeforeLast('.')
        return row.title.equals(stem, ignoreCase = true)
    }

    /** True when any name field carries mojibake (UTF-8 bytes that got decoded as Latin-1) —
     *  e.g. an old import that mis-read a tag. Retag re-selects such rows and repairs them from
     *  the path; once clean they no longer match, so this doesn't loop. */
    private fun hasMojibake(row: TrackEntity): Boolean =
        looksMojibake(row.title) || looksMojibake(row.artist) || looksMojibake(row.albumTitle)

    internal fun looksMojibake(s: String?): Boolean {
        if (s == null) return false
        for (i in s.indices) {
            val c = s[i].code
            if (c == 0xFFFD) return true // replacement char
            // U+00C2/C3/C5 (Â/Ã/Å) followed by a Latin-1 continuation byte = UTF-8-as-Latin-1.
            if ((c == 0xC2 || c == 0xC3 || c == 0xC5) && i + 1 < s.length && s[i + 1].code in 0x80..0xBF) return true
        }
        return false
    }

    /** Re-derive (title, artist, album) from a track's path the way import does: filename stem
     *  for the title, the grandparent folder for the artist, the parent folder for the album.
     *  Used to repair fields whose stored value is corrupt (mojibake). */
    private fun pathDerivedNames(path: String): Triple<String, String?, String?> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        val fileName = parts.lastOrNull() ?: path
        return Triple(
            fileName.substringBeforeLast('.'),
            parts.dropLast(2).lastOrNull(),
            parts.dropLast(1).lastOrNull(),
        )
    }

    /** True when a stored duration is physically impossible for the file size — the implied
     *  bitrate (sizeBytes*8 / durationMs, in kbps) exceeds [BOGUS_DURATION_KBPS]. Catches the
     *  truncated-probe artifact (old 5s deadline reported fake 5-16s durations whose implied
     *  bitrate ran to thousands of kbps; lossy audio tops out near 320, lossless near ~1400).
     *  Such a row is re-probed by retag even though it already has a (wrong) duration > 0. */
    internal fun durationLooksBogus(row: TrackEntity): Boolean {
        if (row.sizeBytes <= 0L || row.durationMs <= 0L) return false
        val impliedKbps = row.sizeBytes * 8.0 / row.durationMs
        return impliedKbps > BOGUS_DURATION_KBPS
    }

    /**
     * Enrich one track's tags from metadata the player extracted while STREAMING it
     * (Phase 8.7). SMB/cloud tracks are indexed with filename/folder-derived names;
     * once ExoPlayer parses the real ID3/Vorbis/MP4 tags we upgrade the row in place
     * (id is path-derived, so it's stable). No-ops when nothing actually changes, so
     * replaying an already-enriched track doesn't churn the on-disk snapshot.
     */
    suspend fun enrichTrack(
        context: Context,
        songId: String,
        title: String?,
        artist: String?,
        albumTitle: String?,
        year: Int?,
        trackNumber: Int?,
        durationMs: Long?,
    ): Result<Unit> {
        if (!enrichIn(dao(context), songId, title, artist, albumTitle, year, trackNumber, durationMs)) {
            return Result.success(Unit)
        }
        return persist(context)
    }

    /** [enrichTrack] minus the disk write; false = nothing changed. */
    internal suspend fun enrichIn(
        dao: IndexDao,
        songId: String,
        title: String?,
        artist: String?,
        albumTitle: String?,
        year: Int?,
        trackNumber: Int?,
        durationMs: Long?,
    ): Boolean {
        val existing = dao.trackById(songId) ?: return false
        val updated = existing.copy(
            title = title?.ifBlank { null } ?: existing.title,
            artist = artist?.ifBlank { null } ?: existing.artist,
            albumTitle = albumTitle?.ifBlank { null } ?: existing.albumTitle,
            year = year ?: existing.year,
            trackNumber = trackNumber ?: existing.trackNumber,
            durationMs = durationMs?.takeIf { it > 0 } ?: existing.durationMs,
        )
        if (updated == existing) return false
        dao.upsertTracks(listOf(updated), TrackMerges.enrich(existing))
        // LIB-13: the grouping keys were derived from the old artist/album — without this the
        // enriched track stays filed under its folder-derived artist/album until the next
        // import or retag. Keys use library-wide collaboration stats, hence the full pass
        // (memoised normalisation; only changed rows are written).
        if (updated.artist != existing.artist || updated.albumTitle != existing.albumTitle) {
            recomputeGroupingKeys(dao)
        }
        return true
    }
}

/** Deterministic primary key so re-imports of the same file update in place rather
 *  than duplicating. Matches [TrackEntity.id]'s documented `sha1(type+source+path)`. */
fun trackIdFor(type: SourceType, sourceId: String, path: String): String {
    val digest = SHA1.get()!!.digest("$type|$sourceId|$path".toByteArray(Charsets.UTF_8))
    // Runs once per file per monitor pass (25k+): a lookup table instead of a
    // String.format per byte. Output is byte-identical to the old "%02x" form.
    val out = CharArray(digest.size * 2)
    for (i in digest.indices) {
        val b = digest[i].toInt() and 0xff
        out[i * 2] = HEX_DIGITS[b ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[b and 0x0f]
    }
    return String(out)
}

// MessageDigest isn't thread-safe; one per thread (the walk runs parallel) avoids a
// provider lookup per call. digest() resets it for the next use.
private val SHA1: ThreadLocal<MessageDigest> = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-1") }
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

fun TrackEntity.toSong(): Song = Song(
    id = id,
    title = title,
    artist = artist ?: "Unknown",
    albumId = albumId,
    albumTitle = albumTitle,
    durationSec = (durationMs / 1000L).toInt(),
    playCount = playCount,
    sourcePath = path,
    bitrate = bitrate ?: "—",
    mediaUri = uri,
    artistKey = artistKey,
    albumKey = albumKey,
    artistLabel = artistLabel,
)
