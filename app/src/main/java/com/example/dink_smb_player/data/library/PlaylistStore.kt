package com.example.dink_smb_player.data.library

import android.content.Context
import com.example.dink_smb_player.data.model.Playlist
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
 * Disk persistence for user playlists. Single JSON file in filesDir, atomic write
 * (temp + fsync + ATOMIC_MOVE) serialized through [ioLock], mirroring [LibraryStore]. Unlike
 * the library index, playlists aren't re-derivable from any source — they're the
 * user's own data — so a torn write or accidental overwrite is unrecoverable; the
 * atomic move is what prevents it. A known-good `playlists.bak.json` is the fallback
 * when the main file is unreadable, and unreadable files are kept as timestamped
 * `*.corrupt-<ms>.json` copies rather than overwritten.
 */
object PlaylistStore {

    @Serializable
    data class Snapshot(val playlists: List<Playlist> = emptyList())

    /** See [LibraryStore.LoadResult] — same contract: never write over [Corrupt]; retry
     *  [Transient]. */
    sealed interface LoadResult {
        data class Ok(val playlists: List<Playlist>, val fromBackup: Boolean = false) : LoadResult
        data object Missing : LoadResult
        data class Corrupt(val error: Throwable) : LoadResult
        data class Transient(val error: Throwable) : LoadResult
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val ioLock = Mutex()

    @Volatile private var guarded: GuardedJsonFile<Snapshot>? = null

    private fun guarded(context: Context): GuardedJsonFile<Snapshot> =
        guarded ?: synchronized(this) {
            guarded ?: guardedFile(context.applicationContext.filesDir).also { guarded = it }
        }

    @OptIn(ExperimentalSerializationApi::class)
    internal fun guardedFile(dir: File): GuardedJsonFile<Snapshot> = GuardedJsonFile(
        file = File(dir, "playlists.json"),
        tag = "PlaylistStore",
        decode = { json.decodeFromStream<Snapshot>(it) },
        encode = { snap, out -> json.encodeToStream(snap, out) },
    )

    /** Test hook: forget the cached file so the next call re-resolves filesDir. */
    internal fun resetForTest() { guarded = null }

    /** Returns true only once [playlists] are durably on disk. */
    suspend fun save(context: Context, playlists: List<Playlist>): Boolean = withContext(Dispatchers.IO) {
        ioLock.withLock { guarded(context).save(Snapshot(playlists)) }
    }

    suspend fun load(context: Context): LoadResult = withContext(Dispatchers.IO) {
        ioLock.withLock { toLoadResult(guarded(context).loadWithRetry()) }
    }

    internal fun toLoadResult(r: GuardedJsonFile.Load<Snapshot>): LoadResult = when (r) {
        is GuardedJsonFile.Load.Ok -> LoadResult.Ok(r.value.playlists, r.fromBackup)
        GuardedJsonFile.Load.Missing -> LoadResult.Missing
        is GuardedJsonFile.Load.Corrupt -> LoadResult.Corrupt(r.error)
        is GuardedJsonFile.Load.Transient -> LoadResult.Transient(r.error)
    }
}
