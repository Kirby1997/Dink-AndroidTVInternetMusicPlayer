package com.example.dink_smb_player.data.source.smb

import org.junit.Assert.assertEquals
import org.junit.Test

/** SRC-13: mDNS resolves run strictly one at a time (NsdManager < API 34 rejects a second). */
class SerialQueueTest {

    /** Fake resolver: records starts, holds each `done` until the test releases it. */
    private class FakeResolver {
        val started = mutableListOf<String>()
        val pendingDone = ArrayDeque<() -> Unit>()
        var inFlight = 0
        var maxInFlight = 0
        val queue = SerialQueue<String> { item, done ->
            started += item
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            pendingDone.addLast { inFlight--; done() }
        }
        fun finishNext() = pendingDone.removeFirst().invoke()
    }

    @Test
    fun `one resolve in flight, the rest start in order as each finishes`() {
        val r = FakeResolver()
        r.queue.submit("nas-a"); r.queue.submit("nas-b"); r.queue.submit("nas-c")
        assertEquals(listOf("nas-a"), r.started)
        r.finishNext()
        assertEquals(listOf("nas-a", "nas-b"), r.started)
        r.finishNext(); r.finishNext()
        assertEquals(listOf("nas-a", "nas-b", "nas-c"), r.started)
        assertEquals(1, r.maxInFlight)
        // Idle again: a late discovery starts immediately.
        r.queue.submit("nas-d")
        assertEquals("nas-d", r.started.last())
    }

    @Test
    fun `a failed or throwing resolve still advances the queue, a double done does not skip`() {
        val started = mutableListOf<String>()
        val dones = mutableListOf<() -> Unit>()
        val q = SerialQueue<String> { item, done ->
            started += item
            if (item == "boom") throw IllegalStateException("resolve rejected")
            dones += done
        }
        q.submit("boom"); q.submit("a"); q.submit("b")
        assertEquals(listOf("boom", "a"), started)
        dones[0](); dones[0]() // duplicate callback for "a"
        assertEquals(listOf("boom", "a", "b"), started)
    }

    @Test
    fun `synchronous completions drain the whole queue`() {
        val started = mutableListOf<Int>()
        var hold = true
        val held = mutableListOf<() -> Unit>()
        val q = SerialQueue<Int> { item, done -> started += item; if (hold) held += done else done() }
        q.submit(0)
        for (i in 1..1000) q.submit(i)
        hold = false
        held.single().invoke()
        assertEquals((0..1000).toList(), started)
    }

    @Test
    fun `close drops queued items`() {
        val r = FakeResolver()
        r.queue.submit("a"); r.queue.submit("b")
        r.queue.close()
        r.finishNext()
        r.queue.submit("c")
        assertEquals(listOf("a"), r.started)
    }
}
