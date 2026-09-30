package com.example.dink_smb_player.data.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide lifecycle guard for library sources (SMB share / cloud provider ids).
 *
 * Every job that walks a source and writes its rows — import, monitor pass, folder
 * removal — runs through [runExclusive]: one at a time per source (a per-source
 * [Mutex]), and registered as a cancellable [Job]. [remove] tombstones the id, cancels
 * and JOINS every registered job for it, and only then runs the row/prefs deletion under
 * the same lock. So a delete can't be followed by a late upsert from a walk that was
 * already in flight, which is how a removed share used to come back.
 *
 * Tombstones live for the process: source ids are random (smb-xxxxxxxx), never reused.
 * Across processes the callers re-read SharePrefs inside the lock and skip a source
 * that is gone. The DAO-level tombstone in the index is the backstop for writers that
 * don't go through here (retag).
 */
object SourceLocks {

    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val removed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** A running job + the folders it touches (null = the whole source). */
    private class Tracked(val job: Job, val roots: List<String>?)
    private val tracked = ConcurrentHashMap<String, MutableSet<Tracked>>()

    private fun mutexFor(sourceId: String): Mutex = mutexes.getOrPut(sourceId) { Mutex() }

    fun isRemoved(sourceId: String): Boolean = sourceId in removed

    /** Number of live registered jobs for [sourceId]. Visible for tests. */
    internal fun activeJobCount(sourceId: String): Int =
        tracked[sourceId]?.count { it.job.isActive } ?: 0

    /**
     * Run [block] exclusively for [sourceId], as a job [remove] / [cancelOverlapping] can
     * cancel. [roots] are the folders it touches ("" = whole source, null = unknown/all).
     * Returns null — without running [block] — when the source is removed (checked again
     * after the lock is taken), or when the job was cancelled by a removal. Cancellation
     * of the CALLER still propagates normally; a failure in [block] is rethrown.
     */
    suspend fun <T> runExclusive(
        sourceId: String,
        roots: List<String>? = null,
        block: suspend () -> T,
    ): T? {
        if (sourceId in removed) return null
        return coroutineScope {
            // LAZY so the job is registered before any of its code runs — a removal that
            // lands in between can't miss it.
            val work = async(start = CoroutineStart.LAZY) {
                mutexFor(sourceId).withLock {
                    if (sourceId in removed) null else block()
                }
            }
            val entry = Tracked(work, roots)
            tracked.getOrPut(sourceId) { ConcurrentHashMap.newKeySet() }.add(entry)
            try {
                work.await()
            } catch (c: CancellationException) {
                // Our caller was cancelled → propagate. Only the job itself was cancelled
                // (source removed / folder removed) → a quiet no-op for the caller.
                currentCoroutineContext().ensureActive()
                null
            } finally {
                tracked[sourceId]?.remove(entry)
            }
        }
    }

    /** Cancel and wait out the jobs for [sourceId] whose roots overlap any of [roots]
     *  (or that cover the whole source). For "remove this folder from the library": an
     *  import of that folder still in flight would re-add its rows after the prune. */
    suspend fun cancelOverlapping(sourceId: String, roots: List<String>) {
        val victims = tracked[sourceId].orEmpty().filter { t ->
            t.roots == null || t.roots.any { a -> roots.any { b -> pathsOverlap(a, b) } }
        }
        victims.forEach { it.job.cancel() }
        victims.forEach { it.job.join() }
    }

    /**
     * Remove [sourceId]: tombstone it (new [runExclusive] calls no-op), cancel every
     * registered job, run [afterCancel] (e.g. close its connections so blocked network
     * reads fail fast), JOIN those jobs, then run [block] (delete rows, creds, prefs)
     * under the source lock. If [block] fails the tombstone is lifted so the user can
     * retry the delete.
     */
    suspend fun <T> remove(
        sourceId: String,
        afterCancel: suspend () -> Unit = {},
        block: suspend () -> T,
    ): T {
        removed += sourceId
        try {
            val victims = tracked[sourceId].orEmpty().toList()
            victims.forEach { it.job.cancel() }
            afterCancel()
            victims.forEach { it.job.join() }
            return mutexFor(sourceId).withLock { block() }
        } catch (t: Throwable) {
            removed -= sourceId
            throw t
        }
    }

    /** True when two folder paths (backslash, "" = root) are the same folder or one
     *  contains the other. */
    internal fun pathsOverlap(a: String, b: String): Boolean {
        val x = a.trim('\\')
        val y = b.trim('\\')
        return x.isEmpty() || y.isEmpty() || x == y ||
            x.startsWith("$y\\") || y.startsWith("$x\\")
    }
}
