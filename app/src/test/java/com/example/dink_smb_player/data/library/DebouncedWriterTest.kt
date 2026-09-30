package com.example.dink_smb_player.data.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** LIB-3: plays persist through one coalescing, debounced writer with an explicit flush. */
class DebouncedWriterTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    @Test
    fun `a burst of requests is one write after the delay`() = runBlocking {
        val writes = AtomicInteger()
        val w = DebouncedWriter(scope, 150) { writes.incrementAndGet(); true }
        repeat(10) { w.request() }
        delay(50)
        assertEquals(0, writes.get())
        delay(500)
        assertEquals(1, writes.get())
    }

    @Test
    fun `flush writes a pending request now and the timer then has nothing to do`() = runBlocking {
        val writes = AtomicInteger()
        val w = DebouncedWriter(scope, 150) { writes.incrementAndGet(); true }
        w.request()
        w.flush()
        assertEquals(1, writes.get())
        delay(500)
        assertEquals(1, writes.get())
        w.flush()
        assertEquals("nothing pending", 1, writes.get())
    }

    @Test
    fun `a failed write stays pending for the next flush`() = runBlocking {
        val writes = AtomicInteger()
        var ok = false
        val w = DebouncedWriter(scope, 10_000) { writes.incrementAndGet(); ok }
        w.request()
        w.flush()
        ok = true
        w.flush()
        assertEquals(2, writes.get())
        w.flush()
        assertEquals(2, writes.get())
    }
}
