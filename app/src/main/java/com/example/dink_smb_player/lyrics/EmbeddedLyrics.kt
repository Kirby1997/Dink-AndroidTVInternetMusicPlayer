@file:OptIn(UnstableApi::class)

package com.example.dink_smb_player.lyrics

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.data.source.ReadResult
import com.example.dink_smb_player.data.source.TagReader
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Embedded lyrics, read on demand for the current track (LYR-1): the ID3 USLT / SYLT
 * frames, MP4 `©lyr`, and FLAC/Ogg `LYRICS` comments — over the network for SMB tracks,
 * through [TagReader.readLyricEntries] (Media3 over DinkDataSourceFactory, header bytes
 * only). Local files whose container Media3 surfaces nothing for fall back to the old
 * jaudiotagger read ([Id3Lyrics]).
 *
 * Returns [LyricResult.Synced] for SYLT (millisecond stamps) or LRC text stashed in a
 * lyrics tag, [LyricResult.Plain] for plain text, else [LyricResult.None]. Throws
 * IOException when the read failed transiently, so the chain doesn't cache a miss.
 */
internal object EmbeddedLyrics {

    suspend fun load(context: Context, song: Song): LyricResult {
        val uri = song.mediaUri
        if (uri != null) {
            val read = runInterruptible(Dispatchers.IO) { TagReader.readLyricEntries(context, uri) }
            val fromTags = fromEntries(read.valueOrNull().orEmpty())
            if (fromTags != LyricResult.None) return fromTags
            if (read is ReadResult.Error) throw IOException("embedded lyric read failed", read.cause)
        }
        val path = song.sourcePath
        if (path.isNotBlank() && runCatching { File(path).isFile }.getOrDefault(false)) {
            val lines = runInterruptible(Dispatchers.IO) { Id3Lyrics.load(song) }
            if (lines.isNotEmpty()) {
                return if (lines.any { it.timeSec > 0f }) LyricResult.Synced(lines) else LyricResult.Plain(lines)
            }
        }
        return LyricResult.None
    }

    private val LYRIC_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS")

    /** Interpret the lyric-bearing entries: SYLT first, then any text carrying LRC
     *  timestamps, then plain text. */
    fun fromEntries(entries: List<Metadata.Entry>): LyricResult {
        val texts = ArrayList<String>()
        for (e in entries) {
            when {
                e is BinaryFrame && e.id == "SYLT" -> parseSylt(e.data)?.let { return LyricResult.Synced(it) }
                e is BinaryFrame && e.id == "USLT" -> parseUslt(e.data)?.let { texts += it }
                e is TextInformationFrame && e.id == "USLT" -> e.value.takeIf { it.isNotBlank() }?.let { texts += it }
                e is TextInformationFrame && e.id == "TXXX" &&
                    e.description?.uppercase() in LYRIC_KEYS -> e.value.takeIf { it.isNotBlank() }?.let { texts += it }
                e is VorbisComment && e.key.uppercase() in LYRIC_KEYS ->
                    e.value.takeIf { it.isNotBlank() }?.let { texts += it }
            }
        }
        for (t in texts) {
            val parsed = runCatching { LrcParser.parse(t) }.getOrDefault(emptyList())
            if (parsed.any { it.timeSec > 0f }) return LyricResult.Synced(parsed)
        }
        val plain = texts.firstOrNull()?.let { plainToLines(it) }.orEmpty()
        return if (plain.isEmpty()) LyricResult.None else LyricResult.Plain(plain)
    }

    /** USLT body: encoding(1) language(3) descriptor(terminated) text. */
    fun parseUslt(data: ByteArray): String? {
        if (data.size < 5) return null
        val enc = data[0].toInt()
        val charset = charsetFor(enc) ?: return null
        val descEnd = terminatorEnd(data, 4, enc) ?: return null
        val text = String(data, descEnd, data.size - descEnd, charset).trimEnd('\u0000').trim()
        return text.ifEmpty { null }
    }

    /**
     * SYLT body: encoding(1) language(3) timestampFormat(1) contentType(1) descriptor
     * (terminated), then repeated { text(terminated) timestamp(4, big-endian) }. Only
     * millisecond stamps (format 2) are usable; MPEG-frame stamps need the frame rate.
     * Word-level taggers start each new line's first syllable with a newline — those
     * syllables are joined into lines stamped with their first syllable's time.
     */
    fun parseSylt(data: ByteArray): List<LyricLine>? {
        if (data.size < 7) return null
        val enc = data[0].toInt()
        val charset = charsetFor(enc) ?: return null
        if (data[4].toInt() != 2) return null
        var pos = terminatorEnd(data, 6, enc) ?: return null
        val entries = ArrayList<Pair<String, Long>>()
        while (pos < data.size) {
            val textEnd = terminatorStart(data, pos, enc) ?: break
            val text = String(data, pos, textEnd - pos, charset).removePrefix("\uFEFF")
            val stampAt = textEnd + termWidth(enc)
            if (stampAt + 4 > data.size) break
            val ms = ((data[stampAt].toLong() and 0xFF) shl 24) or
                ((data[stampAt + 1].toLong() and 0xFF) shl 16) or
                ((data[stampAt + 2].toLong() and 0xFF) shl 8) or
                (data[stampAt + 3].toLong() and 0xFF)
            entries += text to ms
            pos = stampAt + 4
        }
        if (entries.isEmpty()) return null
        val wordLevel = entries.drop(1).any { it.first.startsWith("\n") || it.first.startsWith("\r") }
        val lines = ArrayList<LyricLine>()
        if (wordLevel) {
            var buf = StringBuilder()
            var start = entries[0].second
            for ((text, ms) in entries) {
                if ((text.startsWith("\n") || text.startsWith("\r")) && buf.isNotBlank()) {
                    lines += LyricLine(start / 1000f, buf.toString().trim())
                    buf = StringBuilder()
                    start = ms
                } else if (buf.isEmpty()) {
                    start = ms
                }
                buf.append(text.trimStart('\n', '\r'))
            }
            if (buf.isNotBlank()) lines += LyricLine(start / 1000f, buf.toString().trim())
        } else {
            for ((text, ms) in entries) {
                val t = text.trim()
                if (t.isNotEmpty()) lines += LyricLine(ms / 1000f, t)
            }
        }
        return lines.takeIf { l -> l.isNotEmpty() && l.any { it.timeSec > 0f } }
    }

    private fun charsetFor(enc: Int): Charset? = when (enc) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> null
    }

    private fun termWidth(enc: Int) = if (enc == 1 || enc == 2) 2 else 1

    /** Index of the terminator of the string starting at [from], or null if none. */
    private fun terminatorStart(data: ByteArray, from: Int, enc: Int): Int? {
        if (termWidth(enc) == 1) {
            for (i in from until data.size) if (data[i].toInt() == 0) return i
            return null
        }
        var i = from
        while (i + 1 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i
            i += 2
        }
        return null
    }

    /** Index just past the terminator of the string starting at [from]. */
    private fun terminatorEnd(data: ByteArray, from: Int, enc: Int): Int? =
        terminatorStart(data, from, enc)?.let { it + termWidth(enc) }
}
