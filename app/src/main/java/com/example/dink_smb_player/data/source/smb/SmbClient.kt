package com.example.dink_smb_player.data.source.smb

import com.example.dink_smb_player.data.model.SmbProtocol
import com.example.dink_smb_player.data.prefs.SmbCreds
import com.example.dink_smb_player.data.source.SourceLocks
import com.hierynomus.mssmb2.SMB2Packet
import com.hierynomus.mssmb2.messages.SMB2Echo
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * smbj wrapper that keeps one [DiskShare] per cache key alive across operations.
 *
 * smbj's [SMBClient] is heavy — it spins up a transport thread and an SMB packet
 * decoder per [Connection]. Tearing it down between every list/read on a TV would
 * stall the UI for ~600 ms each time. Instead we cache per-share, and only
 * tear down when the share is removed or the process exits.
 *
 * THREE smbj clients, not one: smbj pools connections per host:port inside a client
 * (SMBClient.connectionTable, refcounted via Pooled.lease/release), so two cache
 * keys under one client would still share a single TCP socket + transport thread.
 * Each [Channel] gets its OWN [SMBClient] — its own socket:
 *  - [Channel.PLAYBACK]: the stream, so an import walk, art fetch, or browse can never
 *    contend with or tear down the stream's transport.
 *  - [Channel.READS]: file-content reads outside playback ([lease] — tag / duration / art /
 *    lyric probes). A probe pulls up to MBs; on one TCP stream every directory listing
 *    queued behind those bytes, and over the TV's Wi-Fi a walk's lists then hit the 30 s
 *    request timeout. On their own socket a probe's timeout (and a dead-link evict) can't
 *    touch the walk's listings either.
 *  - [Channel.GENERAL]: directory listings — walks, browse, sidecar-dir lists ([share]).
 *
 * Eviction safety: a cache entry is only proactively closed when no [ShareLease]
 * is outstanding on it. Before this, an idle-evict could close the connection out
 * from under a file handle that was actively streaming — smbj then threw from the
 * next read and Media3 treated it as fatal. Lease holders (SmbDataSource) pin the
 * entry for the duration of an open file handle.
 *
 * Thread-safety: connect/mount is serialized PER CACHE KEY (per share), not
 * globally — a 30 s connect timeout against an unreachable host must not block
 * playback opens against a healthy one.
 *
 * Auth: [SmbCreds] with null user → guest auth via [AuthenticationContext.guest].
 */
object SmbClient {

    private fun newClient(): SMBClient {
        val cfg = SmbConfig.builder()
            // 30 s is plenty for LAN; default 60 s leaves the wizard hanging if the
            // host is unreachable. The Test-Connection step should fail fast.
            .withTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            // soTimeout MUST stay 0 (smbj's own default). It is the timeout of the socket's
            // blocking read, and smbj's per-connection packet-reader thread sits in exactly that
            // read while the link is idle: any non-zero value makes the reader throw after that
            // much silence, which kills the reader for good (PacketReader.run returns). Its
            // handleError only RELEASES the pooled connection, so when a second share on the
            // same host holds a lease the socket stays open with nobody reading — a zombie that
            // SMBClient keeps handing out, where every request then waits the full 30 s timeout.
            // Per-request deadlines come from withTimeout above; dead links are caught by the
            // ECHO probe in [entryForLocked] and the forced evict in [close].
            .withSoTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return SMBClient(cfg)
    }

    /** Which smbj client (TCP socket) an operation rides on — see class doc. */
    enum class Channel { GENERAL, READS, PLAYBACK }

    /** Directory listings: walks, browse, imports. */
    private val generalClient: SMBClient by lazy { newClient() }

    /** Non-playback file reads (tag / duration / art / lyric probes) — separate socket. */
    private val readsClient: SMBClient by lazy { newClient() }

    /** Playback streaming only — separate socket, see class doc. */
    private val playbackClient: SMBClient by lazy { newClient() }

    private fun clientFor(channel: Channel): SMBClient = when (channel) {
        Channel.GENERAL -> generalClient
        Channel.READS -> readsClient
        Channel.PLAYBACK -> playbackClient
    }

    private const val TAG = "SmbClient"

    internal class CacheEntry(
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
        @Volatile var lastUsedMs: Long,
    ) {
        /** Outstanding [ShareLease]s (open file handles). Entry must not be
         *  proactively evicted while > 0. */
        val activeLeases = AtomicInteger(0)
    }

    /**
     * Pins a cache entry while a file handle is open on its [disk]. [close] releases
     * the pin and refreshes the idle timer (a just-finished read is proof of life).
     */
    class ShareLease internal constructor(
        val disk: DiskShare,
        private val entry: CacheEntry,
    ) : Closeable {
        override fun close() {
            entry.activeLeases.updateAndGet { if (it > 0) it - 1 else 0 }
            entry.lastUsedMs = System.currentTimeMillis()
        }
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** Per-key connect locks — see class doc on thread-safety. */
    private val locks = ConcurrentHashMap<String, Any>()

    /** A cached entry idle at least this long gets an SMB2 ECHO before reuse. The local
     *  socket state ([Connection.isConnected]) can't see a link the NAS dropped silently
     *  (reboot, power loss, NAT/Wi-Fi timeout), and neither it nor [DiskShare.isConnected]
     *  (a local "we closed it" flag) can see a packet reader that has died. One ECHO round
     *  trip (~ms on a LAN) proves both ends are alive, so a healthy idle connection is reused
     *  instead of torn down, and a dead one costs [ECHO_TIMEOUT_MS] instead of a 30 s
     *  stall on the first real request. Entries with live leases skip the probe: their open
     *  handles are the traffic, and a failed read on them evicts via [close]. */
    private const val PROBE_AFTER_IDLE_MS = 15_000L

    /** How long a liveness ECHO may take before the connection is judged dead. */
    private const val ECHO_TIMEOUT_MS = 3_000L

    /** The ECHO after an operation TIMED OUT ([close] with a cause): the link was busy enough
     *  for a 30 s request to expire, so the ECHO may queue behind in-flight responses — give
     *  it longer than the idle probe before calling the connection dead. */
    private const val LIVENESS_ECHO_TIMEOUT_MS = 5_000L

    private fun key(shareId: String, channel: Channel) = when (channel) {
        Channel.GENERAL -> shareId
        Channel.READS -> "$shareId#read"
        Channel.PLAYBACK -> "$shareId#play"
    }

    private fun lockFor(key: String): Any = locks.computeIfAbsent(key) { Any() }

    /**
     * Open or reuse a [DiskShare] for [shareId]. Throws on connect / auth / share
     * mount failure — call site should wrap in `runCatching`.
     *
     * One-shot ops (list, browse) on [Channel.GENERAL]. For a long-lived file handle use
     * [lease] so the entry can't be evicted underneath it.
     */
    fun share(
        shareId: String,
        host: String,
        port: Int,
        shareName: String,
        creds: SmbCreds?,
        playback: Boolean = false,
    ): DiskShare = entryFor(shareId, host, port, shareName, creds, if (playback) Channel.PLAYBACK else Channel.GENERAL).share

    /** A file-handle share, pinned against proactive eviction until the returned
     *  [ShareLease] is closed. Hold it for exactly the life of an open file handle.
     *  Non-playback leases ride [Channel.READS], never the listings' socket. */
    fun lease(
        shareId: String,
        host: String,
        port: Int,
        shareName: String,
        creds: SmbCreds?,
        playback: Boolean = false,
    ): ShareLease {
        val channel = leaseChannel(playback)
        val k = key(shareId, channel)
        synchronized(lockFor(k)) {
            val entry = entryForLocked(shareId, k, host, port, shareName, creds, channel)
            entry.activeLeases.incrementAndGet()
            return ShareLease(entry.share, entry)
        }
    }

    /** The channel [lease] uses — what a lease holder passes to [close]. */
    fun leaseChannel(playback: Boolean): Channel = if (playback) Channel.PLAYBACK else Channel.READS

    private fun entryFor(
        shareId: String,
        host: String,
        port: Int,
        shareName: String,
        creds: SmbCreds?,
        channel: Channel,
    ): CacheEntry {
        val k = key(shareId, channel)
        synchronized(lockFor(k)) {
            return entryForLocked(shareId, k, host, port, shareName, creds, channel)
        }
    }

    /** Must hold [lockFor] (k). Refuses a share that is being deleted ([SourceLocks.isRemoved]):
     *  a retag, probe retry, lyric sidecar or art read racing the delete would otherwise open a
     *  fresh session + connection for it that nothing ever closes. [closeAllFor] drops entries
     *  under the same lock AFTER the tombstone is set, so none can slip in between. */
    private fun entryForLocked(
        shareId: String,
        k: String,
        host: String,
        port: Int,
        shareName: String,
        creds: SmbCreds?,
        channel: Channel,
    ): CacheEntry {
        checkNotRemoved(shareId)
        val now = System.currentTimeMillis()
        cache[k]?.let { entry ->
            var dead = !entry.connection.isConnected
            if (!dead && entry.share.isConnected) {
                val idle = now - entry.lastUsedMs >= PROBE_AFTER_IDLE_MS
                val busy = entry.activeLeases.get() > 0
                if (!idle || busy || answersEcho(entry.connection)) {
                    entry.lastUsedMs = now
                    return entry
                }
                dead = true
            }
            // Socket closed, share closed, or no ECHO reply: drop it and reopen below. The
            // connection is FORCE-closed when it's dead — a plain close() only releases our
            // lease on smbj's pooled connection, and while another share on the same host
            // still holds one, SMBClient.connect would hand the dead socket straight back.
            // Forcing it shut makes every sharer reconnect. A dead-socket entry is closed even
            // if leases are outstanding: their handles are already doomed, and the lease
            // release on a removed entry is harmless (counter only).
            closeEntry(entry, force = dead)
            cache.remove(k)
        }
        val connection = clientFor(channel).connect(host, port)
        val auth = creds?.let {
            AuthenticationContext(it.user, it.password.toCharArray(), it.domain)
        } ?: AuthenticationContext.guest()
        // A failed auth or mount must release what was already opened: smbj refcounts
        // the pooled connection, so an unclosed one here is a leaked lease that keeps
        // the socket (and its transport thread) alive with nothing ever closing it.
        var session: Session? = null
        val disk = try {
            session = connection.authenticate(auth)
            session.connectShare(shareName) as DiskShare
        } catch (t: Throwable) {
            session?.let { s -> runCatching { s.close() } }
            runCatching { connection.close() }
            throw t
        }
        val entry = CacheEntry(connection, session!!, disk, System.currentTimeMillis())
        cache[k] = entry
        return entry
    }

    /** Throws when [shareId] is being deleted — see [entryForLocked]. An IOException, so
     *  callers treat it like any other failed open. */
    internal fun checkNotRemoved(shareId: String) {
        if (SourceLocks.isRemoved(shareId)) throw java.io.IOException("SMB share $shareId was removed")
    }

    /** [force] = the connection is dead (or must be treated as dead): shut the socket FIRST,
     *  for every share pooled on it, so the tree-disconnect / logoff below fail fast instead
     *  of each waiting 30 s for a reply that can't come. Graceful (share removed) releases
     *  only our lease on smbj's pooled connection — other shares on the host keep using it. */
    private fun closeEntry(entry: CacheEntry, force: Boolean) {
        if (force) runCatching { entry.connection.close(true) }
        runCatching { entry.share.close() }
        runCatching { entry.session.close() }
        if (!force) runCatching { entry.connection.close() }
    }

    /** One SMB2 ECHO with a short deadline — true if the server answered at all. Sent on the
     *  connection (session id 0), which MS-SMB2 allows unsigned for ECHO. */
    private fun answersEcho(connection: Connection, timeoutMs: Long = ECHO_TIMEOUT_MS): Boolean = try {
        connection.send<SMB2Packet>(SMB2Echo(connection.negotiatedProtocol.dialect))
            .get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        true
    } catch (t: Throwable) {
        if (t is InterruptedException) Thread.currentThread().interrupt()
        false
    }

    /**
     * Wizard "Test Connection" step. Connects → authenticates → mounts share →
     * lists root. Returns [Result.success] on full success; [Result.failure] with
     * the underlying exception on any rung.
     *
     * Safe to run against a host with live cached connections: smbj refcounts the
     * pooled connection (Pooled.lease/release), so the `connection.close()` below
     * only drops the lease this test took — it never tears down the transport a
     * cached share (or playback) is using.
     */
    fun test(
        host: String,
        port: Int,
        shareName: String,
        creds: SmbCreds?,
    ): Result<Unit> = runCatching {
        val connection = generalClient.connect(host, port)
        try {
            val auth = creds?.let {
                AuthenticationContext(it.user, it.password.toCharArray(), it.domain)
            } ?: AuthenticationContext.guest()
            val session = connection.authenticate(auth)
            try {
                (session.connectShare(shareName) as DiskShare).use { it.list("") }
            } finally {
                runCatching { session.close() }
            }
        } finally {
            runCatching { connection.close() }
        }
        Unit
    }.recoverCatching { e -> throw Exception(friendlyError(e, host, port, shareName), e) }

    /**
     * Map raw smbj / socket exceptions to a human message. The raw message is
     * often just the host string ("192.168.1.45") or an opaque NT-status enum,
     * which tells the user nothing. We key off message/status text since the
     * concrete exception types live across several smbj packages.
     */
    private fun friendlyError(e: Throwable, host: String, port: Int, shareName: String): String {
        val raw = (e.message ?: e::class.simpleName.orEmpty())
        val text = "$raw ${e.cause?.message.orEmpty()}".uppercase()
        return when {
            "LOGON_FAILURE" in text || "ACCESS_DENIED" in text || "PASSWORD" in text ->
                "Login rejected — check username and password"
            "BAD_NETWORK_NAME" in text || "NETWORK_NAME" in text ->
                "Share \"$shareName\" not found on $host"
            "TIMEOUT" in text || "TIMED OUT" in text ->
                "Timed out reaching $host:$port — host up but not answering SMB?"
            "CONNECTION REFUSED" in text || "REFUSED" in text ->
                "$host:$port refused connection — is SMB enabled on that port?"
            "UNREACHABLE" in text || "NO ROUTE" in text || "ENETUNREACH" in text ->
                "$host unreachable — check the IP and that you're on the same network"
            "ECONNRESET" in text || "RESET" in text ->
                "Connection reset by $host — try SMB port 445 (or 139 for legacy)"
            else -> "Couldn't connect to $host:$port — ${raw.ifBlank { e::class.simpleName }}"
        }
    }

    /** True when [t] reads as a transport/session failure (dead connection) rather than a
     *  per-file SMB status. Per-file statuses (NOT_FOUND, ACCESS_DENIED, SHARING_VIOLATION)
     *  must NOT evict the shared connection. We key off message text since smbj's status
     *  enums live across packages and the socket layer throws plain IOExceptions.
     *  Shared by the playback open (SmbDataSource) and the import walk (SmbImporter). */
    internal fun isConnectionError(t: Throwable): Boolean {
        // Socket-layer / smbj-transport types first: a connect to a powered-off NAS throws
        // e.g. NoRouteToHostException "…EHOSTUNREACH (No route to host)", which none of the
        // text keys below match — and the walk's circuit breaker must see it as a dead host.
        var c: Throwable? = t
        while (c != null) {
            if (c is java.net.SocketException || c is java.net.SocketTimeoutException ||
                c is java.net.UnknownHostException || c is java.io.EOFException ||
                c is java.util.concurrent.TimeoutException ||
                c is com.hierynomus.protocol.transport.TransportException
            ) return true
            c = c.cause
        }
        val text = buildString {
            var e: Throwable? = t
            while (e != null) { append(e.message ?: e::class.simpleName.orEmpty()); append(' '); e = e.cause }
        }.uppercase()
        return "TIMEOUT" in text || "TIMED OUT" in text || "SOCKET" in text ||
            "CONNECTION" in text || "ECONNRESET" in text || "RESET" in text ||
            "BROKEN PIPE" in text || "EOF" in text || "TRANSPORT" in text ||
            "UNREACH" in text || "NO ROUTE" in text ||
            "USER_SESSION_DELETED" in text || "NETWORK_NAME_DELETED" in text
    }

    /** True when [t] is a request/socket TIMEOUT: the reply didn't come in time, which on a
     *  busy link doesn't mean the link is dead (see [keepAfterFailure]). */
    internal fun isTimeout(t: Throwable): Boolean {
        var c: Throwable? = t
        while (c != null) {
            if (c is java.util.concurrent.TimeoutException || c is java.net.SocketTimeoutException) return true
            c = c.cause
        }
        return false
    }

    /** True when [t] is smbj refusing an op because the handle it used was already closed
     *  ("DiskShare has already been closed") — typically a concurrent evict dropped the share
     *  the op had just acquired. Not a connection failure: re-acquiring the share fixes it. */
    internal fun isClosedHandleError(t: Throwable): Boolean {
        var c: Throwable? = t
        while (c != null) {
            if (c.message?.contains("has already been closed", ignoreCase = true) == true) return true
            c = c.cause
        }
        return false
    }

    /**
     * Whether an error-path [close] after [cause] should KEEP the connection. Pure, for tests.
     *  - Socket already down → never.
     *  - A closed handle ([isClosedHandleError]) → keep: only our share handle is gone, the next
     *    [share]/[lease] re-mounts on the live socket.
     *  - A timeout ([isTimeout]) → keep iff the connection still answers an ECHO: the link is
     *    busy, not dead. Force-closing it would fail EVERY other request in flight on it (the
     *    walk's lists included, as "DiskShare has already been closed") — one slow reply used
     *    to cascade into an incomplete walk that way. Only this one operation fails.
     *  - Anything else connection-level (reset, EOF, session deleted…) → evict.
     */
    internal fun keepAfterFailure(
        cause: Throwable,
        connected: Boolean,
        shareOpen: Boolean,
        answersEcho: () -> Boolean,
    ): Boolean = when {
        !connected -> false
        isClosedHandleError(cause) -> true
        isTimeout(cause) -> shareOpen && answersEcho()
        else -> false
    }

    /** Evict one cache entry after a CONNECTION-level failure (error-path reconnect).
     *  [channel] selects which socket's entry — evicting one never touches another's. The
     *  connection is force-closed (see [closeEntry]) so a zombie shared with another share on
     *  the same host can't be handed back by smbj's pool.
     *
     *  [failed] is the [DiskShare] the failing operation used. When given, the entry is only
     *  evicted if it is still that share: a concurrent op on the same dead connection that
     *  fails a moment later must not tear down the fresh connection another op just made.
     *
     *  [cause] is the failure. With it, a connection that is merely BUSY (a timeout, but the
     *  ECHO answers) or only lost a handle is kept — see [keepAfterFailure]. The ECHO runs
     *  outside the key lock, so it never holds up other opens on this share.
     *
     *  Returns true when the connection [failed] used is gone (evicted now, or already replaced),
     *  false when it was kept because it is alive. */
    fun close(
        shareId: String,
        channel: Channel = Channel.GENERAL,
        failed: DiskShare? = null,
        cause: Throwable? = null,
    ): Boolean {
        val k = key(shareId, channel)
        val entry = cache[k] ?: return true
        if (failed != null && entry.share !== failed) return true
        if (cause != null && keepAfterFailure(cause, entry.connection.isConnected, entry.share.isConnected) {
                answersEcho(entry.connection, LIVENESS_ECHO_TIMEOUT_MS)
            }
        ) {
            android.util.Log.i(TAG, "$channel link alive after ${cause.javaClass.simpleName} — connection kept")
            return false
        }
        synchronized(lockFor(k)) {
            if (cache[k] === entry) {
                cache.remove(k)
                closeEntry(entry, force = true)
                android.util.Log.w(TAG, "$channel connection evicted after ${cause?.javaClass?.simpleName ?: "error"}")
            }
        }
        return true
    }

    /** Share removed: take its entry out of the cache (under the key lock, so nothing can
     *  reuse it), then close it OUTSIDE the lock. A live link closes gracefully — only our lease
     *  on smbj's pooled connection is released, other shares on the host keep it. A dead link
     *  (socket closed, or no ECHO reply within [ECHO_TIMEOUT_MS]) is force-closed socket first:
     *  a graceful tree-disconnect + logoff there each wait out the 30 s request timeout, and
     *  only the forced close fails the requests still blocked on it. */
    private fun release(shareId: String, channel: Channel) {
        val k = key(shareId, channel)
        val entry = synchronized(lockFor(k)) { cache.remove(k) } ?: return
        closeEntry(entry, force = releaseForce(entry.connection.isConnected) { answersEcho(entry.connection) })
    }

    /** Whether [release] must force-close: the socket is already down, or it doesn't answer. */
    internal fun releaseForce(connected: Boolean, answersEcho: () -> Boolean): Boolean =
        !connected || !answersEcho()

    /** Tear down every channel's entry for a share — call when the user deletes the share.
     *  Blocks for up to an ECHO timeout per entry on a dead NAS: call on IO, and don't wait on it. */
    fun closeAllFor(shareId: String) {
        Channel.entries.forEach { release(shareId, it) }
    }

    @Synchronized
    fun closeAll() {
        cache.values.forEach { closeEntry(it, force = false) }
        cache.clear()
    }

    /** Currently SmbProtocol is metadata only — smbj negotiates the best dialect
     *  automatically. Reserved for an explicit "force SMB2-only" toggle later. */
    @Suppress("UNUSED_PARAMETER")
    fun preferProtocol(p: SmbProtocol) { /* TODO Phase 7.x: pin dialect on SmbConfig */ }
}
