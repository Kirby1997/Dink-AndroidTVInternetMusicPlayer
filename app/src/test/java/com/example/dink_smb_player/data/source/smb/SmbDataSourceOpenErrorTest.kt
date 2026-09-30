package com.example.dink_smb_player.data.source.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * PLAY-10: a missing file must surface as [FileNotFoundException] (Media3 never retries it),
 * everything else as a plain, retryable [IOException] — with the smbj cause kept, since
 * PlayerState classifies the error reason off the cause chain's status text.
 */
class SmbDataSourceOpenErrorTest {

    private fun api(status: NtStatus) =
        SMBApiException(status.value, SMB2MessageCommandCode.SMB2_CREATE, "Create failed for \\\\nas\\music\\a.mp3", null)

    @Test
    fun `not-found statuses become FileNotFoundException with the smbj cause`() {
        for (s in listOf(
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE,
        )) {
            val cause = api(s)
            val e = SmbDataSource.openError(cause, "Album\\a.mp3")
            assertTrue(s.name, e is FileNotFoundException)
            assertSame(cause, e.cause)
            assertTrue(e.message!!.contains(s.name))
        }
        // Wrapped by something else — still found.
        val wrapped = RuntimeException(api(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND))
        assertTrue(SmbDataSource.openError(wrapped, "a.mp3") is FileNotFoundException)
    }

    @Test
    fun `other failures stay retryable IOExceptions`() {
        val timeout = SocketTimeoutException("Read timed out")
        assertSame(timeout, SmbDataSource.openError(timeout, "a.mp3"))

        for (s in listOf(NtStatus.STATUS_ACCESS_DENIED, NtStatus.STATUS_SHARING_VIOLATION, NtStatus.STATUS_USER_SESSION_DELETED)) {
            val cause = api(s)
            val e = SmbDataSource.openError(cause, "a.mp3")
            assertFalse(s.name, e is FileNotFoundException)
            assertSame(cause, e.cause)
        }
        val none = SmbDataSource.openError(null, "a.mp3")
        assertTrue(none is IOException && none !is FileNotFoundException)
    }
}
