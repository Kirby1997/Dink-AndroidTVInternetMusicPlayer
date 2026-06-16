package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * Reads embedded lyrics from a media file's ID3v2 tag using jaudiotagger.
 *
 * Source preference:
 *   1. USLT (unsynchronised text) via [FieldKey.LYRICS]. If its body itself contains
 *      LRC-style timestamps (some encoders stash sync lyrics here), we run it through
 *      [LrcParser] to surface them as synced lines.
 *   2. Plain unsynced text → a single [LyricLine] at t=0 so the lyrics pane still
 *      shows something rather than falling through to the online chain.
 *
 * SYLT (synchronised lyric) frame extraction is intentionally skipped in Phase 6 —
 * jaudiotagger's SYLT body API differs across releases, and the offline corpus this
 * gates on stores synced lyrics in sidecar `.lrc` files almost exclusively. Phase 8.5
 * picks SYLT up as part of the full foo_openlyrics chain.
 */
object Id3Lyrics {

    private val EMBEDDED_LRC_HINT = Regex("""\[\d{1,3}:\d{2}""")

    fun load(song: Song): List<LyricLine> {
        val path = song.sourcePath
        if (path.isBlank()) return emptyList()
        val file = File(path)
        if (!file.exists() || !file.canRead()) return emptyList()

        return runCatching {
            val audio = AudioFileIO.read(file) ?: return@runCatching emptyList()
            val tag = audio.tag ?: return@runCatching emptyList()
            val raw = runCatching { tag.getFirst(FieldKey.LYRICS) }.getOrNull()
            if (raw.isNullOrBlank()) return@runCatching emptyList()
            if (EMBEDDED_LRC_HINT.containsMatchIn(raw)) {
                LrcParser.parse(raw).ifEmpty { listOf(LyricLine(0f, raw.trim())) }
            } else {
                listOf(LyricLine(0f, raw.trim()))
            }
        }.getOrDefault(emptyList())
    }
}
