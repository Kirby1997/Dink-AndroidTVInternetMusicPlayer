package com.example.dink_smb_player.player

import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutionException

/**
 * PLAY-1: a cold media key must never leave a foreground-started service without
 * startForeground(). The receiver only starts the service when there's a session to
 * resume, and a failed resumption for a real play request falls back to a silent
 * foreground notification + stop — after Media3 has seen the failure.
 */
class MediaButtonResumptionTest {

    @Test
    fun `receiver starts the service only when a session can resume`() {
        assertFalse(DinkMediaButtonReceiver.shouldStart(hasSavedSession = false, hasLiveSession = false))
        assertTrue(DinkMediaButtonReceiver.shouldStart(hasSavedSession = true, hasLiveSession = false))
        assertTrue(DinkMediaButtonReceiver.shouldStart(hasSavedSession = false, hasLiveSession = true))
    }

    @Test
    fun `failed resumption for playback fails the future then falls back`() {
        val future = SettableFuture.create<String>()
        val events = mutableListOf<String>()
        future.addListener({ events += "future-done" }, MoreExecutors.directExecutor())

        completeResumption(
            Result.failure(IllegalStateException("no saved playback session")),
            isForPlayback = true,
            future = future,
        ) { events += "fallback" }

        assertEquals(listOf("future-done", "fallback"), events)
        assertTrue(runCatching { future.get() }.exceptionOrNull() is ExecutionException)
    }

    @Test
    fun `failed resumption for a query does not fall back`() {
        val future = SettableFuture.create<String>()
        var fellBack = false

        completeResumption(
            Result.failure(IllegalStateException("x")),
            isForPlayback = false,
            future = future,
        ) { fellBack = true }

        assertFalse(fellBack)
        assertTrue(future.isDone)
    }

    @Test
    fun `successful resumption sets the items and never falls back`() {
        val future = SettableFuture.create<String>()
        var fellBack = false

        completeResumption(Result.success("items"), isForPlayback = true, future = future) { fellBack = true }

        assertFalse(fellBack)
        assertEquals("items", future.get())
    }
}
