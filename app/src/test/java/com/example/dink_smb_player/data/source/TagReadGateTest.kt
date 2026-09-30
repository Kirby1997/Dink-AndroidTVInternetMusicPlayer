package com.example.dink_smb_player.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Review #7: every walk and the retag share ONE process-wide tag-read gate, sized under
 * Media3's retriever cap, so concurrent walks + a retag never queue inside Media3 (where the
 * wait counted against the read timeout).
 */
class TagReadGateTest {

    @Test
    fun `gate sits under the retriever cap`() {
        assertTrue(TagReadGate.PERMITS < TagReader.MAX_PARALLEL_RETRIEVALS)
        assertTrue(TagReadGate.HEAVY_PERMITS <= TagReadGate.PERMITS)
    }

    @Test
    fun `concurrent callers never exceed the total or the heavy cap`() = runBlocking(Dispatchers.Default) {
        val inFlight = AtomicInteger(0)
        val heavyInFlight = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        val maxHeavy = AtomicInteger(0)
        // Three "walks" and a "retag" at once, mixed containers.
        val jobs = (0 until 4).flatMap { caller ->
            (0 until 40).map { i ->
                val path = if (i % 3 == 0) "/smb/$caller/t$i.m4a" else "/smb/$caller/t$i.mp3"
                launch {
                    TagReadGate.withPermit(path) {
                        val heavy = TagReadGate.isTailLoaded(path)
                        maxSeen.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                        if (heavy) maxHeavy.accumulateAndGet(heavyInFlight.incrementAndGet(), ::maxOf)
                        delay(5)
                        if (heavy) heavyInFlight.decrementAndGet()
                        inFlight.decrementAndGet()
                    }
                }
            }
        }
        jobs.forEach { it.join() }
        assertTrue("max ${maxSeen.get()}", maxSeen.get() <= TagReadGate.PERMITS)
        assertTrue("max heavy ${maxHeavy.get()}", maxHeavy.get() <= TagReadGate.HEAVY_PERMITS)
        assertTrue("gate actually saturated", maxSeen.get() > TagReadGate.HEAVY_PERMITS)
        assertEquals(TagReadGate.PERMITS, TagReadGate.available)
    }

    @Test
    fun `tail-loaded detection`() {
        assertTrue(TagReadGate.isTailLoaded("/a/b.M4A"))
        assertTrue(TagReadGate.isTailLoaded("x.mp4"))
        assertTrue(!TagReadGate.isTailLoaded("x.mp3"))
        assertTrue(!TagReadGate.isTailLoaded("Music\\Album\\x.flac"))
    }
}
