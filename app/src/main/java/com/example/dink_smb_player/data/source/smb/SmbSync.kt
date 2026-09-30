package com.example.dink_smb_player.data.source.smb

import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.library.trackIdFor
import com.example.dink_smb_player.data.model.SmbShare
import com.example.dink_smb_player.data.model.Song
import java.net.URLEncoder

/**
 * SMB path helpers shared by the importer ([SmbImporter]) and the folder browser
 * ([SmbBrowser]): the audio-extension filter, the playable smb:// URI, and a
 * filename-derived [Song] for browse-and-play before a file is indexed. (The old
 * whole-share walk that lived here was replaced by [SmbImporter.enumerate].)
 *
 * mediaUri shape: `smb://host:port/share/dir/sub/file.mp3` (path components are
 * URL-encoded so spaces / unicode survive Media3's URI parsing).
 */
object SmbSync {

    private val AUDIO_EXT = setOf("mp3", "flac", "ogg", "oga", "opus", "m4a", "wav", "aac", "wma")

    internal fun isAudio(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return false
        return name.substring(dot + 1).lowercase() in AUDIO_EXT
    }

    /** Builds the playable `smb://host:port/share/dir/file?sid=<id>` URI. Path
     *  components are URL-encoded so spaces / unicode survive Media3 URI parsing. */
    internal fun mediaUriFor(share: SmbShare, smbPath: String): String = buildString {
        append("smb://")
        append(share.host)
        append(':')
        append(share.port)
        append('/')
        append(URLEncoder.encode(share.shareName, "UTF-8").replace("+", "%20"))
        for (p in smbPath.split('\\')) {
            append('/')
            append(URLEncoder.encode(p, "UTF-8").replace("+", "%20"))
        }
        // sid lets SmbDataSource resolve the SmbShare + creds without parsing
        // host/share-name lookups (multiple shares may share a host).
        append("?sid=")
        append(URLEncoder.encode(share.id, "UTF-8"))
    }

    internal fun songFor(
        share: SmbShare,
        smbPath: String,
    ): Song {
        val parts = smbPath.split('\\')
        val fileName = parts.last()
        val title = fileName.substringBeforeLast('.')
        val parentDir = parts.dropLast(1).lastOrNull()
        val grandparentDir = parts.dropLast(2).lastOrNull()
        val uri = mediaUriFor(share, smbPath)
        val ext = fileName.substringAfterLast('.', "").uppercase()
        return Song(
            // Same id the importer writes to the index, so a browser-played track
            // and its indexed TrackEntity are one and the same row.
            id = trackIdFor(SourceType.Smb, share.id, smbPath),
            title = title,
            artist = grandparentDir ?: "Unknown",
            albumId = null,
            albumTitle = parentDir,
            durationSec = 0, // unknown until first prepare; ExoPlayer reports actual on load
            playCount = 0,
            sourcePath = "${share.mountPath}/$smbPath".replace('\\', '/'),
            bitrate = ext.ifEmpty { "—" },
            mediaUri = uri,
        )
    }
}
