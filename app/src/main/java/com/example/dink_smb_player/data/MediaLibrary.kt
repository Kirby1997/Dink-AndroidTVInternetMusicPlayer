package com.example.dink_smb_player.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
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
    @Volatile private var loaded = false
    @Volatile private var lastRefreshMs = 0L
    private val refreshLock = Mutex()

    /** A non-forced refresh within this window of the last one is skipped. Launch used to
     *  run 2–3 back-to-back (app-start worker, UI loadOnce, monitor catch-up), each a full
     *  index merge + grouping recompute + library-file rewrite competing with first frames. */
    private const val FRESH_MS = 60_000L

    /** Stable source id for all MediaStore-indexed local audio. */
    const val LOCAL_SOURCE_ID = "local-mediastore"

    /** Re-query MediaStore. Cheap to call repeatedly — MediaStore caches the index.
     *  Also mirrors the result into the unified library index so local tracks appear
     *  alongside imported SMB/cloud tracks in the Library screens. */
    suspend fun refresh(context: Context, force: Boolean = false) = refreshLock.withLock {
        if (!force && loaded && System.currentTimeMillis() - lastRefreshMs < FRESH_MS) return@withLock
        val list = withContext(Dispatchers.IO) { MediaStoreAudio.query(context.applicationContext) }
        localSongs.clear()
        localSongs.addAll(list)
        loaded = true
        lastRefreshMs = System.currentTimeMillis()
        // Index merge is CPU work — keep it off Main even when a screen calls us from there.
        withContext(Dispatchers.Default) { importLocal(context, list) }
    }

    private suspend fun importLocal(context: Context, list: List<Song>) {
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
    }

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
