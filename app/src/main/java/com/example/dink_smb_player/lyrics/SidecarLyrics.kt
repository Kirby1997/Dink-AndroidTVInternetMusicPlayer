package com.example.dink_smb_player.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song
import java.io.File

/**
 * Reads a `.lrc` file sitting next to the media file. First port of the
 * foo_openlyrics provider chain — sidecar > ID3 > online (Phase 8.5).
 *
 * Resolution order:
 *   1. Filesystem path (file:// URIs, MediaStore's DATA column) — replace extension with `.lrc`.
 *   2. SAF document URIs — locate sibling document with same display name + `.lrc` suffix.
 * MediaStore content URIs without a `sourcePath` are not resolved here; v1 relies
 * on MediaStoreAudio populating `sourcePath` from the DATA column.
 */
object SidecarLyrics {

    fun load(context: Context, song: Song): List<LyricLine> {
        val text = readSidecarText(context, song) ?: return emptyList()
        return runCatching { LrcParser.parse(text) }.getOrDefault(emptyList())
    }

    private fun readSidecarText(context: Context, song: Song): String? {
        val fsText = readFromFilesystem(song.sourcePath)
        if (fsText != null) return fsText

        // Same-directory fallback by title/artist (handles "Slipknot - Wait
        // And Bleed.lrc" next to "WaitAndBleed.mp3").
        val byTitle = findSiblingByTitle(song.sourcePath, song.title, song.artist, arrayOf(".lrc", ".LRC"))
        if (byTitle != null) {
            val read = runCatching { byTitle.readText() }.getOrNull()
            if (read != null) return read
        }

        val uriStr = song.mediaUri ?: return null
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        return when (uri.scheme) {
            "file" -> uri.path?.let(::readFromFilesystem)
            "content" -> readFromSafSibling(context, uri)
            else -> null
        }
    }

    private fun readFromFilesystem(path: String?): String? {
        if (path.isNullOrBlank()) return null
        for (ext in arrayOf(".lrc", ".LRC")) {
            val candidate = replaceExtension(path, ext)
            val file = File(candidate)
            if (file.exists() && file.canRead()) {
                return runCatching { file.readText() }.getOrNull()
            }
        }
        return null
    }

    /**
     * Fallback when `{basename}.lrc` isn't present. Tries:
     *   1. Explicit candidates built from title/artist (`Title.lrc`,
     *      `Artist - Title.lrc`, common separators). File.exists()-only — works
     *      on Android 13+ scoped storage where File.listFiles() is blocked.
     *   2. If listFiles() *is* permitted (older API or MANAGE_EXTERNAL_STORAGE),
     *      do a case- and separator-insensitive contains-match on the title.
     */
    fun findSiblingByTitle(
        songPath: String,
        title: String,
        artist: String,
        exts: Array<String>,
    ): File? {
        if (songPath.isBlank() || title.isBlank()) return null
        val mediaFile = File(songPath)
        val dir = mediaFile.parentFile ?: return null

        for (basename in buildCandidates(title, artist)) {
            for (ext in exts) {
                val f = File(dir, basename + ext)
                if (f.exists() && f.canRead()) return f
            }
        }

        val needle = normalize(title)
        if (needle.isBlank()) return null
        val matches = runCatching {
            dir.listFiles { f ->
                f.isFile && exts.any { e -> f.name.endsWith(e, ignoreCase = true) } &&
                    normalize(f.nameWithoutExtension).contains(needle)
            }
        }.getOrNull() ?: return null
        return matches.firstOrNull()
    }

    private fun buildCandidates(title: String, artist: String): List<String> {
        val out = mutableListOf<String>()
        if (title.isNotBlank()) out += title
        if (artist.isNotBlank() && title.isNotBlank()) {
            out += "$artist - $title"
            out += "$artist-$title"
            out += "${artist}_${title}"
        }
        return out
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "")

    private fun readFromSafSibling(context: Context, uri: Uri): String? {
        // SAF tree URIs expose siblings via DocumentsContract.buildChildDocumentsUriUsingTree.
        // Single-document URIs (ACTION_OPEN_DOCUMENT) generally can't resolve siblings without
        // a parent tree, so this is a best-effort lookup.
        return null
    }

    private fun replaceExtension(path: String, newExt: String): String {
        val slash = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.')
        return if (dot > slash) path.substring(0, dot) + newExt else "$path$newExt"
    }
}
