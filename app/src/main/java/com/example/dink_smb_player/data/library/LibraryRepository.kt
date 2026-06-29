package com.example.dink_smb_player.data.library

import android.content.Context
import com.example.dink_smb_player.data.index.IndexDao
import com.example.dink_smb_player.data.index.MediaIndex
import com.example.dink_smb_player.data.index.SourceEntity
import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.DinkApplication
import com.example.dink_smb_player.data.source.TagReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
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

    private fun dao(context: Context): IndexDao = MediaIndex.get(context.applicationContext).dao

    @Volatile private var restored = false
    private val restoreMutex = Mutex()

    // Observable mirror of [restored] for the UI. Boot restore of a 25k-row index takes
    // a few seconds; without this, library screens see an empty index and render the
    // "nothing imported — add a source" empty state over a library that's about to load.
    // Screens show a loading bar while this is false and the list is still empty.
    private val _restored = MutableStateFlow(false)
    val restoredState: StateFlow<Boolean> = _restored.asStateFlow()

    // Gates [persist]. Stays true normally, but flips false if a restore finds the
    // on-disk index present-but-unreadable: persisting then would snapshot an index
    // that is missing that file's tracks and overwrite the (recoverable) file with
    // an empty one — exactly the bug that wiped imported SMB tracks on restart. An
    // authoritative reindex ([importSource] with reindexAuthoritative=true) re-enables it.
    @Volatile private var safeToPersist = true

    /** Reload the on-disk index into memory at boot. Upserts (never prunes) so a
     *  concurrent local MediaStore refresh can't be clobbered. */
    suspend fun restore(context: Context) {
        when (val result = LibraryStore.load(context)) {
            is LibraryStore.LoadResult.Missing -> safeToPersist = true
            is LibraryStore.LoadResult.Ok -> {
                val dao = dao(context)
                result.snapshot.sources.forEach { dao.upsertSource(it) }
                dao.upsertTracks(result.snapshot.tracks)
                safeToPersist = true
            }
            is LibraryStore.LoadResult.Corrupt -> {
                safeToPersist = false
                android.util.Log.e(
                    "LibraryRepository",
                    "index restore failed — persistence disabled until a reindex, to avoid wiping the on-disk library",
                    result.error,
                )
            }
        }
        restored = true
        _restored.value = true
    }

    /**
     * Restore the on-disk index into the in-memory singleton once per process,
     * unless it's already been done. Critical before any mutate-and-[persist]:
     * the index is an empty singleton at process start, and a background entry
     * point (e.g. [com.example.dink_smb_player.data.source.MonitorWorker]) can run
     * in a cold process where the UI's boot restore never executed. Persisting an
     * unrestored index would overwrite library_index.json with a near-empty
     * snapshot, wiping every imported SMB/cloud track that isn't re-derived from a
     * live source. Idempotent and cheap — guards on a process-level flag.
     */
    suspend fun ensureRestored(context: Context) {
        if (restored) return
        // Serialize concurrent callers (UI boot restore vs a cold-process worker)
        // so two restores can't interleave upserts on a half-populated index.
        restoreMutex.withLock { if (!restored) restore(context) }
    }

    /** Snapshot the current index to disk. Called after every mutation. */
    private suspend fun persist(context: Context) {
        if (!safeToPersist) {
            android.util.Log.w("LibraryRepository", "persist skipped — restore failed; not clobbering on-disk index")
            return
        }
        val (tracks, sources) = dao(context).snapshot()
        LibraryStore.save(context, tracks, sources)
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

    fun recentlyAdded(context: Context, limit: Int = 30): Flow<List<Song>> =
        dao(context).observeRecentlyAdded(limit).map { rows -> rows.map(TrackEntity::toSong) }
            .flowOn(Dispatchers.Default)

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
     * prune absent), and refresh its [SourceEntity] stats. This is the import +
     * monitor write path — call from a background coroutine.
     */
    suspend fun importSource(
        context: Context,
        source: SourceEntity,
        tracks: List<TrackEntity>,
        // A user-initiated full reindex of a source is authoritative: it re-enables
        // persistence after a failed restore disabled it, so a manual resync can
        // recover the on-disk index. Automatic/boot imports leave the gate as-is.
        reindexAuthoritative: Boolean = false,
    ) {
        if (reindexAuthoritative) safeToPersist = true
        val dao = dao(context)
        dao.upsertSource(source)
        dao.upsertTracks(tracks)
        dao.pruneSource(source.type, source.id, tracks.map { it.uri })
        dao.updateSourceStats(
            id = source.id,
            ts = System.currentTimeMillis(),
            count = tracks.size,
            size = tracks.sumOf { it.sizeBytes },
            statusJson = null,
        )
        persist(context)
    }

    /**
     * Import / re-import a bounded subtree of a source. Upserts the [SourceEntity]
     * and [freshTracks], and prunes only rows whose [TrackEntity.path] falls under
     * [scopePrefixes] and vanished from the scan — every other imported folder of
     * the same source is left untouched. This is the folder-scoped import/re-import
     * path: it does NOT re-walk or prune the rest of the share. Returns the source's
     * new total track count. Authoritative (re-enables persistence after a failed
     * restore), since it's a user-initiated import.
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
    ): Int {
        safeToPersist = true
        val dao = dao(context)
        dao.upsertSource(source)
        dao.upsertTracks(freshTracks)
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
        persist(context)
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
    ) {
        if (monitoredPrefixes.isEmpty()) return
        val dao = dao(context)
        dao.upsertTracks(freshTracks)
        if (prune) {
            val existing = dao.observeTracksFor(source.type, source.id).first()
            // Keep: every row that is NOT inside a monitored folder, plus the fresh scan
            // of the monitored folders. Anything under a monitored prefix and absent from
            // the fresh scan was deleted on the source and gets pruned.
            val keepUris = buildSet {
                existing.forEach { t -> if (monitoredPrefixes.none { t.path.startsWith(it) }) add(t.uri) }
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
        persist(context)
    }

    /**
     * Persist a partial batch of freshly-enumerated tracks MID-import, so a restart or
     * crash during a long initial walk (25k SMB files = minutes) doesn't lose everything.
     * Upsert-only — no prune, no stats — the final [importScoped] reconciles the full set.
     * Because track ids are deterministic ([trackIdFor]), a resumed import reuses these
     * rows and skips re-reading their tags. Authoritative (user-initiated import), so it
     * re-enables persistence after a failed restore.
     */
    @Volatile private var lastBatchPersistMs = 0L
    private val BATCH_PERSIST_INTERVAL_MS = 3_000L

    suspend fun upsertBatch(context: Context, tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        safeToPersist = true
        val dao = dao(context)
        // The index is in-memory, so this upsert is cheap and immediately re-emits to
        // the live `songs()` flow — that's what populates the Library view progressively.
        dao.upsertTracks(tracks)
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

    suspend fun removeSource(context: Context, type: SourceType, sourceId: String) {
        val dao = dao(context)
        dao.deleteSourceTracks(type, sourceId)
        dao.deleteSource(sourceId)
        persist(context)
    }

    suspend fun markPlayed(context: Context, trackId: String) {
        dao(context).markPlayed(trackId, System.currentTimeMillis())
    }

    /** Progress of an in-flight [retagAll], for the Settings UI. null = idle. */
    data class RetagProgress(val done: Int, val total: Int, val changed: Int, val running: Boolean)

    private val _retagProgress = MutableStateFlow<RetagProgress?>(null)
    val retagProgress: StateFlow<RetagProgress?> = _retagProgress.asStateFlow()

    /** Max concurrent tag reads during a full rescan. Kept LOW: each read spins a full
     *  Media3 extractor that buffers multi-MB chunks (M4A/MP4 pull the moov atom from
     *  end-of-file), so high concurrency multiplies peak heap and OOMs on a 25k library.
     *  Throughput here is memory-bound, not latency-bound — 6 in flight is the safe cap. */
    private const val RETAG_CONCURRENCY = 6

    /** Rows per persisted chunk — bounds the crash-loss window. Peak memory is capped
     *  by [RETAG_CONCURRENCY] (in-flight reads), not this, so it can be comfortably large
     *  to keep full-index snapshot writes infrequent over a 25k rescan. */
    private const val RETAG_CHUNK = 500

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
    suspend fun retagAll(context: Context): Int {
        if (_retagProgress.value?.running == true) return 0
        val dao = dao(context)
        // Remote rows that still look filename-derived (title == file stem) OR are missing
        // a duration (durationMs <= 0 — imported before duration reading existed). A row
        // with a real name AND a duration is skipped, so this is cheap to re-run and resumes
        // after an interrupt instead of re-reading 25k. The same probe fills both.
        val rows = dao.snapshot().first.filter {
            it.sourceType != SourceType.Local && (looksFilenameDerived(it) || it.durationMs <= 0L)
        }
        val total = rows.size
        if (total == 0) {
            _retagProgress.value = RetagProgress(0, 0, 0, false)
            return 0
        }
        _retagProgress.value = RetagProgress(0, total, 0, true)
        val gate = Semaphore(RETAG_CONCURRENCY)
        val done = AtomicInteger(0)
        val changed = AtomicInteger(0)
        // Process in chunks and persist after each: bounds peak heap (only one chunk's
        // reads in flight) AND makes the rescan crash-resumable — a kill loses at most
        // one chunk, and rows already persisted with real titles are skipped on rerun.
        withContext(Dispatchers.IO) {
            rows.chunked(RETAG_CHUNK).forEach { chunk ->
                val updates = Collections.synchronizedList(ArrayList<TrackEntity>())
                coroutineScope {
                    chunk.forEach { row ->
                        launch {
                            gate.withPermit {
                                val tags = TagReader.read(context, row.uri)
                                if (tags != null) {
                                    val merged = row.copy(
                                        title = tags.title?.ifBlank { null } ?: row.title,
                                        artist = tags.artist?.ifBlank { null } ?: row.artist,
                                        albumTitle = tags.album?.ifBlank { null } ?: row.albumTitle,
                                        year = tags.year ?: row.year,
                                        trackNumber = tags.trackNumber ?: row.trackNumber,
                                        durationMs = tags.durationMs?.takeIf { it > 0 } ?: row.durationMs,
                                    )
                                    if (merged != row) {
                                        updates.add(merged)
                                        changed.incrementAndGet()
                                    }
                                }
                            }
                            done.incrementAndGet()
                        }
                    }
                }
                if (updates.isNotEmpty()) {
                    dao.upsertTracks(updates)
                    persist(context)
                }
                _retagProgress.value = RetagProgress(done.get(), total, changed.get(), true)
            }
        }
        _retagProgress.value = RetagProgress(total, total, changed.get(), false)
        return changed.get()
    }

    /** True when the row's title still equals its filename stem — i.e. it was indexed
     *  from the path and never got real embedded tags. Cheap heuristic, no I/O. */
    private fun looksFilenameDerived(row: TrackEntity): Boolean {
        val stem = row.path.substringAfterLast('/').substringBeforeLast('.')
        return row.title.equals(stem, ignoreCase = true)
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
    ) {
        val dao = dao(context)
        val existing = dao.trackById(songId) ?: return
        val updated = existing.copy(
            title = title?.ifBlank { null } ?: existing.title,
            artist = artist?.ifBlank { null } ?: existing.artist,
            albumTitle = albumTitle?.ifBlank { null } ?: existing.albumTitle,
            year = year ?: existing.year,
            trackNumber = trackNumber ?: existing.trackNumber,
            durationMs = durationMs?.takeIf { it > 0 } ?: existing.durationMs,
        )
        if (updated == existing) return
        dao.upsertTracks(listOf(updated))
        persist(context)
    }
}

/** Deterministic primary key so re-imports of the same file update in place rather
 *  than duplicating. Matches [TrackEntity.id]'s documented `sha1(type+source+path)`. */
fun trackIdFor(type: SourceType, sourceId: String, path: String): String {
    val digest = MessageDigest.getInstance("SHA-1")
        .digest("$type|$sourceId|$path".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

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
)
