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
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Disk persistence for the in-memory library index. The index ([com.example.dink_smb_player.data.index.MediaIndex])
 * is rebuilt every process; without this, imported SMB tracks vanish on restart
 * (local tracks survive only because LocalSyncWorker re-queries MediaStore at boot).
 *
 * Single JSON file in filesDir — fine for the track counts a TV music library holds.
 * Swap for the Room table once KSP supports AGP 9. Always touched off the main thread.
 *
 * Writes are atomic (temp file + ATOMIC_MOVE) and serialized through [ioLock] so a
 * crash mid-write or two concurrent persisters (UI boot refresh racing a worker)
 * can never leave a torn/half file — torn files are what previously failed to parse
 * on restart and let an empty snapshot clobber a full library.
 */
object LibraryStore {

    @Serializable
    data class Snapshot(
        val tracks: List<TrackEntity> = emptyList(),
        val sources: List<SourceEntity> = emptyList(),
    )

    /** Outcome of [load]. Distinguishing [Missing] (legit first run) from [Corrupt]
     *  (file present but unreadable) is critical: the caller must NOT overwrite a
     *  corrupt file with an empty index — that is how the library got wiped. */
    sealed interface LoadResult {
        data class Ok(val snapshot: Snapshot) : LoadResult
        data object Missing : LoadResult
        data class Corrupt(val error: Throwable) : LoadResult
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val ioLock = Mutex()

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, "library_index.json")

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun save(context: Context, tracks: List<TrackEntity>, sources: List<SourceEntity>) =
        withContext(Dispatchers.IO) {
            ioLock.withLock {
                runCatching {
                    val f = file(context)
                    val tmp = File(f.parentFile, "${f.name}.tmp")
                    // Stream the encode straight to the file instead of building one giant
                    // String first — halves peak heap and avoids a full extra copy of a
                    // multi-MB snapshot on every persist.
                    BufferedOutputStream(FileOutputStream(tmp)).use { out ->
                        json.encodeToStream(Snapshot(tracks, sources), out)
                    }
                    // Atomic replace: a reader (or the next boot) sees either the old
                    // complete file or the new complete file, never a partial write.
                    Files.move(
                        tmp.toPath(), f.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                    )
                }.onFailure { t ->
                    android.util.Log.e("LibraryStore", "save failed: ${tracks.size} tracks, ${sources.size} sources", t)
                }
                Unit
            }
        }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun load(context: Context): LoadResult = withContext(Dispatchers.IO) {
        ioLock.withLock {
            val f = file(context)
            if (!f.exists()) return@withContext LoadResult.Missing
            // Parse straight off a buffered file stream — never materialises the whole
            // file as a String, so cold-boot restore of a big index is faster and uses
            // far less peak memory (the long pole before Home can show any track).
            runCatching { f.inputStream().buffered().use { json.decodeFromStream<Snapshot>(it) } }.fold(
                onSuccess = { LoadResult.Ok(it) },
                onFailure = { t ->
                    // Preserve the unreadable file for inspection/recovery instead of
                    // letting it get silently overwritten by an empty snapshot.
                    runCatching {
                        f.copyTo(File(f.parentFile, "library_index.corrupt.json"), overwrite = true)
                    }
                    android.util.Log.e("LibraryStore", "load failed; preserved as library_index.corrupt.json", t)
                    LoadResult.Corrupt(t)
                },
            )
        }
    }
}
