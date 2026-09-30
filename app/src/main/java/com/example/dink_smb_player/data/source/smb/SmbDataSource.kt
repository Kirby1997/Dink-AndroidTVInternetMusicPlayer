@file:OptIn(UnstableApi::class)

package com.example.dink_smb_player.data.source.smb

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.share.File
import java.io.FileNotFoundException
import java.io.IOException
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
 * [playback] = true routes through [SmbClient]'s dedicated playback client (own
 * TCP socket), so background walks/art/tag reads can never contend with or tear
 * down the stream's transport. The player's factory sets it; import-time readers
 * (TagReader, duration probes, art) ride the separate reads socket
 * ([SmbClient.Channel.READS]), so they never queue ahead of a walk's directory listings.
 *
 * Error contract: everything thrown from [open]/[read] is an [IOException].
 * smbj surfaces many failures as [RuntimeException] subclasses
 * (SMBRuntimeException & co.) — Media3's Loader treats a RuntimeException as an
 * unexpected FATAL error (no retry), while an IOException goes through
 * LoadErrorHandlingPolicy: ~3 retries with backoff, each retry re-opening the
 * source, which reconnects via [SmbClient] and self-heals a dropped session.
 * Wrapping is the difference between "track skipped mid-play" and a sub-second
 * rebuffer nobody notices.
 *
 * Seek strategy: smbj [File] supports RANDOM-ACCESS positioned reads
 * ([File.read] with a `fileOffset`), so we read from `position` directly. The old
 * approach `skip`-ped an [java.io.InputStream] to the offset, which read-and-discarded
 * every byte up to it — a seek near end-of-file (e.g. an M4A/MP4 `moov` atom, which
 * holds DURATION) dragged the whole file over the network and timed out, so duration
 * probes silently failed. Positioned reads make a tail seek cost only the bytes wanted.
 *
 * Read-ahead (PLAY-11): Media3's extractors issue ~1 KB reads, and each one used to be its own
 * SMB READ round-trip (~2 per 26 ms MP3 frame). [read] now serves them from a [ReadAheadBuffer]
 * refilled by one large positioned read, so a second of audio costs one round-trip instead of
 * ~80. The buffer is keyed by file offset and reset on every [open], so a seek (Media3 closes
 * and re-opens at the new position) never serves stale bytes.
 *
 * [knownFileSize] (>= 0) is the file's size from a fresh directory listing — the import probe
 * passes it so [open] skips the extra QUERY_INFO round-trip. Playback leaves it unset.
 */
class SmbDataSource(
    private val playback: Boolean = false,
    private val knownFileSize: Long = C.LENGTH_UNSET.toLong(),
) : BaseDataSource(/* isNetwork = */ true) {

    private var currentUri: Uri? = null
    private var file: File? = null
    private var lease: SmbClient.ShareLease? = null
    /** The socket this source's handles ride on — the one an error-path evict must target. */
    private val channel = SmbClient.leaseChannel(playback)
    /** Registry id of the open file's share — to evict its connection on a dead read. */
    private var shareId: String? = null
    private var readOffset: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false

    /** Size of the open file (C.LENGTH_UNSET when closed) — for the random-access probe path. */
    internal var openedLength: Long = C.LENGTH_UNSET.toLong()
        private set

    /** Sequential read-ahead for [read]; the array is allocated on the first read, not here. */
    private val readAhead = ReadAheadBuffer(if (playback) PLAYBACK_READ_AHEAD else PROBE_READ_AHEAD)
    private val fetch = ReadAheadBuffer.Fetch { pos, b, o, l ->
        (file ?: throw IOException("SMB read before open() / after close()")).read(b, pos, o, l)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        currentUri = uri
        transferInitializing(dataSpec)

        val sid = uri.getQueryParameter("sid")
            ?: throw IOException("smb URI missing sid query parameter: $uri")
        val share = SmbConnectionRegistry.share(sid)
            ?: throw IOException("Unknown SMB share id: $sid (was the share deleted?)")
        val creds = SmbConnectionRegistry.creds(sid)

        // Path segments are "[sharename, dir, sub, file.ext]". Uri.pathSegments already
        // percent-DECODES each segment, so we must NOT URLDecoder.decode again: a filename
        // with a literal '%' is stored as "%25", which getPath/pathSegments turns back into
        // a bare '%' — a second decode then reads it as a broken escape ("%!") and throws,
        // permanently blocking tags/duration/playback for that file. Drop the share name
        // (first segment) and join the rest with smb's backslash separator.
        val smbPath = uri.pathSegments.drop(1).joinToString("\\")

        // Mount + open with ONE reconnect retry. The cached smbj connection can have been
        // dropped server-side (idle NAS) without isConnected noticing; the first op then
        // fails, so we evict the dead entry ([SmbClient.close]) and reconnect once. The
        // idle-threshold reconnect in SmbClient avoids most of these, but a session
        // dropped mid-use still lands here. Only one retry — a genuinely missing file or
        // down host then surfaces as the error instead of looping. The lease pins the
        // cache entry against proactive idle-eviction for the life of the file handle.
        var lastErr: Throwable? = null
        var f: File? = null
        for (attempt in 0..1) {
            val acquired = try {
                SmbClient.lease(share.id, share.host, share.port, share.shareName, creds, playback)
            } catch (t: Throwable) {
                lastErr = t
                SmbClient.close(share.id, channel)
                continue
            }
            try {
                f = acquired.disk.openFile(
                    smbPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
                lease = acquired
                break
            } catch (t: Throwable) {
                lastErr = t
                acquired.close()
                // Only evict + retry when the failure looks connection-level (a dropped/dead
                // session). A per-file error (FILE_NOT_FOUND, ACCESS_DENIED, SHARING_VIOLATION)
                // means the connection is fine — tearing it down would poison every other
                // concurrent read over the same share. A timeout on a link that still answers
                // an ECHO keeps the connection too (SmbClient.close decides from the cause), and
                // a share handle a concurrent evict closed under us is simply re-acquired.
                if (SmbClient.isConnectionError(t) || SmbClient.isClosedHandleError(t)) {
                    SmbClient.close(share.id, channel, failed = acquired.disk, cause = t)
                } else {
                    break
                }
            }
        }
        val openedFile = f ?: throw openError(lastErr, smbPath)
        file = openedFile
        shareId = share.id

        try {
            val totalLen = if (knownFileSize >= 0) knownFileSize
                else openedFile.fileInformation.standardInformation.endOfFile
            val position = dataSpec.position
            if (position > totalLen) throw IOException("Position $position past end-of-file $totalLen")
            openedLength = totalLen

            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                totalLen - position
            } else {
                dataSpec.length
            }

            // Positioned read — no whole-file skip to reach the offset (see class doc).
            readOffset = position
            readAhead.clear() // a re-open is a seek: never serve the previous position's bytes
        } catch (e: IOException) {
            close()
            throw e
        } catch (t: Throwable) {
            close()
            throw IOException("SMB stat failed for $smbPath", t)
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (file == null) throw IOException("SMB read before open() / after close()")
        val want = minOf(length.toLong(), bytesRemaining).toInt()
        val n = try {
            // Refill is capped at bytesRemaining so a bounded DataSpec never over-reads its range.
            readAhead.read(readOffset, buffer, offset, want, bytesRemaining, fetch)
        } catch (t: Throwable) {
            evictIfDead(t)
            // smbj RuntimeExceptions must become IOExceptions — see class doc.
            throw t as? IOException ?: IOException("SMB read failed at offset $readOffset", t)
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT // smbj -1 = real EOF (file shorter than opened range)
        readOffset += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    /**
     * Random-access read at an ABSOLUTE file offset on the already-open handle — no re-open.
     * The platform MP3 duration scanner issues hundreds of scattered reads per file; routing
     * each through a fresh [open] meant a full SMB CREATE per seek (~636 CREATEs for one probe,
     * ~20s over the network → the probe timed out and duration fell back to a bogus partial
     * value). smbj's [File] is genuinely random-access, so reusing one open handle collapses
     * that whole scan to a single CREATE. Returns -1 at real EOF. [open] must precede this.
     */
    fun readAtOffset(fileOffset: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val f = file ?: throw IOException("readAtOffset before open()")
        val n = try {
            f.read(buffer, fileOffset, offset, length)
        } catch (t: Throwable) {
            evictIfDead(t)
            throw t as? IOException ?: IOException("SMB read failed at offset $fileOffset", t)
        }
        if (n > 0) bytesTransferred(n)
        return n // smbj returns -1 at EOF
    }

    /** A read that died on the connection (not a per-file status) means the cached entry is
     *  dead: evict it now so Media3's retry — which re-[open]s — reconnects at once instead
     *  of reusing the dead socket and waiting out a 30 s request timeout. [SmbClient.close]
     *  only evicts if the entry is still the one this handle was opened on, and keeps a
     *  connection that is only slow (a timed-out read, but the ECHO answers): force-closing
     *  it would fail every other read in flight on the socket. */
    private fun evictIfDead(t: Throwable) {
        val sid = shareId ?: return
        val disk = lease?.disk ?: return
        if (SmbClient.isConnectionError(t)) SmbClient.close(sid, channel, failed = disk, cause = t)
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        try {
            runCatching { file?.close() }
            runCatching { lease?.close() }
        } finally {
            file = null
            lease = null
            shareId = null
            readOffset = 0L
            bytesRemaining = 0L
            readAhead.clear()
            openedLength = C.LENGTH_UNSET.toLong()
            currentUri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val playback: Boolean = false) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(playback)
    }

    companion object {
        /** Read-ahead per [read] refill. Playback streams sequentially for minutes, so a big
         *  refill pays off; import-time header reads (Media3 MetadataRetriever) stop after the
         *  tags, so they keep a smaller one — up to ~32 of those run in parallel. */
        private const val PLAYBACK_READ_AHEAD = 256 * 1024
        private const val PROBE_READ_AHEAD = 128 * 1024

        /** SMB statuses meaning "this file isn't there" (moved, renamed, deleted). */
        private val NOT_FOUND_STATUSES = setOf(
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE,
        )

        /**
         * The exception [open] throws after failing to open [smbPath] with [cause].
         *
         * A missing file becomes a [FileNotFoundException]: Media3's load-error policy never
         * retries one, so a stale track fails in one round trip and the player moves on,
         * instead of ~6 s of retries that re-open a file that isn't coming back. The smbj
         * exception stays as the cause — PlayerState classifies the reason off its status text.
         * Everything else stays a (retryable) [IOException], per the class-doc contract.
         */
        internal fun openError(cause: Throwable?, smbPath: String): IOException {
            var e: Throwable? = cause
            while (e != null) {
                if (e is FileNotFoundException) return e
                if (e is SMBApiException && e.status in NOT_FOUND_STATUSES) {
                    return FileNotFoundException("SMB file not found: $smbPath (${e.status})")
                        .apply { initCause(cause) }
                }
                e = e.cause
            }
            return cause as? IOException ?: IOException("Failed to open SMB file $smbPath", cause)
        }
    }
}

/**
 * Offset-keyed read-ahead cache over a positioned reader (an SMB file handle).
 *
 * [read] serves the request from the cached window when it covers [read]'s `fileOffset`;
 * otherwise it refills the window with ONE [Fetch] of up to [capacity] bytes starting at that
 * offset (a fetch may return fewer — smbj caps a READ at the server's max read size, and a
 * short read is fine). A request of at least [capacity] bytes with nothing cached bypasses the
 * window and reads straight into the caller's array. Because the window is keyed by absolute
 * offset, random access is always correct: a read outside it just refills.
 *
 * The array is allocated on the first refill, so a source that is opened but never read
 * (or only read in big chunks) costs nothing. Not thread-safe; one reader at a time.
 */
internal class ReadAheadBuffer(private val capacity: Int) {

    /** Positioned read: up to [len] bytes at [pos] into [dst] at [off]; count, or -1 at EOF. */
    fun interface Fetch {
        fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int
    }

    private var buf: ByteArray? = null
    private var start = -1L // file offset of buf[0]; -1 = empty
    private var len = 0

    /** Drop the cached window (keeps the array for reuse). */
    fun clear() {
        start = -1L
        len = 0
    }

    /**
     * Up to [length] bytes of the file at [fileOffset] into [dst] at [off]. Returns the count
     * (possibly fewer than asked — at most what one window holds), or the fetch's -1 at EOF.
     * [maxFetch] caps how far past [fileOffset] a refill may read (the caller's remaining
     * range / byte budget). Exceptions from [fetch] propagate with the window left empty.
     */
    fun read(fileOffset: Long, dst: ByteArray, off: Int, length: Int, maxFetch: Long, fetch: Fetch): Int {
        if (length <= 0) return 0
        if (start >= 0 && fileOffset >= start && fileOffset < start + len) {
            val srcOff = (fileOffset - start).toInt()
            val give = minOf(length, len - srcOff)
            System.arraycopy(buf!!, srcOff, dst, off, give)
            return give
        }
        clear()
        val cap = minOf(capacity.toLong(), maxFetch).toInt()
        if (cap <= 0) return -1
        if (length >= cap) return fetch.read(fileOffset, dst, off, minOf(length, cap))
        val b = buf ?: ByteArray(capacity).also { buf = it }
        val n = fetch.read(fileOffset, b, 0, cap)
        if (n <= 0) return n
        start = fileOffset
        len = n
        val give = minOf(length, n)
        System.arraycopy(b, 0, dst, off, give)
        return give
    }
}
