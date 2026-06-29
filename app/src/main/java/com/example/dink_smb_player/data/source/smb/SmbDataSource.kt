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
 * Seek strategy: smbj [File] supports RANDOM-ACCESS positioned reads
 * ([File.read] with a `fileOffset`), so we read from `position` directly. The old
 * approach `skip`-ped an [java.io.InputStream] to the offset, which read-and-discarded
 * every byte up to it — a seek near end-of-file (e.g. an M4A/MP4 `moov` atom, which
 * holds DURATION) dragged the whole file over the network and timed out, so duration
 * probes silently failed. Positioned reads make a tail seek cost only the bytes wanted.
 */
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var currentUri: Uri? = null
    private var file: File? = null
    private var readOffset: Long = 0L
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

        // URI path is "/sharename/dir/sub/file.ext" — strip leading slash + share name,
        // decode percent-encoding, swap to smb's backslash separator.
        val rawPath = uri.path.orEmpty().trimStart('/')
        val afterShare = rawPath.removePrefix(share.shareName).trimStart('/')
        val smbPath = afterShare
            .split('/')
            .joinToString("\\") { URLDecoder.decode(it, "UTF-8") }

        // Mount + open with ONE reconnect retry. The cached smbj connection can have been
        // dropped server-side (idle NAS) without isConnected noticing; the first op then
        // fails, so we evict the dead entry ([SmbClient.close]) and reconnect once. The
        // idle-threshold reconnect in SmbClient.share avoids most of these, but a session
        // dropped mid-use still lands here. Only one retry — a genuinely missing file or
        // down host then surfaces as the error instead of looping.
        var lastErr: Throwable? = null
        var f: File? = null
        for (attempt in 0..1) {
            val disk = try {
                SmbClient.share(share.id, share.host, share.port, share.shareName, creds)
            } catch (t: Throwable) {
                lastErr = t
                SmbClient.close(share.id)
                continue
            }
            try {
                f = disk.openFile(
                    smbPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
                break
            } catch (t: Throwable) {
                lastErr = t
                // Evict the (possibly stale) cached connection so the next attempt reconnects.
                SmbClient.close(share.id)
            }
        }
        val openedFile = f ?: throw IOException("Failed to open SMB file $smbPath", lastErr)
        file = openedFile

        val totalLen = openedFile.fileInformation.standardInformation.endOfFile
        val position = dataSpec.position
        if (position > totalLen) throw IOException("Position $position past end-of-file $totalLen")

        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            totalLen - position
        } else {
            dataSpec.length
        }

        // Positioned read — no whole-file skip to reach the offset (see class doc).
        readOffset = position

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), bytesRemaining).toInt()
        val n = file!!.read(buffer, readOffset, offset, want)
        if (n == -1) return C.RESULT_END_OF_INPUT
        readOffset += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        try {
            runCatching { file?.close() }
        } finally {
            file = null
            readOffset = 0L
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
