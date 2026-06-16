package com.example.dink_smb_player.lyrics

import android.content.Context
import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song
import java.io.File

/**
 * Reads a plain `.txt` sidecar lyric file (no timestamps). Each non-blank line
 * becomes a single [LyricLine] with [LyricLine.timeSec] = 0; [LyricChain] then
 * distributes the timestamps evenly across the song's runtime so the karaoke
 * pane still auto-scrolls.
 *
 * Looked up next to the media file with the same base name (`coldsilver.mp3`
 * → `coldsilver.txt`). Case-insensitive on the extension.
 */
object TxtLyrics {

    fun load(context: Context, song: Song): List<LyricLine> {
        val path = song.sourcePath
        if (path.isBlank()) return emptyList()
        val exts = arrayOf(".txt", ".TXT")
        for (ext in exts) {
            val candidate = replaceExtension(path, ext)
            val file = File(candidate)
            if (file.exists() && file.canRead()) {
                return parseFile(file)
            }
        }
        // Fallback: same-directory file whose name contains the song title.
        // Handles user-curated naming like "Starbenders - Cold Silver.txt".
        val byTitle = SidecarLyrics.findSiblingByTitle(path, song.title, song.artist, exts)
        if (byTitle != null) return parseFile(byTitle)
        return emptyList()
    }

    private fun parseFile(file: File): List<LyricLine> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { LyricLine(timeSec = 0f, text = it) }
            .toList()
    }

    private fun replaceExtension(path: String, newExt: String): String {
        val slash = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.')
        return if (dot > slash) path.substring(0, dot) + newExt else "$path$newExt"
    }
}
