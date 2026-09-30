package com.example.dink_smb_player.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SRC-14: ID3v1 fields end at the first NUL and decode as UTF-8 else windows-1252 (with the
 * mojibake reject kept); APE items are streamed, so a huge binary item is skipped, not read.
 */
class TagFallbackReaderTest {

    /** A byte-array "file" that counts the bytes actually read. */
    private class Src(val b: ByteArray) : TagFallbackReader.TailSource {
        var bytesRead = 0L
        override fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
            if (pos >= b.size) return -1
            val n = minOf(len.toLong(), b.size - pos).toInt()
            System.arraycopy(b, pos.toInt(), dst, off, n)
            bytesRead += n
            return n
        }
    }

    private fun parse(file: ByteArray) = TagFallbackReader.parse(Src(file), file.size.toLong())

    private val CP1252 = charset("windows-1252")

    private fun field(bytes: ByteArray, width: Int = 30): ByteArray = bytes.copyOf(width)

    private fun id3v1(
        title: ByteArray = ByteArray(0),
        artist: ByteArray = ByteArray(0),
        album: ByteArray = ByteArray(0),
        year: String = "",
        track: Int? = null,
    ): ByteArray {
        val b = ByteArray(128)
        "TAG".toByteArray().copyInto(b, 0)
        field(title).copyInto(b, 3)
        field(artist).copyInto(b, 33)
        field(album).copyInto(b, 63)
        field(year.toByteArray(), 4).copyInto(b, 93)
        if (track != null) { b[125] = 0; b[126] = track.toByte() }
        return b
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private fun apeItem(key: String, value: ByteArray, binary: Boolean = false): ByteArray =
        le32(value.size) + le32(if (binary) 1 shl 1 else 0) + key.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + value

    /** APEv2 tag (optional 32-byte header, items, footer). tagSize counts items + footer. */
    private fun ape(vararg items: ByteArray, withHeader: Boolean = true): ByteArray {
        val body = items.fold(ByteArray(0)) { acc, i -> acc + i }
        val flags = if (withHeader) 1 shl 31 else 0
        fun block() = "APETAGEX".toByteArray() + le32(2000) + le32(body.size + 32) + le32(items.size) + le32(flags) + ByteArray(8)
        return (if (withHeader) block() else ByteArray(0)) + body + block()
    }

    private val audio = ByteArray(4_000) { (it * 7).toByte() }

    // ---- ID3v1 ----------------------------------------------------------------------------

    @Test
    fun `ID3v1 fields, year and v1_1 track number`() {
        val t = parse(audio + id3v1("Song".toByteArray(), "Band".toByteArray(), "Record".toByteArray(), "1999", 7))!!
        assertEquals("Song", t.title)
        assertEquals("Band", t.artist)
        assertEquals("Record", t.album)
        assertEquals(1999, t.year)
        assertEquals(7, t.trackNumber)
    }

    @Test
    fun `a field ends at its first NUL - leftover bytes after it are ignored`() {
        val title = "Hello".toByteArray() + byteArrayOf(0) + "garbage!".toByteArray()
        assertEquals("Hello", parse(audio + id3v1(title))!!.title)
    }

    @Test
    fun `trailing space padding is trimmed`() {
        assertEquals("Padded", parse(audio + id3v1("Padded".padEnd(30).toByteArray()))!!.title)
    }

    @Test
    fun `non-UTF-8 high bytes decode as windows-1252`() {
        val t = parse(audio + id3v1("Don’t Stop €".toByteArray(CP1252), "Café".toByteArray(CP1252)))!!
        assertEquals("Don’t Stop €", t.title) // 0x92, 0x80 — control codes under Latin-1
        assertEquals("Café", t.artist)
    }

    @Test
    fun `valid UTF-8 in a field is decoded as UTF-8`() {
        assertEquals("Motörhead", parse(audio + id3v1("Motörhead".toByteArray(Charsets.UTF_8)))!!.title)
    }

    @Test
    fun `a UTF-8 char cut by the 30-byte limit is dropped, not mis-decoded`() {
        // 14 × "ö" (28 bytes) + "a" + the first byte of another "ö" = 30 bytes.
        val bytes = "ö".repeat(14).toByteArray() + "a".toByteArray() + byteArrayOf(0xC3.toByte())
        assertEquals(30, bytes.size)
        assertEquals("ö".repeat(14) + "a", parse(audio + id3v1(bytes))!!.title)
    }

    @Test
    fun `Latin-1 text ending in a lead-byte-looking char keeps it`() {
        // "Café" in cp1252 ends with 0xE9 (a UTF-8 3-byte lead), padded to the full 30 bytes.
        val bytes = ("x".repeat(26) + "Café").toByteArray(CP1252)
        assertEquals(30, bytes.size)
        assertEquals("x".repeat(26) + "Café", parse(audio + id3v1(bytes))!!.title)
    }

    @Test
    fun `double-encoded (mojibake) fields are rejected`() {
        // UTF-8 of "Ã¶" — what a tagger writes after mis-reading "ö" once.
        val t = parse(audio + id3v1("MÃ¶tley".toByteArray(Charsets.UTF_8), "Clean".toByteArray()))!!
        assertNull(t.title)
        assertEquals("Clean", t.artist)
    }

    @Test
    fun `no ID3v1 and no APE is null`() {
        assertNull(parse(audio))
        assertNull(parse(ByteArray(100)))
    }

    // ---- looksMojibake ----------------------------------------------------------------------

    @Test
    fun `looksMojibake flags UTF-8 read as Latin-1 or windows-1252`() {
        assertTrue(TagFallbackReader.looksMojibake("CafÃ©"))   // é as Latin-1
        assertTrue(TagFallbackReader.looksMojibake("Ã„"))      // Ä as cp1252 (0x84 → „)
        assertTrue(TagFallbackReader.looksMojibake("BeyoncÃ©"))
        assertTrue(TagFallbackReader.looksMojibake("bad � char"))
        assertFalse(TagFallbackReader.looksMojibake("Motörhead"))
        assertFalse(TagFallbackReader.looksMojibake("Ångström"))
        assertFalse(TagFallbackReader.looksMojibake("Sigur Rós"))
        assertFalse(TagFallbackReader.looksMojibake("Ã"))
    }

    // ---- APEv2 ------------------------------------------------------------------------------

    @Test
    fun `APE text items are read and win over ID3v1`() {
        val tag = ape(
            apeItem("Title", "A Much Longer Title Than ID3v1 Could Ever Hold".toByteArray()),
            apeItem("Artist", "Björk".toByteArray()),
            apeItem("Year", "2004-05-01".toByteArray()),
            apeItem("Track", "3/12".toByteArray()),
        )
        val t = parse(audio + tag + id3v1("Short".toByteArray(), "Other".toByteArray(), "V1 Album".toByteArray()))!!
        assertEquals("A Much Longer Title Than ID3v1 Could Ever Hold", t.title)
        assertEquals("Björk", t.artist)
        assertEquals("V1 Album", t.album) // APE has none → ID3v1 fills it
        assertEquals(2004, t.year)
        assertEquals(3, t.trackNumber)
    }

    @Test
    fun `APE without ID3v1 and without a header`() {
        val t = parse(audio + ape(apeItem("ALBUM", "Upper Key".toByteArray()), withHeader = false))!!
        assertEquals("Upper Key", t.album)
    }

    @Test
    fun `a multi-megabyte binary APE item is skipped, not read`() {
        val art = ByteArray(3 * 1024 * 1024) { 0x55 }
        val tag = ape(
            apeItem("Title", "Before Art".toByteArray()),
            apeItem("Cover Art (Front)", art, binary = true),
            apeItem("Album", "After Art".toByteArray()),
        )
        val src = Src(audio + tag)
        val t = TagFallbackReader.parse(src, src.b.size.toLong())!!
        assertEquals("Before Art", t.title)
        assertEquals("After Art", t.album)
        assertTrue("read ${src.bytesRead} bytes", src.bytesRead < 4 * 1024)
    }

    @Test
    fun `multi-value APE items keep the first value`() {
        val t = parse(audio + ape(apeItem("Artist", "First\u0000Second".toByteArray())))!!
        assertEquals("First", t.artist)
    }

    @Test
    fun `a corrupt APE item length stops parsing without throwing`() {
        val bad = le32(Int.MAX_VALUE) + le32(0) + "Title".toByteArray() + byteArrayOf(0) + "x".toByteArray()
        val t = parse(audio + ape(apeItem("Artist", "Kept".toByteArray()), bad) + id3v1("V1".toByteArray()))!!
        assertEquals("Kept", t.artist)
        assertEquals("V1", t.title)
    }

    @Test
    fun `APE album artist is read under either key spelling`() {
        val t = parse(audio + ape(apeItem("Album Artist", "Various Artists".toByteArray()), apeItem("Title", "T".toByteArray())))!!
        assertEquals("Various Artists", t.albumArtist)
        assertEquals("T", t.title)
        assertEquals("Queen", parse(audio + ape(apeItem("ALBUMARTIST", "Queen".toByteArray())))!!.albumArtist)
        // ID3v1 has no album-artist field.
        assertEquals(null, parse(audio + id3v1("V1".toByteArray()))!!.albumArtist)
    }
}
