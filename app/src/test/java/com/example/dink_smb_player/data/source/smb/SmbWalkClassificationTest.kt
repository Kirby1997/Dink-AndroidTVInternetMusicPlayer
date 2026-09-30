package com.example.dink_smb_player.data.source.smb

import com.example.dink_smb_player.data.source.smb.SmbImporter.ListFailure
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Which listing failures evict the shared connection, which skip a folder with the walk
 * still complete (so pruning isn't blocked forever), and which make the walk incomplete
 * (never prune).
 */
class SmbWalkClassificationTest {

    private fun api(status: NtStatus, path: String = "\\\\nas\\music\\Private") =
        SMBApiException(status.value, SMB2MessageCommandCode.SMB2_QUERY_DIRECTORY, "Query directory failed for $path", null)

    @Test
    fun `connection errors`() {
        assertTrue(SmbClient.isConnectionError(SocketTimeoutException("Read timed out")))
        assertTrue(SmbClient.isConnectionError(TransportException("Connection reset")))
        assertTrue(SmbClient.isConnectionError(RuntimeException(IOException("Broken pipe"))))
        assertTrue(SmbClient.isConnectionError(api(NtStatus.STATUS_USER_SESSION_DELETED)))
        assertTrue(SmbClient.isConnectionError(api(NtStatus.STATUS_NETWORK_NAME_DELETED)))
        // Connect to a powered-off / unplugged NAS — by type, the messages don't say "connection".
        assertTrue(SmbClient.isConnectionError(java.net.NoRouteToHostException("isConnected failed: EHOSTUNREACH (No route to host)")))
        assertTrue(SmbClient.isConnectionError(java.net.ConnectException("failed to connect to /192.168.1.5 (port 445)")))
        assertTrue(SmbClient.isConnectionError(RuntimeException(java.util.concurrent.TimeoutException())))

        assertFalse(SmbClient.isConnectionError(api(NtStatus.STATUS_ACCESS_DENIED)))
        assertFalse(SmbClient.isConnectionError(api(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND)))
        assertFalse(SmbClient.isConnectionError(api(NtStatus.STATUS_SHARING_VIOLATION)))
    }

    @Test
    fun `permission denied or vanished subfolder is skipped, walk stays complete`() {
        for (s in listOf(
            NtStatus.STATUS_ACCESS_DENIED,
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NOT_FOUND,
        )) {
            assertEquals(s.name, ListFailure.SKIP, SmbImporter.listFailureAction(api(s), isRoot = false, retried = false))
            assertEquals(s.name, ListFailure.SKIP, SmbImporter.listFailureAction(api(s), isRoot = false, retried = true))
        }
        // Classified by status, not message text — a folder named "Connection" is still a
        // permission problem, not a dead socket.
        val denied = api(NtStatus.STATUS_ACCESS_DENIED, "\\\\nas\\music\\The Connection Reset EOF")
        assertEquals(ListFailure.SKIP, SmbImporter.listFailureAction(denied, isRoot = false, retried = false))
        // Wrapped is fine too.
        assertEquals(
            ListFailure.SKIP,
            SmbImporter.listFailureAction(RuntimeException(api(NtStatus.STATUS_ACCESS_DENIED)), isRoot = false, retried = false),
        )
    }

    @Test
    fun `a walk root never skips - a not-yet-mounted NAS volume must not prune everything`() {
        assertEquals(
            ListFailure.INCOMPLETE,
            SmbImporter.listFailureAction(api(NtStatus.STATUS_ACCESS_DENIED), isRoot = true, retried = false),
        )
        assertEquals(
            ListFailure.INCOMPLETE,
            SmbImporter.listFailureAction(api(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND), isRoot = true, retried = false),
        )
    }

    @Test
    fun `connection errors reconnect once, then leave the walk incomplete`() {
        val dead = SocketTimeoutException("Read timed out")
        assertEquals(ListFailure.RECONNECT, SmbImporter.listFailureAction(dead, isRoot = false, retried = false))
        assertEquals(ListFailure.INCOMPLETE, SmbImporter.listFailureAction(dead, isRoot = false, retried = true))
        assertEquals(ListFailure.RECONNECT, SmbImporter.listFailureAction(dead, isRoot = true, retried = false))
        assertEquals(ListFailure.INCOMPLETE, SmbImporter.listFailureAction(dead, isRoot = true, retried = true))
    }

    @Test
    fun `other failures neither evict nor count as complete`() {
        assertEquals(
            ListFailure.INCOMPLETE,
            SmbImporter.listFailureAction(api(NtStatus.STATUS_SHARING_VIOLATION), isRoot = false, retried = false),
        )
        assertEquals(
            ListFailure.INCOMPLETE,
            SmbImporter.listFailureAction(IllegalStateException("weird"), isRoot = false, retried = false),
        )
    }
}
