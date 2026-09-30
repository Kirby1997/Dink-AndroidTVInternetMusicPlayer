package com.example.dink_smb_player.data.art

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiskArtCacheTest {

    private lateinit var dir: File
    private var now = 1_000_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    @Before fun setUp() { dir = Files.createTempDirectory("artcache").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun cache(max: Long = 10_000L) = DiskArtCache(dir, max, 7 * day) { now }

    @Test
    fun `none marker expires after ttl`() {
        val c = cache()
        assertFalse(c.isKnownAbsent("a"))
        c.markAbsent("a")
        assertTrue(c.isKnownAbsent("a"))
        now += 7 * day - 1
        assertTrue(c.isKnownAbsent("a"))
        now += 1
        assertFalse(c.isKnownAbsent("a"))
        assertFalse(File(dir, "a.none").exists())
    }

    @Test
    fun `write then read round-trips and clears the none marker`() {
        val c = cache()
        c.markAbsent("a")
        assertTrue(c.write("a") { it.write(byteArrayOf(1, 2, 3)) })
        assertArrayEquals(byteArrayOf(1, 2, 3), c.read("a"))
        assertFalse(c.isKnownAbsent("a"))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `evicts least recently used once over the cap`() {
        val c = cache(max = 3_000L)
        val kb = ByteArray(1_000)
        for (k in listOf("a", "b", "c")) { c.write(k) { it.write(kb) }; now += 1_000 }
        // Touch "a" → "b" becomes the oldest.
        assertNotNull(c.read("a")); now += 1_000
        c.write("d") { it.write(kb) } // 4 KB > 3 KB cap → trim to ≤ 2.7 KB
        assertNull(c.read("b"))
        assertNull(c.read("c"))
        assertNotNull(c.read("a"))
        assertNotNull(c.read("d"))
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 3_000L)
    }

    @Test
    fun `failed write leaves nothing behind`() {
        val c = cache()
        assertFalse(c.write("a") { throw java.io.IOException("disk full") })
        assertNull(c.read("a"))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `clear removes covers and markers`() {
        val c = cache()
        c.write("a") { it.write(byteArrayOf(1)) }
        c.markAbsent("b")
        c.clear()
        assertNull(c.read("a"))
        assertFalse(c.isKnownAbsent("b"))
    }
}
