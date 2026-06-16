package com.example.dink_smb_player.data.source.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.EnumSet

/**
 * Media3 [DataSource] that reads bytes from an SMB share via smbj.
 *
 * URI shape: `smb://host:port/share/dir/file.ext?sid=<shareId>` — `sid` is the
 * registry key consulted in [SmbConnectionRegistry] for the persisted
 * [com.example.dink_smb_player.data.model.SmbShare] + credentials. Without `sid`
 * we have no reliable way to map an arbitrary smb:// URI back to creds the user
 * once entered, so [open] throws.
 *
 * Seek strategy: smbj's [File.getInputStream] doesn't seek natively; we recreate
 * the stream and `skip` for each `position`. Most audio playback is sequential so
 * this only fires on user scrubs — fine for v1.
 */
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var currentUri: Uri? = null
    private var file: File? = null
    private var stream: InputStream? = null
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        currentUri = uri
        transferInitializing(dataSpec)

        val sid = uri.getQueryParameter("sid")
            ?: throw IOException("smb URI missing sid query parameter: $uri")
        val share = SmbConnectionRegistry.share(sid)
            ?: throw IOException("Unknown SMB share id: $sid (was the share deleted?)")
        val creds = SmbConnectionRegistry.creds(sid)

        val disk = try {
            SmbClient.share(share.id, share.host, share.port, share.shareName, creds)
        } catch (t: Throwable) {
            throw IOException("Failed to mount SMB share ${share.name}", t)
        }

        // URI path is "/sharename/dir/sub/file.ext" — strip leading slash + share name,
        // decode percent-encoding, swap to smb's backslash separator.
        val rawPath = uri.path.orEmpty().trimStart('/')
        val afterShare = rawPath.removePrefix(share.shareName).trimStart('/')
        val smbPath = afterShare
            .split('/')
            .joinToString("\\") { URLDecoder.decode(it, "UTF-8") }

        val f = try {
            disk.openFile(
                smbPath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        } catch (t: Throwable) {
            throw IOException("Failed to open SMB file $smbPath", t)
        }
        file = f

        val totalLen = f.fileInformation.standardInformation.endOfFile
        val position = dataSpec.position
        if (position > totalLen) throw IOException("Position $position past end-of-file $totalLen")

        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            totalLen - position
        } else {
            dataSpec.length
        }

        stream = f.inputStream.also { s ->
            var toSkip = position
            while (toSkip > 0L) {
                val skipped = s.skip(toSkip)
                if (skipped <= 0L) throw IOException("Failed to skip to offset $position")
                toSkip -= skipped
            }
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), bytesRemaining).toInt()
        val n = try {
            stream!!.read(buffer, offset, want)
        } catch (t: IOException) {
            throw t
        }
        if (n == -1) return C.RESULT_END_OF_INPUT
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        try {
            runCatching { stream?.close() }
            runCatching { file?.close() }
        } finally {
            stream = null
            file = null
            bytesRemaining = 0L
            currentUri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }
}
