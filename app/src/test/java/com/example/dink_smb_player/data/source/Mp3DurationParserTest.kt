package com.example.dink_smb_player.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * SRC-12: Mp3DurationParser over synthetic MPEG-1 Layer III streams (44.1 kHz, stereo):
 * Xing/Info and VBRI frame counts, the CBR estimate, stacked ID3v2 tags, false-sync
 * rejection, the mid-file bitrate check and trailing-tag exclusion.
 */
class Mp3DurationParserTest {

    private class Reader(private val b: ByteArray) : Mp3DurationParser.RandomReader {
        override fun size() = b.size.toLong()
        override fun readFully(pos: Long, dst: ByteArray, len: Int): Int {
            if (pos >= b.size) return 0
            val n = minOf(len.toLong(), b.size - pos).toInt()
            System.arraycopy(b, pos.toInt(), dst, 0, n)
            return n
        }
        override fun close() {}
    }

    private fun parse(b: ByteArray) = Mp3DurationParser.parse(Reader(b))

    // Bitrate index for MPEG-1 Layer III.
    private val brIndex = mapOf(32 to 1, 64 to 5, 128 to 9, 192 to 11, 320 to 14)

    private fun frameLen(kbps: Int) = 144 * kbps * 1000 / 44100

    /** One frame: header FF FB (MPEG-1, Layer III, no CRC), bitrate, 44.1 kHz, stereo; zero body. */
    private fun frame(kbps: Int): ByteArray {
        val f = ByteArray(frameLen(kbps))
        f[0] = 0xFF.toByte()
        f[1] = 0xFB.toByte()
        f[2] = (brIndex.getValue(kbps) shl 4).toByte()
        f[3] = 0x00
        return f
    }

    private fun frames(n: Int, kbps: Int): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(n) { out.write(frame(kbps)) }
        return out.toByteArray()
    }

    /** First frame carrying a VBR header ("Xing"/"Info"/"VBRI") at side-info offset 36. */
    private fun vbrHeaderFrame(tag: String, frameCount: Int): ByteArray {
        val f = frame(128)
        tag.toByteArray(Charsets.US_ASCII).copyInto(f, 36)
        if (tag == "VBRI") {
            be32(frameCount).copyInto(f, 36 + 14)
        } else {
            be32(1).copyInto(f, 36 + 4) // flags: frame count present
            be32(frameCount).copyInto(f, 36 + 8)
        }
        return f
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private fun id3v2(bodySize: Int): ByteArray {
        val h = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            (bodySize shr 21 and 0x7F).toByte(), (bodySize shr 14 and 0x7F).toByte(),
            (bodySize shr 7 and 0x7F).toByte(), (bodySize and 0x7F).toByte(),
        )
        return h + ByteArray(bodySize)
    }

    private fun msFor(frames: Int) = frames.toLong() * 1152 * 1000 / 44100

    private fun assertClose(expected: Long, actual: Long?, tolerancePct: Double = 1.0) {
        assertNotNull("expected ~$expected ms, got null", actual)
        val diff = Math.abs(expected - actual!!).toDouble() / expected * 100
        assert(diff <= tolerancePct) { "expected ~$expected ms, got $actual ms ($diff%)" }
    }

    @Test
    fun `Xing header gives the exact frame-count duration`() {
        val file = vbrHeaderFrame("Xing", 10_000) + frames(50, 192)
        assertEquals(msFor(10_000), parse(file))
    }

    @Test
    fun `Info header (LAME CBR) is read the same way`() {
        val file = vbrHeaderFrame("Info", 7_000) + frames(50, 128)
        assertEquals(msFor(7_000), parse(file))
    }

    @Test
    fun `VBRI header gives the exact frame-count duration`() {
        val file = vbrHeaderFrame("VBRI", 4_321) + frames(50, 128)
        assertEquals(msFor(4_321), parse(file))
    }

    @Test
    fun `CBR without a VBR header divides audio bytes by the bitrate`() {
        val n = 2_000
        assertClose(msFor(n), parse(frames(n, 128)))
    }

    @Test
    fun `a single ID3v2 tag is skipped without reading its body`() {
        val file = id3v2(300_000) + vbrHeaderFrame("Xing", 5_000) + frames(10, 128)
        assertEquals(msFor(5_000), parse(file))
    }

    @Test
    fun `stacked ID3v2 tags are all skipped`() {
        val file = id3v2(1_000) + id3v2(70_000) + id3v2(20) + vbrHeaderFrame("Xing", 9_999) + frames(10, 128)
        assertEquals(msFor(9_999), parse(file))
    }

    @Test
    fun `a lone false sync in junk is rejected in favour of the real stream`() {
        // Junk carrying a plausible 320 kbps header whose "next frame" isn't a header, then
        // the real 128 kbps stream 100 bytes later. Trusting the junk sync would size the
        // audio at 320 kbps — less than half the real duration.
        val junk = ByteArray(100)
        frame(320).copyOfRange(0, 4).copyInto(junk, 10)
        val n = 3_000
        val file = junk + frames(n, 128)
        assertClose(msFor(n), parse(file))
    }

    @Test
    fun `a header with no valid successor anywhere yields null`() {
        val file = ByteArray(8_000)
        frame(128).copyOfRange(0, 4).copyInto(file, 0)
        assertNull(parse(file))
    }

    @Test
    fun `headerless VBR (first frame bitrate differs from mid-file) yields null`() {
        val file = frames(100, 128) + frames(100, 320)
        assertNull(parse(file))
    }

    @Test
    fun `trailing ID3v1 and APE tags are not counted as audio`() {
        val n = 2_000
        val audio = frames(n, 128)
        // APE tag: header-less, one 1 MB binary item + footer — at 128 kbps that's a minute of
        // phantom audio if counted.
        val itemValue = ByteArray(1_000_000)
        val item = le32(itemValue.size) + le32(1 shl 1) + "Cover Art (Front)".toByteArray() + byteArrayOf(0) + itemValue
        val footer = "APETAGEX".toByteArray() + le32(2000) + le32(item.size + 32) + le32(1) + le32(0) + ByteArray(8)
        val id3v1 = ByteArray(128).also { "TAG".toByteArray().copyInto(it) }
        assertClose(msFor(n), parse(audio + item + footer + id3v1))
    }

    @Test
    fun `ID3 tag running past the end of file yields null`() {
        assertNull(parse(id3v2(10).copyOf(10).also { it[6] = 0x7F }))
    }
}
