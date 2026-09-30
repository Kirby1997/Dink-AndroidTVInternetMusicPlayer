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
 *
 * The playback POSITION also lives in its own tiny record ([Position]): it changes every
 * few seconds while playing, and checkpointing it used to rewrite the whole ~45 KB queue
 * file each time. [load] overlays it onto the session when it belongs to the same track.
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
        /** Unshuffled order of the saved tracks, as indices into [queueIds]; only written
         *  while [shuffle] is on, so a restored shuffle can be turned back off. */
        val baseOrder: List<Int>? = null,
    )

    /** Position checkpoint for [songId]; ignored unless it matches the session's track. */
    @Serializable
    data class Position(val songId: String, val positionSec: Float)

    private val json = Json { ignoreUnknownKeys = true }
    private val ioLock = Mutex()

    private const val SESSION_FILE = "nowplaying.json"
    private const val POSITION_FILE = "nowplaying_pos.json"

    private fun dir(context: Context): File = context.applicationContext.filesDir

    suspend fun save(context: Context, snapshot: Snapshot) = saveIn(dir(context), snapshot)
    suspend fun savePosition(context: Context, position: Position) = savePositionIn(dir(context), position)
    suspend fun load(context: Context): Snapshot? = loadFrom(dir(context))
    suspend fun clear(context: Context) = clearIn(dir(context))

    /** Cheap main-thread check (no parse): is there a session a media key could resume?
     *  Used by [DinkMediaButtonReceiver] before it starts the service in the foreground. */
    fun hasSession(context: Context): Boolean = hasSessionIn(dir(context))

    internal fun hasSessionIn(dir: File): Boolean =
        runCatching { File(dir, SESSION_FILE).let { it.isFile && it.length() > 2 } }.getOrDefault(false)

    internal suspend fun saveIn(dir: File, snapshot: Snapshot) = withContext(Dispatchers.IO) {
        ioLock.withLock {
            writeAtomic(File(dir, SESSION_FILE), json.encodeToString(Snapshot.serializer(), snapshot))
            // Keep the position record in step, so an older checkpoint for the same
            // track can't override the position this session was just saved with.
            snapshot.queueIds.getOrNull(snapshot.index)?.let { id ->
                writeAtomic(
                    File(dir, POSITION_FILE),
                    json.encodeToString(Position.serializer(), Position(id, snapshot.positionSec)),
                )
            }
        }
        Unit
    }

    internal suspend fun savePositionIn(dir: File, position: Position) = withContext(Dispatchers.IO) {
        ioLock.withLock {
            writeAtomic(File(dir, POSITION_FILE), json.encodeToString(Position.serializer(), position))
        }
        Unit
    }

    internal suspend fun loadFrom(dir: File): Snapshot? = withContext(Dispatchers.IO) {
        ioLock.withLock {
            val f = File(dir, SESSION_FILE)
            if (!f.exists()) return@withLock null
            val snap = runCatching { json.decodeFromString(Snapshot.serializer(), f.readText()) }
                .getOrNull()
                ?.takeIf { it.queueIds.isNotEmpty() }
                ?: return@withLock null
            // A missing or corrupt position record just leaves the session's own position.
            val pos = File(dir, POSITION_FILE).takeIf { it.exists() }?.let { pf ->
                runCatching { json.decodeFromString(Position.serializer(), pf.readText()) }.getOrNull()
            }
            if (pos != null && pos.songId == snap.queueIds.getOrNull(snap.index) && pos.positionSec >= 0f) {
                snap.copy(positionSec = pos.positionSec)
            } else snap
        }
    }

    internal suspend fun clearIn(dir: File) = withContext(Dispatchers.IO) {
        ioLock.withLock {
            runCatching { File(dir, SESSION_FILE).delete() }
            runCatching { File(dir, POSITION_FILE).delete() }
        }
        Unit
    }

    private fun writeAtomic(f: File, text: String) {
        runCatching {
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }
    }
}
