package com.example.dink_smb_player.data.library

import android.content.Context
import com.example.dink_smb_player.data.model.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Disk persistence for user playlists. Single JSON file in filesDir, atomic write
 * (temp + ATOMIC_MOVE) serialized through [ioLock], mirroring [LibraryStore]. Unlike
 * the library index, playlists aren't re-derivable from any source — they're the
 * user's own data — so a torn write or accidental overwrite is unrecoverable; the
 * atomic move is what prevents it.
 */
object PlaylistStore {

    @Serializable
    data class Snapshot(val playlists: List<Playlist> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    private val ioLock = Mutex()

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, "playlists.json")

    suspend fun save(context: Context, playlists: List<Playlist>) = withContext(Dispatchers.IO) {
        ioLock.withLock {
            runCatching {
                val f = file(context)
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(json.encodeToString(Snapshot(playlists)))
                Files.move(
                    tmp.toPath(), f.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            }.onFailure { t ->
                android.util.Log.e("PlaylistStore", "save failed: ${playlists.size} playlists", t)
            }
            Unit
        }
    }

    /** Returns null when the file is missing (first run) OR unreadable — either way
     *  the caller starts with no playlists. A corrupt file is preserved for recovery
     *  rather than silently overwritten on the next save. */
    suspend fun load(context: Context): List<Playlist>? = withContext(Dispatchers.IO) {
        ioLock.withLock {
            val f = file(context)
            if (!f.exists()) return@withContext null
            runCatching { json.decodeFromString<Snapshot>(f.readText()).playlists }.getOrElse { t ->
                runCatching {
                    f.copyTo(File(f.parentFile, "playlists.corrupt.json"), overwrite = true)
                }
                android.util.Log.e("PlaylistStore", "load failed; preserved as playlists.corrupt.json", t)
                null
            }
        }
    }
}
