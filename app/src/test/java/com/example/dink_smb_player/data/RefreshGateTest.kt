package com.example.dink_smb_player.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LIB-17: one local MediaStore refresh per launch however many callers ask at once. */
class RefreshGateTest {

    private var clock = 0L
    private val gate = RefreshGate(freshMs = 60_000L) { clock }

    @Test
    fun `concurrent launch callers run one scan`() = runBlocking {
        var scans = 0
        val release = CompletableDeferred<Unit>()
        // Boot loadOnce, monitor catch-up and the Local screen's first load, all non-forced.
        val calls = (1..3).map { async { gate.run(force = false) { scans++; release.await(); true } } }
        yield()
        release.complete(Unit)
        val ran = calls.awaitAll()
        assertEquals(1, scans)
        assertEquals(listOf(true, false, false), ran)
    }

    @Test
    fun `forced requests queued behind an in-flight scan share one follow-up`() = runBlocking {
        var scans = 0
        val release = CompletableDeferred<Unit>()
        val first = async { gate.run(force = false) { scans++; release.await(); true } }
        yield() // first scan is now in flight
        // Several volume-mount scans arrive while it runs: they may have missed it, so ONE more.
        val forced = (1..4).map { async { gate.run(force = true) { scans++; true } } }
        yield()
        release.complete(Unit)
        first.await()
        forced.awaitAll()
        assertEquals(2, scans)
    }

    @Test
    fun `a forced request issued before a scan starts is covered by it`() = runBlocking {
        var scans = 0
        val release = CompletableDeferred<Unit>()
        val a = async { gate.run(force = true) { scans++; release.await(); true } }
        yield()
        // b asks while a runs → gets a follow-up; c asks before that follow-up starts → covered.
        val b = async { gate.run(force = true) { scans++; true } }
        val c = async { gate.run(force = true) { scans++; true } }
        yield()
        release.complete(Unit)
        listOf(a, b, c).awaitAll()
        assertEquals(2, scans)
    }

    @Test
    fun `non-forced refresh runs again once the window passes, forced always runs`() = runBlocking {
        var scans = 0
        assertTrue(gate.run(false) { scans++; true })
        clock += 30_000
        assertFalse(gate.run(false) { scans++; true })
        assertTrue(gate.run(true) { scans++; true })
        clock += 60_000
        assertTrue(gate.run(false) { scans++; true })
        assertEquals(3, scans)
    }

    @Test
    fun `a failed scan covers nobody`() = runBlocking {
        var scans = 0
        runCatching { gate.run(false) { scans++; error("MediaStore down") } }
        assertFalse(gate.hasRun)
        assertTrue(gate.run(false) { scans++; true })
        assertEquals(2, scans)
    }

    @Test
    fun `a rejected empty scan isn't fresh - the Local Storage visit rescans`() = runBlocking {
        var scans = 0
        // Boot loadOnce: MediaStore not ready, scan came back empty and was rejected (WP-O guard).
        assertTrue(gate.run(false) { scans++; false })
        assertFalse("rejected scan must not count as a run", gate.hasRun)
        clock += 1_000
        // LocalStorageScreen's non-forced refresh (songs still empty) now actually scans.
        assertTrue(gate.run(false) { scans++; true })
        assertTrue(gate.hasRun)
        // ...and a scan that applied IS fresh: the next automatic caller is skipped.
        assertFalse(gate.run(false) { scans++; true })
        assertEquals(2, scans)
    }

    @Test
    fun `a rejected scan covers nobody queued behind it`() = runBlocking {
        var scans = 0
        val release = CompletableDeferred<Unit>()
        val boot = async { gate.run(false) { scans++; release.await(); false } }
        yield()
        val screen = async { gate.run(false) { scans++; true } }
        yield()
        release.complete(Unit)
        boot.await()
        assertTrue("the queued caller scans itself", screen.await())
        assertEquals(2, scans)
    }
}
