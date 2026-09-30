package com.example.dink_smb_player.data.art

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One [Mutex] per key, created on demand and dropped once no coroutine holds or waits on it —
 * so resolving thousands of distinct album covers doesn't leave a lock entry behind per
 * album forever. Ref-counted rather than "remove after unlock", which would let a late
 * arrival create a second mutex for a key another coroutine is still waiting on.
 */
internal class KeyedMutex {

    private class Entry {
        val mutex = Mutex()
        var holders = 0 // guarded by `entries`
    }

    private val entries = HashMap<String, Entry>()

    suspend fun <T> withLock(key: String, block: suspend () -> T): T {
        val entry = synchronized(entries) {
            entries.getOrPut(key) { Entry() }.also { it.holders++ }
        }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(entries) {
                if (--entry.holders == 0) entries.remove(key)
            }
        }
    }

    /** Live entries — for tests. */
    fun size(): Int = synchronized(entries) { entries.size }
}
