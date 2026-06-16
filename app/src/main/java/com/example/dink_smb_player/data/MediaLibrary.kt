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
    private var loaded = false

    /** Stable source id for all MediaStore-indexed local audio. */
    const val LOCAL_SOURCE_ID = "local-mediastore"

    /** Re-query MediaStore. Cheap to call repeatedly — MediaStore caches the index.
     *  Also mirrors the result into the unified library index so local tracks appear
     *  alongside imported SMB/cloud tracks in the Library screens. */
    suspend fun refresh(context: Context) {
        val list = withContext(Dispatchers.IO) { MediaStoreAudio.query(context.applicationContext) }
        localSongs.clear()
        localSongs.addAll(list)
        loaded = true
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
