package com.example.dink_smb_player.data.source.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SRC-4: the walk's circuit breaker, driven through [SmbImporter.listFolder] with a fake
 * lister. A connection failure that survives the reconnect aborts every later folder without
 * touching the network (walk incomplete → never pruned); per-folder statuses don't trip it.
 */
class SmbWalkCircuitBreakerTest {

    private fun api(status: NtStatus) =
        SMBApiException(status.value, SMB2MessageCommandCode.SMB2_QUERY_DIRECTORY, "Query directory failed", null)

    /** Fake share: folder → listing, or the throwable its list() raises. */
    private class FakeLister(private val script: Map<String, Any>) {
        val calls = mutableListOf<String>()
        var evictions = 0
        fun list(path: String): List<String> {
            calls += path
            return when (val r = script[path]) {
                is Throwable -> throw r
                is List<*> -> r.map { it as String }
                else -> error("unscripted $path")
            }
        }
    }

    private fun FakeLister.walkFolder(path: String, isRoot: Boolean, breaker: AtomicBoolean) =
        SmbImporter.listFolder(path, isRoot, breaker, list = { list(path) }, evict = { evictions++ })

    @Test
    fun `dead host trips the breaker after one reconnect, and queued folders never hit the network`() {
        val dead = SocketTimeoutException("Read timed out")
        val fake = FakeLister(mapOf("A" to dead, "B" to listOf("x.mp3"), "C" to listOf("y.mp3")))
        val breaker = AtomicBoolean(false)

        assertNull(fake.walkFolder("A", isRoot = false, breaker))
        assertEquals(listOf("A", "A"), fake.calls) // first try + one reconnect retry
        assertEquals(1, fake.evictions)
        assertTrue(breaker.get())

        // Everything still queued returns "couldn't list" (→ walk incomplete) at once.
        assertNull(fake.walkFolder("B", isRoot = false, breaker))
        assertNull(fake.walkFolder("C", isRoot = true, breaker))
        assertEquals(listOf("A", "A"), fake.calls)
        assertEquals(1, fake.evictions)
    }

    @Test
    fun `unreachable host on connect trips it too`() {
        val fake = FakeLister(mapOf("" to NoRouteToHostException("failed to connect: EHOSTUNREACH (No route to host)")))
        val breaker = AtomicBoolean(false)
        assertNull(fake.walkFolder("", isRoot = true, breaker))
        assertTrue(breaker.get())
    }

    @Test
    fun `a dropped session that reconnects fine does not trip it`() {
        var first = true
        val breaker = AtomicBoolean(false)
        var evictions = 0
        val result = SmbImporter.listFolder(
            "A", isRoot = false, breaker,
            list = { if (first) { first = false; throw api(NtStatus.STATUS_USER_SESSION_DELETED) } else listOf("x.mp3") },
            evict = { evictions++ },
        )
        assertEquals(listOf("x.mp3"), result)
        assertEquals(1, evictions)
        assertFalse(breaker.get())
    }

    @Test
    fun `access denied still skips the folder and leaves the breaker alone`() {
        val fake = FakeLister(mapOf(
            "Private" to api(NtStatus.STATUS_ACCESS_DENIED),
            "Gone" to api(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND),
            "Music" to listOf("a.flac"),
        ))
        val breaker = AtomicBoolean(false)
        assertEquals(emptyList<String>(), fake.walkFolder("Private", isRoot = false, breaker))
        assertEquals(emptyList<String>(), fake.walkFolder("Gone", isRoot = false, breaker))
        assertEquals(listOf("a.flac"), fake.walkFolder("Music", isRoot = false, breaker))
        assertEquals(0, fake.evictions)
        assertFalse(breaker.get())
    }

    @Test
    fun `a denied ROOT is incomplete but not a dead host`() {
        val fake = FakeLister(mapOf("music" to api(NtStatus.STATUS_ACCESS_DENIED), "other" to listOf("b.mp3")))
        val breaker = AtomicBoolean(false)
        assertNull(fake.walkFolder("music", isRoot = true, breaker))
        assertFalse(breaker.get())
        assertEquals(listOf("b.mp3"), fake.walkFolder("other", isRoot = true, breaker))
    }
}
