package com.example.dink_smb_player.data.source.smb

import com.example.dink_smb_player.data.model.SmbProtocol
import com.example.dink_smb_player.data.prefs.SmbCreds
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
 * TWO smbj clients, not one: smbj pools connections per host:port inside a client
 * (SMBClient.connectionTable, refcounted via Pooled.lease/release), so two cache
 * keys under one client would still share a single TCP socket + transport thread.
 * Playback gets its OWN [SMBClient] — its own socket — so an import walk, art
 * fetch, or browse can never contend with or tear down the stream's transport.
 * Everything else (walks, browse, tag/duration/art reads) shares [generalClient].
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
            .withSoTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return SMBClient(cfg)
    }

    /** Walks, browse, imports, tag/duration/art reads. */
    private val generalClient: SMBClient by lazy { newClient() }

    /** Playback streaming only — separate socket, see class doc. */
    private val playbackClient: SMBClient by lazy { newClient() }

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

    /** A NAS silently drops an idle SMB session, but smbj's [DiskShare.isConnected]
     *  reflects only the LOCAL socket state — a server-side idle-drop still reads as
     *  "connected", so the next operation blocks to the 30 s soTimeout instead of
     *  reconnecting. So we treat a cached share that's been idle longer than this as
     *  stale and reconnect proactively — cheap (no round-trip), and it turns the first
     *  read after an idle period into one ~600 ms reconnect instead of a 30 s-per-read
     *  stall. Entries with live leases are exempt: an open handle refreshes
     *  [CacheEntry.lastUsedMs] on release, and closing under it kills the stream. */
    private const val STALE_AFTER_IDLE_MS = 60_000L

    private fun key(shareId: String, playback: Boolean) =
        if (playback) "$shareId#play" else shareId

    private fun lockFor(key: String): Any = locks.computeIfAbsent(key) { Any() }

    /**
     * Open or reuse a [DiskShare] for [shareId]. Throws on connect / auth / share
     * mount failure — call site should wrap in `runCatching`.
     *
     * One-shot ops (list, browse). For a long-lived file handle use [lease] so the
     * entry can't be evicted underneath it.
     */
    fun share(
        shareId: String,
        host: String,
        port: Int,
        shareName: String,
        creds: SmbCreds?,
        playback: Boolean = false,
    ): DiskShare = entryFor(shareId, host, port, shareName, creds, playback).share

    /** [share], but pins the entry against proactive eviction until the returned
     *  [ShareLease] is closed. Hold it for exactly the life of an open file handle. */
    fun lease(
        shareId: String,
        host: String,
        port: Int,
        shareName: String,
        creds: SmbCreds?,
        playback: Boolean = false,
    ): ShareLease {
        val k = key(shareId, playback)
        synchronized(lockFor(k)) {
            val entry = entryForLocked(k, host, port, shareName, creds, playback)
            entry.activeLeases.incrementAndGet()
            return ShareLease(entry.share, entry)
        }
    }

    private fun entryFor(
        shareId: String,
        host: String,
        port: Int,
        shareName: String,
        creds: SmbCreds?,
        playback: Boolean,
    ): CacheEntry {
        val k = key(shareId, playback)
        synchronized(lockFor(k)) {
            return entryForLocked(k, host, port, shareName, creds, playback)
        }
    }

    /** Must hold [lockFor] (k). */
    private fun entryForLocked(
        k: String,
        host: String,
        port: Int,
        shareName: String,
        creds: SmbCreds?,
        playback: Boolean,
    ): CacheEntry {
        val now = System.currentTimeMillis()
        cache[k]?.let { entry ->
            val fresh = now - entry.lastUsedMs < STALE_AFTER_IDLE_MS
            val busy = entry.activeLeases.get() > 0
            if (entry.share.isConnected && (fresh || busy)) {
                entry.lastUsedMs = now
                return entry
            }
            // Locally closed, OR idle long enough (with no live handles) that the
            // server may have dropped the session — drop it and reopen below rather
            // than risk a 30 s blocked read. A dead-socket entry is closed even if
            // leases are outstanding: their handles are already doomed, and the
            // lease release on a removed entry is harmless (counter only).
            closeEntry(entry)
            cache.remove(k)
        }
        val client = if (playback) playbackClient else generalClient
        val connection = client.connect(host, port)
        val auth = creds?.let {
            AuthenticationContext(it.user, it.password.toCharArray(), it.domain)
        } ?: AuthenticationContext.guest()
        val session = connection.authenticate(auth)
        val disk = session.connectShare(shareName) as DiskShare
        val entry = CacheEntry(connection, session, disk, System.currentTimeMillis())
        cache[k] = entry
        return entry
    }

    private fun closeEntry(entry: CacheEntry) {
        runCatching { entry.share.close() }
        runCatching { entry.session.close() }
        runCatching { entry.connection.close() }
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

    /** Evict one cache entry (error-path reconnect). [playback] selects the stream's
     *  entry vs the general one — evicting one never touches the other's socket. */
    fun close(shareId: String, playback: Boolean = false) {
        val k = key(shareId, playback)
        synchronized(lockFor(k)) {
            val entry = cache.remove(k) ?: return
            closeEntry(entry)
        }
    }

    /** Tear down BOTH entries for a share — call when the user deletes the share. */
    fun closeAllFor(shareId: String) {
        close(shareId, playback = false)
        close(shareId, playback = true)
    }

    @Synchronized
    fun closeAll() {
        cache.values.forEach { closeEntry(it) }
        cache.clear()
    }

    /** Currently SmbProtocol is metadata only — smbj negotiates the best dialect
     *  automatically. Reserved for an explicit "force SMB2-only" toggle later. */
    @Suppress("UNUSED_PARAMETER")
    fun preferProtocol(p: SmbProtocol) { /* TODO Phase 7.x: pin dialect on SmbConfig */ }
}
