package com.example.dink_smb_player.data.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * LIB-5 / SRC-9: per-source exclusivity, and a source delete that cancels + waits out
 * every in-flight job for it BEFORE its rows are removed (so no late upsert lands after).
 */
class SourceLocksTest {

    private fun newId() = "smb-" + UUID.randomUUID().toString().take(8)

    @Test
    fun `jobs for one source never overlap`() = runBlocking(Dispatchers.Default) {
        val id = newId()
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val jobs = List(8) {
            launch {
                SourceLocks.runExclusive(id) {
                    maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                    delay(5)
                    inside.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(1, maxInside.get())
    }

    @Test
    fun `remove cancels and joins the in-flight job before deleting`() = runBlocking(Dispatchers.Default) {
        val id = newId()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val started = CompletableDeferred<Unit>()
        val importer = async {
            SourceLocks.runExclusive(id) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    events += "import-stopped"
                }
            }
        }
        started.await()
        SourceLocks.remove(id) { events += "rows-removed" }

        assertEquals(listOf("import-stopped", "rows-removed"), events)
        // The cancelled job reports "nothing done" rather than throwing at its caller.
        assertNull(withTimeout(1_000) { importer.await() })
        assertEquals(0, SourceLocks.activeJobCount(id))
    }

    @Test
    fun `queued job for a removed source never runs`() = runBlocking(Dispatchers.Default) {
        val id = newId()
        val ran = AtomicInteger(0)
        SourceLocks.remove(id) { }
        assertTrue(SourceLocks.isRemoved(id))
        assertNull(SourceLocks.runExclusive(id) { ran.incrementAndGet() })
        assertEquals(0, ran.get())
    }

    @Test
    fun `failed remove lifts the tombstone so the delete can be retried`() = runBlocking {
        val id = newId()
        runCatching { SourceLocks.remove(id) { error("disk full") } }
        assertFalse(SourceLocks.isRemoved(id))
        assertEquals(1, SourceLocks.runExclusive(id) { 1 })
    }

    @Test
    fun `cancelOverlapping stops only jobs touching that folder`() = runBlocking(Dispatchers.Default) {
        val id = newId()
        // Different folders of one source still serialise on the lock, so start the
        // unrelated one first and let the target queue behind it.
        val rockStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rock = async {
            SourceLocks.runExclusive(id, listOf("Rock")) { rockStarted.complete(Unit); release.await(); "rock" }
        }
        rockStarted.await()
        val jazz = async(start = CoroutineStart.UNDISPATCHED) {
            SourceLocks.runExclusive(id, listOf("Jazz\\Live")) { "jazz" }
        }
        SourceLocks.cancelOverlapping(id, listOf("Jazz"))
        release.complete(Unit)

        assertEquals("rock", rock.await())
        assertNull(jazz.await())
    }

    @Test
    fun `a failure inside the block is rethrown`() = runBlocking {
        val r = runCatching { SourceLocks.runExclusive(newId()) { error("boom") } }
        assertEquals("boom", r.exceptionOrNull()?.message)
    }

    @Test
    fun `path overlap`() {
        assertTrue(SourceLocks.pathsOverlap("", "Rock"))
        assertTrue(SourceLocks.pathsOverlap("Rock", "Rock"))
        assertTrue(SourceLocks.pathsOverlap("Rock", "Rock\\80s"))
        assertTrue(SourceLocks.pathsOverlap("Rock\\80s", "Rock"))
        assertFalse(SourceLocks.pathsOverlap("Rock", "Rockabilly"))
        assertFalse(SourceLocks.pathsOverlap("Rock\\80s", "Rock\\90s"))
    }
}
