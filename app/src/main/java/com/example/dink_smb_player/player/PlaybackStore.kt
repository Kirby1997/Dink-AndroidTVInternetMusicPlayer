package com.example.dink_smb_player.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the "now playing" session — which track, where in it, and the surrounding
 * queue — so a relaunch resumes where the user left off (paused). Mirrors
 * [com.example.dink_smb_player.data.library.LibraryStore]: one small JSON file in
 * filesDir, atomic temp-file + rename, writes serialised through a Mutex.
 *
 * The queue is stored as SONG IDs, not whole rows: the library index is the source of
 * truth, so IDs re-resolve to fresh Song objects on restore (a track deleted from the
 * library since last session simply drops out). Only a window of up to
 * [MAX_QUEUE] ids centred on the current track is kept — a "shuffle all" queue can be
 * 25k tracks, and rewriting a 1 MB file on every skip would hammer flash for no real
 * gain; a thousand-track window still resumes next/prev seamlessly.
 */
object PlaybackStore {

    const val MAX_QUEUE = 1000

    @Serializable
    data class Snapshot(
        val version: Int = 1,
        /** Song ids, in play order, windowed around [index]. */
        val queueIds: List<String> = emptyList(),
        /** Index of the current track WITHIN [queueIds]. */
        val index: Int = 0,
        val positionSec: Float = 0f,
        val shuffle: Boolean = false,
        /** [RepeatMode] name (Off / All / One). */
        val repeat: String = RepeatMode.Off.name,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val ioLock = Mutex()

    private fun file(context: Context) =
        File(context.applicationContext.filesDir, "nowplaying.json")

    suspend fun save(context: Context, snapshot: Snapshot) = withContext(Dispatchers.IO) {
        ioLock.withLock {
            val f = file(context)
            runCatching {
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(json.encodeToString(Snapshot.serializer(), snapshot))
                if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
            }
        }
        Unit
    }

    suspend fun load(context: Context): Snapshot? = withContext(Dispatchers.IO) {
        ioLock.withLock {
            val f = file(context)
            if (!f.exists()) return@withLock null
            runCatching { json.decodeFromString(Snapshot.serializer(), f.readText()) }
                .getOrNull()
                ?.takeIf { it.queueIds.isNotEmpty() }
        }
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        ioLock.withLock { runCatching { file(context).delete() } }
        Unit
    }
}
