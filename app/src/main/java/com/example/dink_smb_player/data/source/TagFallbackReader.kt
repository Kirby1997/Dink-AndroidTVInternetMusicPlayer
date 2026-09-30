package com.example.dink_smb_player.data.source

import android.content.Context
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Reads tags that live at the END of a file — ID3v1 (last 128 bytes) and APEv2 (footer at
 * EOF) — which Media3's Mp3Extractor and the platform MediaMetadataRetriever both ignore.
 *
 * A large slice of older / ExactAudioCopy-ripped libraries carry ONLY these trailing tags
 * (no ID3v2 at the file start), so [TagReader] falls back here whenever the primary read
 * finds no title. Reads are done over the same byte-budgeted SMB [Media3MediaDataSource] as
 * [DurationReader] — just a seek to the tail — so nothing is downloaded. Inside an
 * [SmbProbe.session] that is the handle the duration probe already opened.
 *
 * When both tags are present, APEv2 wins: its values are variable-length UTF-8, whereas
 * ID3v1 truncates every field to 30 bytes (so a long title is cut off).
 *
 * Blocks (network) — call from Dispatchers.IO. Returns [ReadResult.Absent] when neither tag
 * is present (or the file is gone / too small), [ReadResult.Error] when a transient failure
 * (NAS down, timeout) cut the read short — so the caller retries later instead of recording
 * "no tags" for a file it simply couldn't reach.
 */
object TagFallbackReader {

    // The tail read only ever touches ID3v1's 128 bytes plus the APE items it wants (text
    // items are a few hundred bytes; binary items such as embedded art are skipped, never
    // read). The budget backstops a pathological tag. A short deadline bounds the tail.
    private const val BUDGET_BYTES = 4L * 1024 * 1024
    private const val DEADLINE_MS = 8_000L

    /** Longest APE text value we read; anything longer isn't a title/artist/album. */
    private const val MAX_APE_TEXT_BYTES = 64 * 1024

    /** An item header: valueLen(4) + flags(4) + key (2..255 ASCII) + NUL. */
    private const val APE_ITEM_HEADER_MAX = 8 + 255 + 1

    private val CP1252: Charset = Charset.forName("windows-1252")

    /** windows-1252's characters for bytes 0x80–0x9F — how UTF-8 continuation bytes in that
     *  range read after a cp1252 mis-decode (they are U+0080–U+009F under Latin-1). */
    private val CP1252_HIGH: Set<Char> =
        String(ByteArray(32) { (0x80 + it).toByte() }, CP1252).toSet() - '�'

    private val APE_TEXT_KEYS = setOf("title", "artist", "album", "album artist", "albumartist", "year", "date", "track")

    /** Positioned read over the file: up to [len] bytes at [pos]; count, or <= 0 at EOF/failure. */
    internal fun interface TailSource {
        fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Int
    }

    fun read(context: Context, uri: String): ReadResult<TagReader.Tags> {
        val src = Media3MediaDataSource(
            context.applicationContext, uri, BUDGET_BYTES, SystemClock.elapsedRealtime() + DEADLINE_MS,
        )
        return try {
            val size = src.getSize()
            classify(parse(TailSource(src::readAt), size), src.failure)
        } catch (t: Throwable) {
            classify(null, src.failure ?: t)
        } finally {
            runCatching { src.close() }
        }
    }

    /** Both trailing tags of a file of [size] bytes read through [src], merged; null if none. */
    internal fun parse(src: TailSource, size: Long): TagReader.Tags? {
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
            albumArtist = ape?.albumArtist, // ID3v1 has no album-artist field
            year = ape?.year ?: id3v1?.year,
            trackNumber = ape?.trackNumber ?: id3v1?.trackNumber,
        )
        return merged.takeUnless { it == TagReader.Tags() }
    }

    /** Found when anything was read and nothing failed transiently; a transient [failure]
     *  makes it an Error (keeping any [tags] read as partial); otherwise Absent. */
    internal fun classify(tags: TagReader.Tags?, failure: Throwable?): ReadResult<TagReader.Tags> = when {
        ReadFailures.isTransient(failure) -> ReadResult.Error(failure, tags)
        tags != null -> ReadResult.Found(tags)
        else -> ReadResult.Absent
    }

    /** Read exactly [len] bytes starting at [pos] (fewer if EOF/budget cuts it short). */
    private fun readFully(src: TailSource, pos: Long, len: Int): ByteArray {
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

    /** Decode a fixed-width ID3v1 field. The field ends at its first NUL (bytes after it are
     *  leftover garbage from the tagger's buffer, not text), then trailing space padding is
     *  trimmed. ID3v1's official charset is Latin-1, but real-world taggers frequently write
     *  UTF-8 into it — so try strict UTF-8 first (only valid UTF-8 succeeds; a multi-byte char
     *  cut by the 30-byte limit is dropped) and otherwise decode as windows-1252, what Windows
     *  taggers actually wrote (Latin-1's printable range plus €, ’, … at 0x80–0x9F where
     *  Latin-1 has control codes). A field that STILL looks like mojibake after decoding came
     *  from a genuinely corrupt (double-encoded) tag — reject it so it can't clobber a clean
     *  path-derived value. */
    private fun decodeField(b: ByteArray, off: Int, len: Int): String? {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        val cutByNul = end < off + len
        while (end > off && b[end - 1] == 0x20.toByte()) end--
        if (end <= off) return null
        val n = end - off
        val decoded = strictUtf8(b, off, n)
            ?: (if (!cutByNul) utf8CutMidChar(b, off, n) else null)
            ?: String(b, off, n, CP1252)
        val s = decoded.trim().ifBlank { null } ?: return null
        return if (looksMojibake(s)) null else s
    }

    private fun strictUtf8(b: ByteArray, off: Int, n: Int): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b, off, n)).toString()
    } catch (e: Exception) {
        null
    }

    /** A full-width field whose last UTF-8 char was cut by the 30-byte limit: decode the text
     *  before that char — but only if it holds real multi-byte UTF-8 (else it's a Latin-1 field
     *  that merely ends in a high byte, e.g. "Café", which must not lose its é). */
    private fun utf8CutMidChar(b: ByteArray, off: Int, n: Int): String? {
        for (k in 1..3) {
            if (n - k <= 0) return null
            val lead = b[off + n - k].toInt() and 0xFF
            if (lead in 0x80..0xBF) continue // continuation byte: keep looking back for the lead
            if (lead < 0x80) return null // ASCII: nothing was cut
            val seqLen = when {
                lead >= 0xF0 -> 4
                lead >= 0xE0 -> 3
                else -> 2
            }
            if (seqLen <= k) return null // the sequence was complete → genuinely malformed
            return strictUtf8(b, off, n - k)?.takeIf { s -> s.any { it.code > 0x7F } }
        }
        return null
    }

    /** True when a string carries the tell-tale fingerprint of UTF-8 bytes decoded as Latin-1 /
     *  windows-1252: a U+00C2/C3/C5 (Â/Ã/Å) immediately followed by what a continuation byte
     *  (0x80–0xBF) decodes to, or the U+FFFD replacement char. Such text is unrecoverable
     *  garbage, not a real name. */
    internal fun looksMojibake(s: String): Boolean {
        for (i in s.indices) {
            val c = s[i].code
            if (c == 0xFFFD) return true // replacement char
            if ((c == 0xC2 || c == 0xC3 || c == 0xC5) && i + 1 < s.length) {
                val next = s[i + 1]
                if (next.code in 0x80..0xBF || next in CP1252_HIGH) return true
            }
        }
        return false
    }

    // ---- APEv2 (footer at [regionEnd], items block precedes it) --------------------------
    // Footer: "APETAGEX"(8) version(4 LE) tagSize(4 LE, items+footer) itemCount(4 LE) flags(4)
    // reserved(8). Each item: valueLen(4 LE) flags(4 LE) key(NUL-terminated ASCII) value.
    // Item flags bits 1-2 give the value type: 0 = UTF-8 text, 1 = binary, 2 = external link.
    // Items are STREAMED: each header is read on its own and only wanted text values are
    // fetched, so a multi-megabyte cover-art item is stepped over, never downloaded.
    private fun parseApe(src: TailSource, regionEnd: Long): TagReader.Tags? {
        val footer = readFully(src, regionEnd - 32, 32)
        if (footer.size != 32) return null
        if (String(footer, 0, 8, StandardCharsets.US_ASCII) != "APETAGEX") return null
        val tagSize = le32(footer, 8 + 4).toLong() and 0xFFFFFFFFL // items + footer
        val itemCount = le32(footer, 8 + 4 + 4)
        val itemsStart = regionEnd - tagSize
        val itemsEnd = regionEnd - 32
        if (tagSize <= 32 || itemsStart < 0 || itemCount <= 0) return null

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var year: String? = null
        var track: String? = null
        var p = itemsStart
        var n = 0
        while (n < itemCount && p + 8 < itemsEnd) {
            val hdr = readFully(src, p, minOf(APE_ITEM_HEADER_MAX.toLong(), itemsEnd - p).toInt())
            if (hdr.size < 9) break
            val valueLen = le32(hdr, 0).toLong() and 0xFFFFFFFFL
            val itemType = (le32(hdr, 4) ushr 1) and 0x3
            var k = 8
            while (k < hdr.size && hdr[k] != 0.toByte()) k++
            if (k >= hdr.size) break // no key terminator within a legal key length: corrupt
            val key = String(hdr, 8, k - 8, StandardCharsets.US_ASCII)
            val valueStart = p + k + 1
            if (valueStart + valueLen > itemsEnd) break
            val field = key.lowercase()
            if (itemType == 0 && valueLen <= MAX_APE_TEXT_BYTES && field in APE_TEXT_KEYS) {
                val raw = readFully(src, valueStart, valueLen.toInt())
                if (raw.size.toLong() != valueLen) break
                // Multi-value items separate values with NUL; the first is the one we want.
                val value = String(raw, StandardCharsets.UTF_8).substringBefore('\u0000').trim()
                    .ifBlank { null }?.takeUnless { looksMojibake(it) }
                when (field) {
                    "title" -> title = value
                    "artist" -> artist = value
                    "album" -> album = value
                    "album artist", "albumartist" -> albumArtist = albumArtist ?: value
                    "year", "date" -> year = year ?: value
                    "track" -> track = value
                }
            }
            p = valueStart + valueLen
            n++
        }
        val tags = TagReader.Tags(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
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
