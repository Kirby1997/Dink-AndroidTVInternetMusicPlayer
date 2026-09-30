@file:OptIn(UnstableApi::class)

package com.example.dink_smb_player.data.source

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.example.dink_smb_player.data.source.smb.SmbDataSource

/**
 * One SMB file handle shared by every import-time probe of a single track (PLAY-15 / SRC-15).
 *
 * A new file's probe runs the duration parse ([Mp3DurationParser]), its platform-retriever
 * fallback ([DurationReader] over [Media3MediaDataSource]) and the tail tag read
 * ([TagFallbackReader]) back to back — each used to open the file itself (plus a separate
 * open just to learn its size): up to 4 SMB CREATEs per file. Inside [session] they all
 * borrow ONE handle, opened lazily on first use and closed when the session ends; the size
 * comes from the directory listing instead of a QUERY_INFO.
 *
 * The session is bound to the calling THREAD (readers look it up with [current] on the
 * thread that constructs them — the platform retriever later calls back on binder threads,
 * so [Media3MediaDataSource] captures it at construction). Outside a session [open] simply
 * returns a handle the caller owns, exactly as before. Only smb:// URIs are shared.
 */
internal object SmbProbe {

    private val active = ThreadLocal<Session?>()

    /**
     * Run [block] with every SMB probe of [uri] on this thread sharing one handle.
     * [knownSize] is the file size from the listing (< 0 = unknown). Not inline: the block
     * must not suspend, or it could resume on another thread without the session.
     */
    fun <T> session(uri: String, knownSize: Long, block: () -> T): T {
        if (!uri.startsWith("smb", ignoreCase = true)) return block()
        val s = Session(uri, knownSize)
        val prev = active.get()
        active.set(s)
        try {
            return block()
        } finally {
            active.set(prev)
            s.close()
        }
    }

    /** The session for [uri] active on this thread, if any. */
    fun current(uri: String): Session? = active.get()?.takeIf { it.uri == uri }

    /** A handle on [uri]: [session]'s shared one when given, else a fresh one the caller owns.
     *  Throws the open failure (the session remembers it, so later probes fail fast too). */
    fun open(uri: String, session: Session? = current(uri)): Handle =
        session?.borrow() ?: Handle(openSmb(uri, -1L), owner = null)

    private fun openSmb(uri: String, knownSize: Long): SmbDataSource {
        val ds = SmbDataSource(knownFileSize = knownSize)
        try {
            ds.open(DataSpec.Builder().setUri(uri).setPosition(0).build())
        } catch (t: Throwable) {
            runCatching { ds.close() }
            throw t
        }
        return ds
    }

    class Session internal constructor(val uri: String, private val knownSize: Long) {
        private var ds: SmbDataSource? = null
        private var openFailure: Throwable? = null

        @Synchronized
        internal fun borrow(): Handle {
            openFailure?.let { throw it }
            val cur = ds ?: try {
                openSmb(uri, knownSize).also { ds = it }
            } catch (t: Throwable) {
                openFailure = t
                throw t
            }
            return Handle(cur, owner = this)
        }

        /** A read on [broken] failed: drop it so the next probe re-opens (and reconnects). */
        @Synchronized
        internal fun invalidate(broken: SmbDataSource) {
            if (ds !== broken) return
            ds = null
            runCatching { broken.close() }
        }

        @Synchronized
        internal fun close() {
            runCatching { ds?.close() }
            ds = null
        }
    }

    /** Random-access view of an open SMB file. [release] closes it only when the caller owns
     *  it; a session-borrowed handle stays open for the session's next probe. */
    class Handle internal constructor(private val ds: SmbDataSource, private val owner: Session?) : ProbeFile {
        /** File size in bytes (the listing's, when the session knew it). */
        override val length: Long = ds.openedLength

        override fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Int = try {
            ds.readAtOffset(pos, dst, off, len)
        } catch (t: Throwable) {
            owner?.invalidate(ds)
            throw t
        }

        override fun release() {
            if (owner == null) runCatching { ds.close() }
        }
    }
}

/** Random-access file for the probe readers — an SMB [SmbProbe.Handle], or a fake in tests. */
internal interface ProbeFile {
    val length: Long
    /** Up to [len] bytes at absolute [pos]; count, or -1 at EOF. Throws on I/O failure. */
    fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Int
    fun release()
}
