package com.example.dink_smb_player.data.source.smb

import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.library.trackIdFor
import com.example.dink_smb_player.data.model.SmbShare
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.data.prefs.SmbCreds
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.smbj.share.DiskShare
import java.net.URLEncoder

/**
 * Walks an [SmbShare] and produces a [Song] list pointing at smb:// URIs that
 * [SmbDataSource] can later read for playback. No tag parsing in Phase 7 —
 * filename → title, parent dir → album, grandparent dir → artist. The
 * jaudiotagger-over-smbj pass lands in Phase 8.5 alongside the lyrics chain.
 *
 * mediaUri shape: `smb://host:port/share/dir/sub/file.mp3` (path components are
 * URL-encoded so spaces / unicode survive Media3's URI parsing).
 */
object SmbSync {

    private val AUDIO_EXT = setOf("mp3", "flac", "ogg", "oga", "opus", "m4a", "wav", "aac", "wma")
    private const val MAX_DEPTH = 8
    private const val MAX_FILES = 50_000

    fun enumerate(share: SmbShare, creds: SmbCreds?): Result<List<Song>> = runCatching {
        val disk = SmbClient.share(share.id, share.host, share.port, share.shareName, creds)
        val out = mutableListOf<Song>()
        walk(disk, share, "", 0, out)
        out
    }

    private fun walk(
        disk: DiskShare,
        share: SmbShare,
        smbPath: String,
        depth: Int,
        out: MutableList<Song>,
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        val entries: List<FileIdBothDirectoryInformation> = try {
            disk.list(smbPath)
        } catch (e: Throwable) {
            // Permission-denied / not-listable folders — skip rather than blow up the whole sync.
            return
        }
        for (entry in entries) {
            val name = entry.fileName
            if (name == "." || name == "..") continue
            val isDir = (entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            val childSmbPath = if (smbPath.isEmpty()) name else "$smbPath\\$name"
            if (isDir) {
                walk(disk, share, childSmbPath, depth + 1, out)
            } else if (isAudio(name)) {
                out += songFor(share, childSmbPath)
                if (out.size >= MAX_FILES) return
            }
        }
    }

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
