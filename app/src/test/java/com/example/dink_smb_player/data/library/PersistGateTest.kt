package com.example.dink_smb_player.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LIB-9 persist-gate invariants shared by the library index and playlists. */
class PersistGateTest {

    @Test
    fun startsNotRestoredAndClosed() {
        val g = PersistGate()
        assertEquals(PersistGate.State.NotRestored, g.state)
        assertFalse("nothing loaded yet → nothing may be written", g.canPersist)
        assertTrue(g.needsRestore)
        g.onLoaded()
        assertTrue(g.canPersist)
        assertFalse(g.needsRestore)
    }

    @Test
    fun corruptStaysDisabledForTheProcess() {
        val g = PersistGate()
        g.onCorrupt()
        assertFalse(g.canPersist)
        assertFalse("corrupt is not retried", g.retryable)
        assertFalse(g.needsRestore)
    }

    @Test
    fun transientIsDisabledButRetryable() {
        val g = PersistGate()
        g.onTransientFailure()
        assertFalse(g.canPersist)
        assertTrue(g.retryable)
        assertTrue(g.needsRestore)
        g.onLoaded()   // retry succeeded
        assertTrue(g.canPersist)
        assertFalse(g.retryable)
    }
}
