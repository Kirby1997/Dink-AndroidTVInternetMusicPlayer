package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LyricCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val synced = LyricResult.Synced(listOf(LyricLine(1f, "a"), LyricLine(2f, "b")))

    private fun cache(dir: java.io.File? = null, mem: Int = 64, maxBytes: Long = LyricCache.DEFAULT_MAX_DISK_BYTES) =
        LyricCache(dir, maxDiskBytes = maxBytes, memEntries = mem, clock = { now })

    @After
    fun reset() {
        LyricSettings.onUserChange = null
        LyricSettings.hydrate(LyricConfig())
    }

    @Test
    fun `memory LRU evicts the least recently used entry`() {
        val c = cache(mem = 2)
        c.put("a", "fp", synced)
        c.put("b", "fp", synced)
        c.get("a", "fp") // touch a
        c.put("c", "fp", synced)
        assertEquals(synced, c.get("a", "fp"))
        assertNull(c.get("b", "fp"))
        assertEquals(synced, c.get("c", "fp"))
    }

    @Test
    fun `negative result expires after the TTL but positives do not`() {
        val c = cache()
        c.put("none", "fp", LyricResult.None)
        c.put("pos", "fp", synced)
        now += LyricCache.NEGATIVE_TTL_MS - 1
        assertEquals(LyricResult.None, c.get("none", "fp"))
        now += 2
        assertNull(c.get("none", "fp"))
        assertEquals(synced, c.get("pos", "fp"))
    }

    @Test
    fun `entry resolved under another provider config is a miss`() {
        val c = cache()
        c.put("k", "off", LyricResult.None)
        assertNull(c.get("k", "on:lrclib"))
    }

    @Test
    fun `disk entries survive a new instance and clear removes them`() {
        val dir = tmp.newFolder("lyrics")
        cache(dir).put("k", "fp", synced)
        cache(dir).put("i", "fp", LyricResult.Instrumental)
        val fresh = cache(dir)
        assertEquals(synced, fresh.get("k", "fp"))
        assertEquals(LyricResult.Instrumental, fresh.get("i", "fp"))
        fresh.clear()
        assertNull(cache(dir).get("k", "fp"))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `memory-only entries never reach disk`() {
        val dir = tmp.newFolder("lyrics")
        val c = cache(dir)
        c.put("k", "fp", LyricResult.None, persist = false)
        assertEquals(LyricResult.None, c.get("k", "fp"))
        assertNull(cache(dir).get("k", "fp"))
    }

    @Test
    fun `disk cache is trimmed oldest-first past its byte budget`() {
        val dir = tmp.newFolder("lyrics")
        val big = LyricResult.Plain(List(200) { LyricLine(0f, "line number $it of a long lyric") })
        val c = cache(dir, mem = 1, maxBytes = 20_000)
        for (i in 0 until 10) { now += 1000; c.put("k$i", "fp", big) }
        val total = dir.listFiles()!!.sumOf { it.length() }
        assertTrue("total=$total", total <= 20_000)
        assertNull(cache(dir).get("k0", "fp"))
        assertEquals(big, cache(dir).get("k9", "fp"))
    }

    @Test
    fun `key changes with title and duration`() {
        val s = Song("id", "Title", "Artist", null, null, 0, 0, "", "MP3")
        val k = LyricCache.keyFor(s)
        assertTrue(k != LyricCache.keyFor(s.copy(title = "Real Title")))
        assertTrue(k != LyricCache.keyFor(s.copy(durationSec = 200)))
        assertEquals(k, LyricCache.keyFor(s.copy(title = " title ")))
    }

    @Test
    fun `user toggles fire invalidation only when the active set changes`() {
        var fired = 0
        LyricSettings.onUserChange = { fired++ }
        LyricSettings.set("lrclib", false) // master off: nothing consulted either way
        assertEquals(0, fired)
        LyricSettings.setOnline(true)
        assertEquals(1, fired)
        LyricSettings.set("netease", false)
        assertEquals(2, fired)
        LyricSettings.set("netease", false) // no change
        assertEquals(2, fired)
        LyricSettings.hydrate(LyricConfig()) // boot hydrate never fires
        assertEquals(2, fired)
    }
}
