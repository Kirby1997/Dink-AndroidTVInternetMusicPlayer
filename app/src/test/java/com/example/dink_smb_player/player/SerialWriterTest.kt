package com.example.dink_smb_player.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Session/position writes must land in the order they were taken: one launch per write
 * let a slow older checkpoint finish after a newer save, restoring a stale position.
 */
class SerialWriterTest {

    @Test
    fun `a slow write finishes before the next one starts`() = runTest {
        val writer = SerialWriter(backgroundScope)
        val log = mutableListOf<String>()

        writer.submit { log += "session:start"; delay(1_000); log += "session:end" }
        writer.submit { log += "position" }
        // advanceUntilIdle() ignores backgroundScope work; advancing time runs it.
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()

        assertEquals(listOf("session:start", "session:end", "position"), log)
    }

    @Test
    fun `a failing write does not stop later writes`() = runTest {
        val writer = SerialWriter(backgroundScope)
        val log = mutableListOf<String>()

        writer.submit { error("disk full") }
        writer.submit { log += "after" }
        // advanceUntilIdle() ignores backgroundScope work; advancing time runs it.
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()

        assertEquals(listOf("after"), log)
    }
}
