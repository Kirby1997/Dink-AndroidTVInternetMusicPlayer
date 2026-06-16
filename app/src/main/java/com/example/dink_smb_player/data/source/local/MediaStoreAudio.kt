package com.example.dink_smb_player.data.source.local

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.example.dink_smb_player.data.MediaLibrary
import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.library.trackIdFor
import com.example.dink_smb_player.data.model.Song

/**
 * MediaStore-backed audio enumeration. Cheaper than walking the filesystem because
 * Android indexes every audio file on device, internal + removable. Returned
 * [Song] entries carry a content:// URI in [Song.mediaUri] which ExoPlayer plays
 * directly via the ContentResolver.
 *
 * Phase 6.5 adds a per-volume browser on top of this for USB / SD-card filtering.
 */
object MediaStoreAudio {

    fun query(context: Context): List<Song> {
        val out = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id,
                )
                val data = c.getString(dataCol) ?: ""
                val pathKey = data.ifEmpty { "media-$id" }
                out += Song(
                    // Canonical index id, so this Song == its local TrackEntity row.
                    id = trackIdFor(SourceType.Local, MediaLibrary.LOCAL_SOURCE_ID, pathKey),
                    title = c.getString(titleCol) ?: "Unknown",
                    artist = c.getString(artistCol) ?: "Unknown",
                    albumId = null,
                    albumTitle = c.getString(albumCol),
                    durationSec = (c.getLong(durCol) / 1000).toInt(),
                    playCount = 0,
                    sourcePath = data,
                    bitrate = c.getString(mimeCol)?.substringAfter('/')?.uppercase() ?: "—",
                    mediaUri = uri.toString(),
                )
            }
        }
        return out
    }
}
