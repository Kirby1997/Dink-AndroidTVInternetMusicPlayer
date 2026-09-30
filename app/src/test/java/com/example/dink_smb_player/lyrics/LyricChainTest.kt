package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LyricChainTest {

    private val song = Song("id", "One", "Metallica", null, null, 446, 0, "", "MP3")

    private fun synced(text: String) = OnlineLyrics(synced = listOf(LyricLine(5f, text)))
    private fun plain(text: String) = OnlineLyrics(plain = listOf(LyricLine(0f, text)))

    /** Provider answering [result] after [delayMs] of virtual time; records calls/cancels. */
    private class FakeProvider(
        override val id: String,
        private val delayMs: Long,
        private val result: OnlineLyrics?,
        override val syncedCapable: Boolean = true,
        private val throws: Boolean = false,
    ) : OnlineLyricProvider {
        override val label = id
        override val defaultEnabled = true
        var calls = 0
        var cancelled = false
        override suspend fun fetch(song: Song): OnlineLyrics {
            calls++
            try {
                if (delayMs == Long.MAX_VALUE) awaitCancellation()
                delay(delayMs)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
            if (throws) throw IOException("host unreachable")
            return result ?: OnlineLyrics()
        }
    }

    private class FakeDir(
        private val names: List<String>,
        private val files: Map<String, String> = emptyMap(),
        private val failList: Boolean = false,
        override val audioName: String = "01 - One.mp3",
    ) : SidecarDir {
        var listCalls = 0
        val reads = mutableListOf<String>()
        override suspend fun list(): List<String> {
            listCalls++
            if (failList) throw IOException("NAS down")
            return names
        }
        override suspend fun read(name: String): ByteArray? {
            reads += name
            return files[name]?.toByteArray()
        }
    }

    private class FakeSources(
        val dir: SidecarDir? = null,
        val embeddedResult: LyricResult = LyricResult.None,
        val providerList: List<OnlineLyricProvider> = emptyList(),
    ) : LyricSources {
        var embeddedCalls = 0
        override fun sidecarDir(song: Song) = dir
        override suspend fun embedded(song: Song): LyricResult { embeddedCalls++; return embeddedResult }
        override fun providers() = providerList
    }

    private suspend fun TestScope.resolve(sources: LyricSources) =
        LyricChain.resolveUncached(song, sources, clock = { testScheduler.currentTime })

    // --- parallel synced tier ---------------------------------------------------------

    @Test
    fun `providers run in parallel and top priority answering first wins at once`() = runTest {
        val a = FakeProvider("a", 100, synced("A"))
        val b = FakeProvider("b", 100, synced("B"))
        val c = FakeProvider("c", 5_000, synced("C"))
        val r = LyricChain.querySyncedTier(song, listOf(a, b, c), 300, 15_000)
        assertEquals("a", r.winnerId)
        assertEquals(100, currentTime) // parallel, and no grace wait when nothing ranks higher
        assertTrue(c.cancelled)
    }

    @Test
    fun `higher priority synced within the grace beats an earlier lower priority hit`() = runTest {
        val a = FakeProvider("a", 350, synced("A"))
        val b = FakeProvider("b", 100, synced("B"))
        val r = LyricChain.querySyncedTier(song, listOf(a, b), 300, 15_000)
        assertEquals("a", r.winnerId)
        assertEquals(350, currentTime)
    }

    @Test
    fun `lower priority hit is taken once the grace runs out`() = runTest {
        val a = FakeProvider("a", 2_000, synced("A"))
        val b = FakeProvider("b", 100, synced("B"))
        val r = LyricChain.querySyncedTier(song, listOf(a, b), 300, 15_000)
        assertEquals("b", r.winnerId)
        assertEquals(400, currentTime)
        assertTrue(a.cancelled)
    }

    @Test
    fun `lower priority hit is taken as soon as higher ones miss`() = runTest {
        val a = FakeProvider("a", 50, null)
        val b = FakeProvider("b", 100, synced("B"))
        val r = LyricChain.querySyncedTier(song, listOf(a, b), 300, 15_000)
        assertEquals("b", r.winnerId)
        assertEquals(100, currentTime)
        assertFalse(r.transient)
    }

    @Test
    fun `deadline stops hung providers and marks the miss transient`() = runTest {
        val a = FakeProvider("a", Long.MAX_VALUE, null)
        val b = FakeProvider("b", 10, plain("words"))
        val r = LyricChain.querySyncedTier(song, listOf(a, b), 300, 15_000)
        assertEquals(15_000, currentTime)
        assertEquals(null, r.winner)
        assertEquals("words", r.plain.single().text)
        assertTrue(r.transient)
        assertTrue(a.cancelled)
    }

    @Test
    fun `cancelling the resolve cancels in-flight providers`() = runTest {
        val a = FakeProvider("a", 10_000, synced("A"))
        val b = FakeProvider("b", 10_000, synced("B"))
        val job = launch { resolve(FakeSources(providerList = listOf(a, b))) }
        advanceTimeBy(100)
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(a.cancelled && b.cancelled)
    }

    // --- whole chain --------------------------------------------------------------------

    @Test
    fun `online synced wins and the embedded read is abandoned`() = runTest {
        val res = resolve(FakeSources(providerList = listOf(FakeProvider("a", 10, synced("A")))))
        assertTrue(res.result is LyricResult.Synced)
        assertTrue(res.cacheable)
    }

    @Test
    fun `instrumental verdict stops the chain before plain scrapers`() = runTest {
        val lrclib = FakeProvider("lrclib", 10, OnlineLyrics(instrumental = true))
        val netease = FakeProvider("netease", 20, plain("wrong words"))
        val scraper = FakeProvider("scraper", 10, plain("scraped"), syncedCapable = false)
        val res = resolve(FakeSources(providerList = listOf(lrclib, netease, scraper)))
        assertEquals(LyricResult.Instrumental, res.result)
        assertTrue(res.cacheable)
        assertEquals(0, scraper.calls)
    }

    @Test
    fun `local plain lyrics beat an instrumental verdict`() = runTest {
        val lrclib = FakeProvider("lrclib", 10, OnlineLyrics(instrumental = true))
        val res = resolve(
            FakeSources(
                embeddedResult = LyricResult.Plain(listOf(LyricLine(0f, "my words"))),
                providerList = listOf(lrclib),
            ),
        )
        assertEquals("my words", (res.result as LyricResult.Plain).lines.single().text)
    }

    @Test
    fun `plain-only scrapers are skipped once the synced tier found plain text`() = runTest {
        val a = FakeProvider("a", 10, plain("tier plain"))
        val scraper = FakeProvider("scraper", 10, plain("scraped"), syncedCapable = false)
        val res = resolve(FakeSources(providerList = listOf(a, scraper)))
        assertEquals("tier plain", (res.result as LyricResult.Plain).lines.single().text)
        assertEquals(0, scraper.calls)
    }

    @Test
    fun `plain scrapers run one at a time and stop at the first hit`() = runTest {
        val s1 = FakeProvider("s1", 10, null, syncedCapable = false)
        val s2 = FakeProvider("s2", 10, plain("scraped"), syncedCapable = false)
        val s3 = FakeProvider("s3", 10, plain("never"), syncedCapable = false)
        val res = resolve(FakeSources(providerList = listOf(s1, s2, s3)))
        assertEquals("scraped", (res.result as LyricResult.Plain).lines.single().text)
        assertEquals(0, s3.calls)
        assertEquals(20, currentTime)
    }

    @Test
    fun `provider failure makes a miss uncacheable`() = runTest {
        val a = FakeProvider("a", 10, null, throws = true)
        val res = resolve(FakeSources(providerList = listOf(a)))
        assertEquals(LyricResult.None, res.result)
        assertFalse(res.cacheable)
    }

    @Test
    fun `master off means no provider runs and a miss stays in memory only`() = runTest {
        val src = FakeSources(providerList = emptyList())
        val res = resolve(src)
        assertEquals(LyricResult.None, res.result)
        assertTrue(res.cacheable)
        assertFalse(res.persist)
        assertEquals(1, src.embeddedCalls)
    }

    // --- sidecars (SMB-style directory, listed once) ------------------------------------

    @Test
    fun `sidecar lrc found by one listing wins before any provider`() = runTest {
        val dir = FakeDir(
            names = listOf("01 - One.mp3", "02 - Alone.lrc", "01 - ONE.LRC", "cover.jpg"),
            files = mapOf("01 - ONE.LRC" to "[00:05.00]I can't remember anything"),
        )
        val provider = FakeProvider("a", 10, synced("online"))
        val res = resolve(FakeSources(dir = dir, providerList = listOf(provider)))
        assertEquals("I can't remember anything", (res.result as LyricResult.Synced).lines.single().text)
        assertEquals(1, dir.listCalls)
        assertEquals(listOf("01 - ONE.LRC"), dir.reads)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `sidecar txt is used when nothing synced exists`() = runTest {
        val dir = FakeDir(
            names = listOf("01 - One.mp3", "Metallica - One.txt"),
            files = mapOf("Metallica - One.txt" to "line one\nline two"),
        )
        val scraper = FakeProvider("scraper", 10, plain("scraped"), syncedCapable = false)
        val res = resolve(FakeSources(dir = dir, providerList = listOf(scraper)))
        assertEquals(listOf("line one", "line two"), (res.result as LyricResult.Plain).lines.map { it.text })
        assertEquals(0, scraper.calls)
    }

    @Test
    fun `unreachable share makes a miss uncacheable`() = runTest {
        val res = resolve(FakeSources(dir = FakeDir(emptyList(), failList = true)))
        assertEquals(LyricResult.None, res.result)
        assertFalse(res.cacheable)
    }
}
