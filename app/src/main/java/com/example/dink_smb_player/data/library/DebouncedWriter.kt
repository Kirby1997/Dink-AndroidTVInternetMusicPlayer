package com.example.dink_smb_player.data.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coalescing, debounced write-behind (LIB-3). [request] marks the state dirty and wakes ONE
 * long-lived coroutine on [scope]; it waits [delayMs] and then runs [write] once for everything
 * requested meanwhile — a burst of N requests is one write. [flush] writes now if anything is
 * pending (ON_STOP). [write] returns false on failure: the state stays dirty, so the next
 * request or flush retries it (no retry loop of its own).
 */
internal class DebouncedWriter(
    scope: CoroutineScope,
    private val delayMs: Long,
    private val write: suspend () -> Boolean,
) {
    private val dirty = AtomicBoolean(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val writeLock = Mutex()

    init {
        scope.launch {
            for (signal in wake) {
                delay(delayMs)
                writeIfDirty()
            }
        }
    }

    fun request() {
        dirty.set(true)
        wake.trySend(Unit)
    }

    /** Write now if a request is pending; no-op otherwise. */
    suspend fun flush() = writeIfDirty()

    private suspend fun writeIfDirty() = writeLock.withLock {
        if (!dirty.getAndSet(false)) return@withLock
        var ok = false
        try {
            ok = write()
        } finally {
            if (!ok) dirty.set(true)
        }
    }
}
