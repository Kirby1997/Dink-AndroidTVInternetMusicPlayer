package com.example.dink_smb_player.data.source

import android.content.Context
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Reads tags that live at the END of a file — ID3v1 (last 128 bytes) and APEv2 (footer at
 * EOF) — which Media3's Mp3Extractor and the platform MediaMetadataRetriever both ignore.
 *
 * A large slice of older / ExactAudioCopy-ripped libraries carry ONLY these trailing tags
 * (no ID3v2 at the file start), so [TagReader] falls back here whenever the primary read
 * finds no title. Reads are done over the same byte-budgeted SMB [Media3MediaDataSource] as
 * [DurationReader] — just a seek to the tail — so nothing is downloaded.
 *
 * When both tags are present, APEv2 wins: its values are variable-length UTF-8, whereas
 * ID3v1 truncates every field to 30 bytes (so a long title is cut off).
 *
 * Blocks (network) — call from Dispatchers.IO. Returns null when neither tag is present or
 * anything goes wrong.
 */
object TagFallbackReader {

    // The tail read only ever touches ID3v1's 128 bytes plus an APE items block (text items
    // are a few hundred bytes; an APE tag with embedded art is larger but we cap the read so a
    // pathological tag can't drag megabytes over the network). A short deadline bounds the tail.
    private const val BUDGET_BYTES = 4L * 1024 * 1024
    private const val DEADLINE_MS = 8_000L
    private const val MAX_APE_ITEMS_BYTES = 1 * 1024 * 1024

    fun read(context: Context, uri: String): TagReader.Tags? {
        val src = Media3MediaDataSource(
            context.applicationContext, uri, BUDGET_BYTES, SystemClock.elapsedRealtime() + DEADLINE_MS,
        )
        return try {
            val size = src.getSize()
            if (size < 128) return null

            // ID3v1 sits in the final 128 bytes; a valid one starts with "TAG".
            val id3v1Buf = readFully(src, size - 128, 128)
            val hasId3v1 = id3v1Buf.size == 128 &&
                id3v1Buf[0] == 'T'.code.toByte() && id3v1Buf[1] == 'A'.code.toByte() && id3v1Buf[2] == 'G'.code.toByte()
            val id3v1 = if (hasId3v1) parseId3v1(id3v1Buf) else null

            // APEv2's 32-byte footer sits at EOF, or just before the ID3v1 tag when both exist.
            val apeRegionEnd = if (hasId3v1) size - 128 else size
            val ape = if (apeRegionEnd >= 32) parseApe(src, apeRegionEnd) else null

            // Prefer APE per field (untruncated UTF-8), fall back to ID3v1.
            val merged = TagReader.Tags(
                title = ape?.title ?: id3v1?.title,
                artist = ape?.artist ?: id3v1?.artist,
                album = ape?.album ?: id3v1?.album,
                year = ape?.year ?: id3v1?.year,
                trackNumber = ape?.trackNumber ?: id3v1?.trackNumber,
            )
            if (merged == TagReader.Tags()) null else merged
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { src.close() }
        }
    }

    /** Read exactly [len] bytes starting at [pos] (fewer if EOF/budget cuts it short). */
    private fun readFully(src: Media3MediaDataSource, pos: Long, len: Int): ByteArray {
        val b = ByteArray(len)
        var got = 0
        while (got < len) {
            val r = src.readAt(pos + got, b, got, len - got)
            if (r <= 0) break
            got += r
        }
        return if (got == len) b else b.copyOf(got)
    }

    // ---- ID3v1 (fixed 128-byte layout) ---------------------------------------------------
    // 0..2 "TAG" · 3..32 title · 33..62 artist · 63..92 album · 93..96 year · 97..126 comment
    // · 127 genre. ID3v1.1: when comment[28]==0 and comment[29]!=0, comment[29] is the track no.
    private fun parseId3v1(b: ByteArray): TagReader.Tags {
        // ID3v1.1 track number (byte 126 when byte 125 is a NUL terminator).
        val track = if (b[125] == 0.toByte() && b[126] != 0.toByte()) (b[126].toInt() and 0xFF) else null
        return TagReader.Tags(
            title = decodeField(b, 3, 30),
            artist = decodeField(b, 33, 30),
            album = decodeField(b, 63, 30),
            year = decodeField(b, 93, 4)?.toIntOrNull(),
            trackNumber = track,
        )
    }

    /** Decode a fixed-width ID3v1 field, trimming trailing NUL/space padding. ID3v1's official
     *  charset is Latin-1, but real-world taggers frequently write UTF-8 into it — so try strict
     *  UTF-8 first (only valid UTF-8 succeeds) and fall back to Latin-1 (every byte maps 1:1, so
     *  a lone ö = 0xF6 stays correct). A field that STILL looks like mojibake after decoding came
     *  from a genuinely corrupt (double-encoded) tag — reject it so it can't clobber a clean
     *  path-derived value. */
    private fun decodeField(b: ByteArray, off: Int, len: Int): String? {
        var end = off + len
        while (end > off && (b[end - 1] == 0.toByte() || b[end - 1] == 0x20.toByte())) end--
        if (end <= off) return null
        val n = end - off
        val decoded = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, off, n)).toString()
        } catch (e: Exception) {
            String(b, off, n, StandardCharsets.ISO_8859_1)
        }
        val s = decoded.trim().ifBlank { null } ?: return null
        return if (looksMojibake(s)) null else s
    }

    /** True when a string carries the tell-tale fingerprint of UTF-8 bytes decoded as Latin-1:
     *  a U+00C2/C3/C5 (Â/Ã/Å) immediately followed by a continuation-range char, or the U+FFFD
     *  replacement char. Such text is unrecoverable garbage, not a real name. */
    private fun looksMojibake(s: String): Boolean {
        for (i in s.indices) {
            val c = s[i].code
            if (c == 0xFFFD) return true // replacement char
            // U+00C2/C3/C5 (Â/Ã/Å) followed by a Latin-1 continuation byte = UTF-8-as-Latin-1.
            if ((c == 0xC2 || c == 0xC3 || c == 0xC5) && i + 1 < s.length && s[i + 1].code in 0x80..0xBF) return true
        }
        return false
    }

    // ---- APEv2 (footer at [regionEnd], items block precedes it) --------------------------
    // Footer: "APETAGEX"(8) version(4 LE) tagSize(4 LE, items+footer) itemCount(4 LE) flags(4)
    // reserved(8). Each item: valueLen(4 LE) flags(4) key(NUL-terminated ASCII) 0x00 value(UTF-8).
    private fun parseApe(src: Media3MediaDataSource, regionEnd: Long): TagReader.Tags? {
        val footer = readFully(src, regionEnd - 32, 32)
        if (footer.size != 32) return null
        if (String(footer, 0, 8, StandardCharsets.US_ASCII) != "APETAGEX") return null
        val tagSize = le32(footer, 8 + 4)          // items + footer
        val itemCount = le32(footer, 8 + 4 + 4)
        val itemsLen = tagSize - 32
        if (itemsLen <= 0 || itemsLen > MAX_APE_ITEMS_BYTES || itemCount <= 0) return null
        val items = readFully(src, regionEnd - tagSize, itemsLen)
        if (items.size != itemsLen) return null

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var year: String? = null
        var track: String? = null
        var p = 0
        var n = 0
        while (n < itemCount && p + 8 <= items.size) {
            val valueLen = le32(items, p)
            p += 8 // valueLen + flags
            val keyStart = p
            while (p < items.size && items[p] != 0.toByte()) p++
            if (p >= items.size) break
            val key = String(items, keyStart, p - keyStart, StandardCharsets.US_ASCII)
            p++ // skip NUL
            if (valueLen < 0 || p + valueLen > items.size) break
            val value = String(items, p, valueLen, StandardCharsets.UTF_8).trim()
                .ifBlank { null }?.takeUnless { looksMojibake(it) }
            p += valueLen
            when (key.lowercase()) {
                "title" -> title = value
                "artist" -> artist = value
                "album" -> album = value
                "year", "date" -> year = year ?: value
                "track" -> track = value
            }
            n++
        }
        val tags = TagReader.Tags(
            title = title,
            artist = artist,
            album = album,
            year = year?.take(4)?.toIntOrNull(),
            trackNumber = track?.substringBefore('/')?.trim()?.toIntOrNull(),
        )
        return if (tags == TagReader.Tags()) null else tags
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
