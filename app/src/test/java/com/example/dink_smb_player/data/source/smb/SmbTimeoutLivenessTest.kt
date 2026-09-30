package com.example.dink_smb_player.data.source.smb

import com.example.dink_smb_player.data.source.smb.SmbImporter.ListFailure
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A timeout on a busy-but-alive link must not cascade. Seen on the TV: one list timed out,
 * the error path force-closed the pooled connection, every other in-flight list then failed
 * "DiskShare has already been closed" (walk incomplete), and the next timeout tripped the
 * circuit breaker although the NAS was up the whole time.
 */
class SmbTimeoutLivenessTest {

    /** What smbj throws when a request's reply doesn't arrive within the 30 s timeout. */
    private fun requestTimeout(): Throwable = SMBRuntimeException(
        TransportException(ExecutionException(SMBRuntimeException(TimeoutException("Timeout expired")))),
    )

    /** What smbj throws when an op uses a share handle that was closed under it. */
    private fun closedShare(): Throwable = SMBRuntimeException("DiskShare has already been closed")

    private fun api(status: NtStatus) =
        SMBApiException(status.value, SMB2MessageCommandCode.SMB2_QUERY_DIRECTORY, "Query directory failed", null)

    // --- classification -------------------------------------------------------------------

    @Test
    fun `timeouts and closed handles are told apart from dead sockets`() {
        assertTrue(SmbClient.isTimeout(requestTimeout()))
        assertTrue(SmbClient.isTimeout(java.net.SocketTimeoutException("Read timed out")))
        assertFalse(SmbClient.isTimeout(closedShare()))
        assertFalse(SmbClient.isTimeout(SocketException("Connection reset")))
        assertFalse(SmbClient.isTimeout(api(NtStatus.STATUS_USER_SESSION_DELETED)))

        assertTrue(SmbClient.isClosedHandleError(closedShare()))
        assertTrue(SmbClient.isClosedHandleError(RuntimeException(closedShare())))
        assertFalse(SmbClient.isClosedHandleError(requestTimeout()))
        assertFalse(SmbClient.isClosedHandleError(api(NtStatus.STATUS_ACCESS_DENIED)))
        // A closed handle is not a connection failure: it must never trip the breaker.
        assertFalse(SmbClient.isConnectionError(closedShare()))
    }

    // --- SmbClient.close: keep or force-close ---------------------------------------------

    @Test
    fun `a timeout on a link that answers the ECHO keeps the connection`() {
        var probes = 0
        assertTrue(SmbClient.keepAfterFailure(requestTimeout(), connected = true, shareOpen = true) { probes++; true })
        assertEquals(1, probes)
    }

    @Test
    fun `a timeout on a link that does not answer is force-closed`() {
        assertFalse(SmbClient.keepAfterFailure(requestTimeout(), connected = true, shareOpen = true) { false })
    }

    @Test
    fun `a dead socket is force-closed without probing it`() {
        var probed = false
        assertFalse(SmbClient.keepAfterFailure(requestTimeout(), connected = false, shareOpen = true) { probed = true; true })
        assertFalse(SmbClient.keepAfterFailure(closedShare(), connected = false, shareOpen = false) { probed = true; true })
        assertFalse(probed)
    }

    @Test
    fun `a closed handle on a live socket keeps the connection - the share is just re-mounted`() {
        var probed = false
        assertTrue(SmbClient.keepAfterFailure(closedShare(), connected = true, shareOpen = false) { probed = true; false })
        assertFalse(probed)
    }

    @Test
    fun `real connection failures still evict, ECHO or not`() {
        var probed = false
        for (t in listOf(
            SocketException("Connection reset"),
            TransportException("Broken pipe"),
            // The session is gone server-side: the socket answers ECHO, the session never will.
            api(NtStatus.STATUS_USER_SESSION_DELETED),
            api(NtStatus.STATUS_NETWORK_NAME_DELETED),
        )) {
            assertFalse(t.toString(), SmbClient.keepAfterFailure(t, connected = true, shareOpen = true) { probed = true; true })
        }
        assertFalse(probed)
    }

    @Test
    fun `an evict with nothing cached reports the connection as gone`() {
        assertTrue(SmbClient.close("smb-none-" + System.nanoTime(), cause = requestTimeout()))
        assertTrue(SmbClient.close("smb-none-" + System.nanoTime(), SmbClient.Channel.READS))
    }

    @Test
    fun `file reads lease their own channel, never the listings' one`() {
        assertEquals(SmbClient.Channel.READS, SmbClient.leaseChannel(playback = false))
        assertEquals(SmbClient.Channel.PLAYBACK, SmbClient.leaseChannel(playback = true))
    }

    // --- the walk ---------------------------------------------------------------------------

    /**
     * A fake share with ONE connection, modelled the way SmbClient.close treats it: [evict]
     * force-closes it only when the failure isn't a timeout on a link that answers the ECHO.
     * A force-close would fail the other folders (they'd throw "already been closed").
     */
    private class FakeLink(private val script: MutableMap<String, ArrayDeque<Any>>, var answersEcho: Boolean = true) {
        var forceCloses = 0
        var echoes = 0
        val calls = mutableListOf<String>()

        fun list(path: String): List<String> {
            calls += path
            val queue = script.getValue(path)
            return when (val r = if (queue.size > 1) queue.removeFirst() else queue.first()) {
                is Throwable -> throw r
                else -> @Suppress("UNCHECKED_CAST") (r as List<String>)
            }
        }

        /** True when the connection was force-closed. */
        fun evict(t: Throwable): Boolean {
            val keep = SmbClient.keepAfterFailure(t, connected = true, shareOpen = true) { echoes++; answersEcho }
            if (!keep) forceCloses++
            return !keep
        }

        fun walkFolder(path: String, breaker: AtomicBoolean, isRoot: Boolean = false) = SmbImporter.listFolder(
            path, isRoot, breaker,
            list = { list(path) },
            evict = { evict(it) },
            hostAlive = { !evict(it) },
        )
    }

    private fun script(vararg folders: Pair<String, List<Any>>) =
        folders.associate { (k, v) -> k to ArrayDeque(v) }.toMutableMap()

    @Test
    fun `a list that times out on a live link fails alone - no force-close, no breaker, others continue`() {
        val link = FakeLink(script(
            "Slow" to listOf(requestTimeout()), // times out on the retry as well
            "B" to listOf(listOf("x.mp3")),
            "C" to listOf(listOf("y.mp3")),
        ))
        val breaker = AtomicBoolean(false)

        assertNull(link.walkFolder("Slow", breaker)) // that folder: walk incomplete, never pruned
        assertEquals(listOf("Slow", "Slow"), link.calls) // one retry
        assertEquals(0, link.forceCloses)
        assertTrue(link.echoes >= 1)
        assertFalse(breaker.get())

        // The rest of the walk carries on, on the same connection.
        assertEquals(listOf("x.mp3"), link.walkFolder("B", breaker))
        assertEquals(listOf("y.mp3"), link.walkFolder("C", breaker, isRoot = true))
        assertEquals(0, link.forceCloses)
    }

    @Test
    fun `a list that times out once succeeds on the retry without touching the connection`() {
        val link = FakeLink(script("A" to listOf(requestTimeout(), listOf("x.mp3"))))
        val breaker = AtomicBoolean(false)
        assertEquals(listOf("x.mp3"), link.walkFolder("A", breaker))
        assertEquals(0, link.forceCloses)
        assertFalse(breaker.get())
    }

    @Test
    fun `a closed share handle is re-acquired and the list retried once`() {
        val link = FakeLink(script("A" to listOf(closedShare(), listOf("x.mp3"))))
        val breaker = AtomicBoolean(false)
        assertEquals(ListFailure.RECONNECT, SmbImporter.listFailureAction(closedShare(), isRoot = false, retried = false))
        assertEquals(ListFailure.RECONNECT, SmbImporter.listFailureAction(closedShare(), isRoot = true, retried = false))

        assertEquals(listOf("x.mp3"), link.walkFolder("A", breaker))
        assertEquals(listOf("A", "A"), link.calls)
        assertEquals(0, link.forceCloses) // the socket is fine — only the handle was gone
        assertFalse(breaker.get())
    }

    @Test
    fun `a share handle closed twice running leaves that folder incomplete, not the host dead`() {
        val link = FakeLink(script("A" to listOf(closedShare()), "B" to listOf(listOf("x.mp3"))))
        val breaker = AtomicBoolean(false)
        assertEquals(ListFailure.INCOMPLETE, SmbImporter.listFailureAction(closedShare(), isRoot = false, retried = true))
        assertNull(link.walkFolder("A", breaker))
        assertEquals(listOf("A", "A"), link.calls) // once, not a loop
        assertFalse(breaker.get())
        assertEquals(listOf("x.mp3"), link.walkFolder("B", breaker))
    }

    @Test
    fun `a host that stops answering still trips the breaker`() {
        val link = FakeLink(
            script("A" to listOf(requestTimeout()), "B" to listOf(listOf("x.mp3"))),
            answersEcho = false,
        )
        val breaker = AtomicBoolean(false)
        assertNull(link.walkFolder("A", breaker))
        assertTrue(link.forceCloses >= 1)
        assertTrue(breaker.get())
        // Queued folders return at once, without the network.
        assertNull(link.walkFolder("B", breaker))
        assertEquals(listOf("A", "A"), link.calls)
    }

    @Test
    fun `a dead connection whose reconnect works does not trip the breaker`() {
        // The retry's connection died too, but the host answers a fresh connect (hostAlive).
        val breaker = AtomicBoolean(false)
        val result = SmbImporter.listFolder<String>(
            "A", isRoot = false, breaker,
            list = { throw SocketException("Connection reset") },
            evict = {},
            hostAlive = { true },
        )
        assertNull(result)
        assertFalse(breaker.get())
    }
}
