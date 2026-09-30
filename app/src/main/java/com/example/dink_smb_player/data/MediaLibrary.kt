package com.example.dink_smb_player.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot
import com.example.dink_smb_player.data.index.SourceEntity
import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.data.source.local.MediaStoreAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide store of locally-indexed audio so [LocalStorageScreen] and
 * [SongsScreen] (and later the Library tab) share one list. Without this, songs
 * imported via MediaStore would only appear in the screen that scanned them.
 *
 * Phase 6.5 will extend this with per-volume (USB / SD) filtering on top of the
 * same backing list. Cloud + SMB sources land into their own per-source registries
 * managed by Phase 7 / Phase 8.
 */
object MediaLibrary {
    val localSongs = mutableStateListOf<Song>()

    /** A non-forced refresh within this window of the last one is skipped. Launch used to
     *  run 2–3 back-to-back (app-start worker, UI loadOnce, monitor catch-up), each a full
     *  index merge + grouping recompute + library-file rewrite competing with first frames. */
    private const val FRESH_MS = 60_000L

    private val gate = RefreshGate(FRESH_MS) { System.currentTimeMillis() }
    private val loaded: Boolean get() = gate.hasRun

    /** Stable source id for all MediaStore-indexed local audio. */
    const val LOCAL_SOURCE_ID = "local-mediastore"

    /** Re-query MediaStore. Cheap to call repeatedly — MediaStore caches the index.
     *  Also mirrors the result into the unified library index so local tracks appear
     *  alongside imported SMB/cloud tracks in the Library screens. Concurrent callers are
     *  serialized and coalesced by [RefreshGate] (LIB-17): one scan per launch. Returns the
     *  failure when the scan's result couldn't be saved to the library index (it is then in
     *  memory only); success otherwise, including a skipped or rejected scan. */
    suspend fun refresh(context: Context, force: Boolean = false): Result<Unit> {
        var saved: Result<Unit> = Result.success(Unit)
        gate.run(force) {
            val list = withContext(Dispatchers.IO) { MediaStoreAudio.query(context.applicationContext) }
            if (!scanApplies(list, force)) {
                // Not applied, so not a fresh scan: the next caller (Local Storage visit, loadOnce,
                // monitor catch-up, a volume mount) scans again instead of trusting this one.
                android.util.Log.w("MediaLibrary", "MediaStore returned no audio on an automatic refresh — keeping the indexed local tracks")
                return@run false
            }
            // One snapshot for clear + addAll, so no reader (LocalStorageScreen's "empty → scan"
            // check, Compose) ever observes the half-swapped, empty list.
            Snapshot.withMutableSnapshot {
                localSongs.clear()
                localSongs.addAll(list)
            }
            // Index merge is CPU work — keep it off Main even when a screen calls us from there.
            saved = withContext(Dispatchers.Default) { importLocal(context, list) }
            true
        }
        return saved
    }

    /** Whether a scan result may replace the local index. The import prunes every indexed local
     *  row the scan didn't return, so an EMPTY result on an automatic refresh (launch, monitor
     *  catch-up) — MediaStore not ready yet, a volume not mounted yet, a null cursor — would wipe
     *  every local track and its first-seen time. Only a forced scan (the user's Scan, or a
     *  volume mount/unmount event) may empty the local library. */
    internal fun scanApplies(scanned: List<Song>, force: Boolean): Boolean = force || scanned.isNotEmpty()

    private suspend fun importLocal(context: Context, list: List<Song>): Result<Unit> =
        LibraryRepository.importSource(
            context,
            SourceEntity(
                id = LOCAL_SOURCE_ID,
                type = SourceType.Local,
                displayName = "Local storage",
                createdAtMs = System.currentTimeMillis(),
                lastSyncMs = System.currentTimeMillis(),
                trackCount = list.size,
            ),
            list.mapNotNull { it.toLocalTrack() },
        )

    private fun Song.toLocalTrack(): TrackEntity? {
        val mediaUri = mediaUri ?: return null
        return TrackEntity(
            // MediaStoreAudio already stamped the canonical index id onto the Song;
            // reuse it so TrackEntity.id == Song.id exactly.
            id = id,
            title = title,
            artist = artist,
            albumTitle = albumTitle,
            durationMs = durationSec * 1000L,
            bitrate = bitrate.takeIf { it != "—" },
            sourceType = SourceType.Local,
            sourceId = LOCAL_SOURCE_ID,
            path = sourcePath,
            uri = mediaUri,
            sizeBytes = 0L,
            addedAtMs = System.currentTimeMillis(),
        )
    }

    /** Refresh only if we've never loaded before. Used at app start so SongsScreen
     *  shows imported tracks even if the user hasn't visited LocalStorageScreen yet. */
    suspend fun loadOnce(context: Context) {
        if (loaded) return
        refresh(context)
    }
}

/**
 * Serializes and coalesces local refreshes (LIB-17). Callers queue on one [Mutex]; when a caller
 * gets its turn it is skipped if
 *  - a scan that STARTED after this caller asked has already completed (it saw everything this
 *    caller could want — so N callers queued behind one in-flight scan cost one follow-up, not N), or
 *  - it is not forced and the last scan finished less than [freshMs] ago.
 * A failed scan (throws) or one whose result wasn't applied ([block] returns false — an empty
 * automatic scan) covers nobody and doesn't count as fresh, so the next caller scans again;
 * each caller still runs at most once, so nothing loops. Returns true if [block] ran.
 */
internal class RefreshGate(private val freshMs: Long, private val now: () -> Long) {
    private val lock = Mutex()
    private val requests = AtomicLong()
    @Volatile private var coveredThrough = 0L
    @Volatile private var lastRunMs: Long? = null

    val hasRun: Boolean get() = lastRunMs != null

    suspend fun run(force: Boolean, block: suspend () -> Boolean): Boolean {
        val ticket = requests.incrementAndGet()
        return lock.withLock {
            if (ticket <= coveredThrough) return@withLock false
            val last = lastRunMs
            if (!force && last != null && now() - last < freshMs) return@withLock false
            // Every request issued before this point is satisfied by this scan.
            val covers = requests.get()
            if (!block()) return@withLock true
            coveredThrough = covers
            lastRunMs = now()
            true
        }
    }
}
