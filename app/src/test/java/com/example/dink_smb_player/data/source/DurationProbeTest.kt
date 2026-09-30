package com.example.dink_smb_player.data.source

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.IOException

/**
 * The duration-probe `truncated` rule: a platform-retriever duration is only trusted when the
 * stream reached its REAL end — a probe cut short by the byte budget, a read error or a failed
 * open reports a bogus short duration, which must become null (the fake 0:11 durations bug).
 * Also PLAY-15: the SMB path opens its handle once and answers getSize from it.
 */
class DurationProbeTest {

    private class FakeFile(val bytes: ByteArray, val failAt: Long = Long.MAX_VALUE) : ProbeFile {
        var released = false
        override val length = bytes.size.toLong()
        override fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
            if (pos + len > failAt) throw IOException("connection reset")
            if (pos >= bytes.size) return -1
            val n = minOf(len.toLong(), bytes.size - pos).toInt()
            System.arraycopy(bytes, pos.toInt(), dst, off, n)
            return n
        }
        override fun release() { released = true }
    }

    private val ctx = mock<Context>()
    private val data = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }

    private fun source(file: FakeFile?, budget: Long = Long.MAX_VALUE, opens: IntArray = IntArray(1)) =
        Media3MediaDataSource(ctx, "smb://nas/music/a.m4a?sid=x", budget, 0L) {
            opens[0]++
            file ?: throw IOException("host unreachable")
        }

    /** Drain the source the way the retriever does: sequential 2 KB reads until -1. */
    private fun drain(src: Media3MediaDataSource): Long {
        val buf = ByteArray(2048)
        var pos = 0L
        while (true) {
            val n = src.readAt(pos, buf, 0, buf.size)
            if (n <= 0) return pos
            for (i in 0 until n) assertEquals(data[(pos + i).toInt()], buf[i])
            pos += n
        }
    }

    // ---- DurationReader.resolve ---------------------------------------------------------

    @Test
    fun `a complete probe keeps the retriever's duration`() {
        assertEquals(215_000L, DurationReader.resolve("215000", truncated = false, failure = null).durationMs)
    }

    @Test
    fun `a truncated probe discards the duration`() {
        val cause = IOException("reset")
        val p = DurationReader.resolve("11000", truncated = true, failure = cause)
        assertNull(p.durationMs)
        assertSame(cause, p.failure)
    }

    @Test
    fun `missing, zero or garbage durations are null`() {
        assertNull(DurationReader.resolve(null, false, null).durationMs)
        assertNull(DurationReader.resolve("0", false, null).durationMs)
        assertNull(DurationReader.resolve("-5", false, null).durationMs)
        assertNull(DurationReader.resolve("abc", false, null).durationMs)
    }

    // ---- Media3MediaDataSource.truncated --------------------------------------------------

    @Test
    fun `reading to the real end of file is not truncated`() {
        val file = FakeFile(data)
        val src = source(file)
        assertEquals(data.size.toLong(), drain(src))
        assertFalse(src.truncated)
        assertNull(src.failure)
        src.close()
        assertTrue(file.released)
    }

    @Test
    fun `hitting the byte budget is truncated`() {
        val src = source(FakeFile(data), budget = 600_000)
        assertTrue(drain(src) < data.size)
        assertTrue(src.truncated)
        assertNull(src.failure) // budget is not a transient failure
    }

    @Test
    fun `a read error mid-file is truncated with the failure recorded`() {
        val src = source(FakeFile(data, failAt = 900_000))
        assertTrue(drain(src) < data.size)
        assertTrue(src.truncated)
        assertTrue(src.failure is IOException)
    }

    @Test
    fun `a failed open is truncated and size unknown`() {
        val src = source(null)
        assertEquals(-1L, src.size)
        assertEquals(-1, src.readAt(0, ByteArray(10), 0, 10))
        assertTrue(src.truncated)
        assertTrue(src.failure is IOException)
    }

    @Test
    fun `getSize and every read share one open`() {
        val opens = IntArray(1)
        val src = source(FakeFile(data), opens = opens)
        assertEquals(data.size.toLong(), src.size)
        drain(src)
        src.readAt(1_500_000, ByteArray(100), 0, 100) // a seek back
        assertEquals(1, opens[0])
    }
}
