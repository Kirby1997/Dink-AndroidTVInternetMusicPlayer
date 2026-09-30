package com.example.dink_smb_player.data.source

import androidx.media3.common.ParserException
import com.example.dink_smb_player.data.art.ArtExtractor
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/**
 * Error ≠ absent: a transient read failure must classify as [ReadResult.Error] (retry later),
 * and only a definitive outcome (no tags/picture, file gone, unparseable) as [ReadResult.Absent].
 */
class ReadResultTest {

    private fun api(status: NtStatus) =
        SMBApiException(status.value, SMB2MessageCommandCode.SMB2_CREATE, "Create failed for \\\\nas\\music\\a.mp3", null)

    /** How SmbDataSource.open surfaces smbj failures: an IOException wrapping the cause. */
    private fun smbOpen(cause: Throwable) = IOException("Failed to open SMB file a.mp3", cause)

    @Test
    fun `transient failures`() {
        assertTrue(ReadFailures.isTransient(TimeoutException()))
        assertTrue(ReadFailures.isTransient(SocketTimeoutException("Read timed out")))
        assertTrue(ReadFailures.isTransient(ExecutionException(IOException("Connection reset"))))
        assertTrue(ReadFailures.isTransient(smbOpen(TransportException("Broken pipe"))))
        assertTrue(ReadFailures.isTransient(smbOpen(api(NtStatus.STATUS_NETWORK_NAME_DELETED))))
        assertTrue(ReadFailures.isTransient(smbOpen(api(NtStatus.STATUS_SHARING_VIOLATION))))
        assertTrue(ReadFailures.isTransient(IOException("Unknown SMB share id: x (was the share deleted?)")))
        assertTrue(ReadFailures.isTransient(InterruptedException()))
        assertTrue(ReadFailures.isTransient(OutOfMemoryError()))
    }

    @Test
    fun `definitive outcomes`() {
        assertFalse(ReadFailures.isTransient(null))
        assertFalse(ReadFailures.isTransient(smbOpen(api(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND))))
        assertFalse(ReadFailures.isTransient(smbOpen(api(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND))))
        assertFalse(ReadFailures.isTransient(smbOpen(api(NtStatus.STATUS_ACCESS_DENIED))))
        assertFalse(ReadFailures.isTransient(IOException(FileNotFoundException("/sdcard/a.mp3"))))
        // ParserException IS an IOException — must still be definitive (unparseable file).
        assertFalse(ReadFailures.isTransient(ExecutionException(ParserException.createForMalformedContainer("bad", null))))
        assertFalse(ReadFailures.isTransient(IllegalStateException("extractor bug")))
    }

    @Test
    fun `tag fallback classify`() {
        val tags = TagReader.Tags(title = "T")
        assertEquals(ReadResult.Found(tags), TagFallbackReader.classify(tags, null))
        assertEquals(ReadResult.Absent, TagFallbackReader.classify(null, null))
        assertEquals(ReadResult.Absent, TagFallbackReader.classify(null, smbOpen(api(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND))))
        val timeout = TimeoutException("probe deadline exceeded")
        val err = TagFallbackReader.classify(null, timeout)
        assertTrue(err is ReadResult.Error)
        assertNull(err.valueOrNull())
        // Partial tags survive a transient failure but the read is not conclusive.
        val partial = TagFallbackReader.classify(tags, timeout)
        assertEquals(ReadResult.Error(timeout, tags), partial)
        assertEquals(tags, partial.valueOrNull())
    }

    @Test
    fun `art classify`() {
        val bytes = byteArrayOf(1, 2, 3)
        val found = ArtExtractor.classify(bytes, TimeoutException())
        assertTrue(found is ReadResult.Found)
        assertEquals(ReadResult.Absent, ArtExtractor.classify(null, null))
        assertEquals(ReadResult.Absent, ArtExtractor.classify(ByteArray(0), null))
        assertEquals(ReadResult.Absent, ArtExtractor.classify(null, smbOpen(api(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND))))
        assertTrue(ArtExtractor.classify(null, smbOpen(TransportException("Connection reset"))) is ReadResult.Error)
    }
}
