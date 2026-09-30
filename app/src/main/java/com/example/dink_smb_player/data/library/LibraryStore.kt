package com.example.dink_smb_player.data.library

import android.content.Context
import com.example.dink_smb_player.data.index.SourceEntity
import com.example.dink_smb_player.data.index.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File

/**
 * Disk persistence for the in-memory library index. The index ([com.example.dink_smb_player.data.index.MediaIndex])
 * is rebuilt every process; without this, imported SMB tracks vanish on restart
 * (local tracks survive only because LocalSyncWorker re-queries MediaStore at boot).
 *
 * Single JSON file in filesDir — fine for the track counts a TV music library holds.
 * Swap for the Room table once KSP supports AGP 9. Always touched off the main thread.
 *
 * Writes are atomic (temp file + fsync + ATOMIC_MOVE) and serialized through [ioLock] so a
 * crash mid-write or two concurrent persisters (UI boot refresh racing a worker)
 * can never leave a torn/half file — torn files are what previously failed to parse
 * on restart and let an empty snapshot clobber a full library. A known-good
 * `library_index.bak.json` is rotated once per process and used when the main file is
 * unreadable; unreadable files are kept as timestamped `*.corrupt-<ms>.json` copies.
 */
object LibraryStore {

    @Serializable
    data class Snapshot(
        val tracks: List<TrackEntity> = emptyList(),
        val sources: List<SourceEntity> = emptyList(),
    )

    /** Outcome of [load]. Distinguishing [Missing] (legit first run) from [Corrupt]
     *  (file present but unreadable) is critical: the caller must NOT overwrite a
     *  corrupt file with an empty index — that is how the library got wiped.
     *  [Transient] (I/O error or OOM, after retries) says nothing about the file's
     *  contents: the caller must not write, and should retry the load later.
     *  [Corrupt.quarantined]: the unreadable file(s) were moved aside, so writing no longer
     *  overwrites anything recoverable. */
    sealed interface LoadResult {
        data class Ok(val snapshot: Snapshot, val fromBackup: Boolean = false) : LoadResult
        data object Missing : LoadResult
        data class Corrupt(val error: Throwable, val quarantined: Boolean = false) : LoadResult
        data class Transient(val error: Throwable) : LoadResult
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val ioLock = Mutex()

    @Volatile private var guarded: GuardedJsonFile<Snapshot>? = null

    private fun guarded(context: Context): GuardedJsonFile<Snapshot> =
        guarded ?: synchronized(this) {
            guarded ?: guardedFile(context.applicationContext.filesDir).also { guarded = it }
        }

    // Stream the encode straight to the file instead of building one giant String first —
    // halves peak heap and avoids a full extra copy of a multi-MB snapshot on every persist.
    // Parse straight off a buffered file stream too — never materialises the whole file as
    // a String, so cold-boot restore of a big index is faster and uses far less peak memory.
    @OptIn(ExperimentalSerializationApi::class)
    internal fun guardedFile(dir: File): GuardedJsonFile<Snapshot> = GuardedJsonFile(
        file = File(dir, "library_index.json"),
        tag = "LibraryStore",
        decode = { json.decodeFromStream<Snapshot>(it) },
        encode = { snap, out -> json.encodeToStream(snap, out) },
    )

    /** Success only once the snapshot is durably on disk. [snapshot] is taken INSIDE [ioLock]
     *  (LIB-12): two persisters that each captured the index before queueing on the lock could
     *  otherwise finish out of order and leave the OLDER snapshot on disk. */
    suspend fun save(
        context: Context,
        snapshot: () -> Pair<List<TrackEntity>, List<SourceEntity>>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        ioLock.withLock {
            val (tracks, sources) = snapshot()
            guarded(context).saveResult(Snapshot(tracks, sources)).onFailure {
                android.util.Log.e("LibraryStore", "save failed: ${tracks.size} tracks, ${sources.size} sources")
            }
        }
    }

    suspend fun load(context: Context): LoadResult = withContext(Dispatchers.IO) {
        ioLock.withLock { toLoadResult(guarded(context).loadWithRetry()) }
    }

    internal fun toLoadResult(r: GuardedJsonFile.Load<Snapshot>): LoadResult = when (r) {
        is GuardedJsonFile.Load.Ok -> LoadResult.Ok(r.value, r.fromBackup)
        GuardedJsonFile.Load.Missing -> LoadResult.Missing
        is GuardedJsonFile.Load.Corrupt -> LoadResult.Corrupt(r.error, r.quarantined)
        is GuardedJsonFile.Load.Transient -> LoadResult.Transient(r.error)
    }
}
