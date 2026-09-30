package com.example.dink_smb_player.data.source.smb

import androidx.media3.common.C
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/**
 * PLAY-11: the read-ahead behind [SmbDataSource.read] (and the probe's random-access reads)
 * must return exactly the file's bytes across seeks, short server reads, bounded ranges,
 * unbounded (C.LENGTH_UNSET) ranges and end-of-file — while collapsing small reads into
 * few network fetches.
 */
class ReadAheadBufferTest {

    /** Fake SMB file: positioned reads capped at [maxRead] (smbj's negotiated max READ). */
    private class FakeFile(val bytes: ByteArray, val maxRead: Int = Int.MAX_VALUE) : ReadAheadBuffer.Fetch {
        var fetches = 0
        var fetchedBytes = 0L
        var failNext = false
        override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
            if (failNext) { failNext = false; throw IOException("connection reset") }
            fetches++
            if (pos >= bytes.size) return -1
            val n = minOf(len, maxRead, (bytes.size - pos).toInt())
            System.arraycopy(bytes, pos.toInt(), dst, off, n)
            fetchedBytes += n
            return n
        }
    }

    /** Mirrors SmbDataSource's open/read bookkeeping around the buffer. */
    private class Stream(val rab: ReadAheadBuffer, val file: FakeFile) {
        var readOffset = 0L
        var bytesRemaining = 0L

        fun open(position: Long, length: Long) {
            val total = file.bytes.size.toLong()
            bytesRemaining = if (length == C.LENGTH_UNSET.toLong()) total - position else length
            readOffset = position
            rab.clear()
        }

        fun read(dst: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
            val want = minOf(len.toLong(), bytesRemaining).toInt()
            val n = rab.read(readOffset, dst, off, want, bytesRemaining, file)
            if (n <= 0) return C.RESULT_END_OF_INPUT
            readOffset += n
            bytesRemaining -= n
            return n
        }
    }

    private val rnd = Random(42)
    private val data = ByteArray(3 * 1024 * 1024 + 17).also { rnd.nextBytes(it) }

    @Test
    fun `random seeks with small reads return exactly the file bytes`() {
        for (maxRead in listOf(Int.MAX_VALUE, 65_536, 37_001)) {
            val file = FakeFile(data, maxRead)
            val s = Stream(ReadAheadBuffer(256 * 1024), file)
            repeat(200) {
                val pos = rnd.nextLong(0, data.size.toLong() + 1)
                val bounded = rnd.nextBoolean()
                val length = if (bounded) rnd.nextLong(0, data.size - pos + 1) else C.LENGTH_UNSET.toLong()
                s.open(pos, length)
                val expectedEnd = if (bounded) pos + length else data.size.toLong()
                // Read a random stretch (sometimes to the end of the range) in small chunks.
                val stopAt = if (rnd.nextInt(4) == 0) expectedEnd else minOf(expectedEnd, pos + rnd.nextLong(0, 600_000))
                var at = pos
                val chunk = ByteArray(8192)
                while (at < stopAt) {
                    val size = rnd.nextInt(1, 3000)
                    val off = rnd.nextInt(0, chunk.size - size)
                    val n = s.read(chunk, off, size)
                    assertTrue("unexpected EOF at $at < $stopAt", n > 0)
                    assertArrayEquals(data.copyOfRange(at.toInt(), (at + n).toInt()), chunk.copyOfRange(off, off + n))
                    at += n
                }
                if (stopAt == expectedEnd) assertEquals(C.RESULT_END_OF_INPUT, s.read(chunk, 0, 10))
            }
        }
    }

    @Test
    fun `sequential 1 KB reads cost one fetch per window`() {
        val file = FakeFile(data)
        val s = Stream(ReadAheadBuffer(256 * 1024), file)
        s.open(0, C.LENGTH_UNSET.toLong())
        val buf = ByteArray(1024)
        var total = 0L
        while (total < 1024 * 1024) total += s.read(buf, 0, buf.size)
        assertEquals(4, file.fetches) // 1 MB / 256 KB — not 1024 round-trips
    }

    @Test
    fun `a bounded range never fetches past its end`() {
        val file = FakeFile(data)
        val s = Stream(ReadAheadBuffer(256 * 1024), file)
        s.open(1000, 5000)
        val buf = ByteArray(100)
        var got = 0
        while (true) {
            val n = s.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            assertArrayEquals(data.copyOfRange(1000 + got, 1000 + got + n), buf.copyOf(n))
            got += n
        }
        assertEquals(5000, got)
        assertEquals(5000L, file.fetchedBytes)
    }

    @Test
    fun `a file shorter than the opened range ends with END_OF_INPUT`() {
        val file = FakeFile(data.copyOf(10_000))
        val s = Stream(ReadAheadBuffer(4096), file)
        s.open(0, 20_000) // claimed length larger than the real file
        val buf = ByteArray(700)
        var got = 0
        while (true) {
            val n = s.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            got += n
        }
        assertEquals(10_000, got)
    }

    @Test
    fun `random-access reads (probe style) match the file`() {
        val file = FakeFile(data, 65_536)
        val rab = ReadAheadBuffer(512 * 1024)
        val buf = ByteArray(4096)
        repeat(2000) {
            val pos = rnd.nextLong(0, data.size.toLong())
            val size = rnd.nextInt(1, buf.size)
            val n = rab.read(pos, buf, 0, size, Long.MAX_VALUE, file)
            assertTrue(n > 0)
            assertArrayEquals(data.copyOfRange(pos.toInt(), (pos + n).toInt()), buf.copyOf(n))
        }
        assertEquals(-1, rab.read(data.size.toLong(), buf, 0, 10, Long.MAX_VALUE, file))
    }

    @Test
    fun `maxFetch caps the refill`() {
        val file = FakeFile(data)
        val rab = ReadAheadBuffer(512 * 1024)
        val buf = ByteArray(10)
        assertEquals(10, rab.read(0, buf, 0, 10, 100, file))
        assertEquals(100L, file.fetchedBytes)
        assertEquals(-1, rab.read(500, buf, 0, 10, 0, file)) // nothing left to fetch
    }

    @Test
    fun `a read at least one window large bypasses the buffer`() {
        var dstSeen: ByteArray? = null
        val fetch = ReadAheadBuffer.Fetch { pos, dst, off, len ->
            dstSeen = dst
            System.arraycopy(data, pos.toInt(), dst, off, len)
            len
        }
        val rab = ReadAheadBuffer(1024)
        val big = ByteArray(4096)
        assertEquals(1024, rab.read(0, big, 0, 4096, Long.MAX_VALUE, fetch))
        assertSame(big, dstSeen)
    }

    @Test
    fun `a failed fetch propagates and leaves no stale window`() {
        val file = FakeFile(data)
        val rab = ReadAheadBuffer(1024)
        val buf = ByteArray(10)
        rab.read(0, buf, 0, 10, Long.MAX_VALUE, file)
        file.failNext = true
        try {
            rab.read(5000, buf, 0, 10, Long.MAX_VALUE, file)
            throw AssertionError("expected IOException")
        } catch (e: IOException) { /* expected */ }
        // Next read at a new offset must fetch fresh, correct bytes.
        assertEquals(10, rab.read(5000, buf, 0, 10, Long.MAX_VALUE, file))
        assertArrayEquals(data.copyOfRange(5000, 5010), buf)
    }
}
