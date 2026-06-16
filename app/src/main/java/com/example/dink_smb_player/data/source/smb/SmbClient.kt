package com.example.dink_smb_player.data.source.smb

import com.example.dink_smb_player.data.model.SmbProtocol
import com.example.dink_smb_player.data.prefs.SmbCreds
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.util.concurrent.ConcurrentHashMap

/**
 * smbj wrapper that keeps one [DiskShare] per share id alive across operations.
 *
 * smbj's [SMBClient] is heavy — it spins up a transport thread and an SMB packet
 * decoder per [Connection]. Tearing it down between every list/read on a TV would
 * stall the UI for ~600 ms each time. Instead we cache per-share, and only
 * tear down when the share is removed or the process exits.
 *
 * Thread-safety: all public functions block on smbj internals and must be called
 * off the main thread (Dispatchers.IO).
 *
 * Auth: [SmbCreds] with null user → guest auth via [AuthenticationContext.guest].
 */
object SmbClient {

    private val client: SMBClient by lazy {
        val cfg = SmbConfig.builder()
            // 30 s is plenty for LAN; default 60 s leaves the wizard hanging if the
            // host is unreachable. The Test-Connection step should fail fast.
            .withTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .withSoTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        SMBClient(cfg)
    }

    private data class CacheEntry(val connection: Connection, val session: Session, val share: DiskShare)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Open or reuse a [DiskShare] for [shareId]. Throws on connect / auth / share
     * mount failure — call site should wrap in `runCatching`.
     */
    @Synchronized
    fun share(
        shareId: String,
        host: String,
        port: Int,
        shareName: String,
        creds: SmbCreds?,
    ): DiskShare {
        cache[shareId]?.let { entry ->
            if (entry.share.isConnected) return entry.share
            // Stale — drop it and reopen below.
            runCatching { entry.share.close() }
            runCatching { entry.session.close() }
            runCatching { entry.connection.close() }
            cache.remove(shareId)
        }
        val connection = client.connect(host, port)
        val auth = creds?.let {
            AuthenticationContext(it.user, it.password.toCharArray(), it.domain)
        } ?: AuthenticationContext.guest()
        val session = connection.authenticate(auth)
        val disk = session.connectShare(shareName) as DiskShare
        cache[shareId] = CacheEntry(connection, session, disk)
        return disk
    }

    /**
     * Wizard "Test Connection" step. Connects → authenticates → mounts share →
     * lists root. Returns [Result.success] on full success; [Result.failure] with
     * the underlying exception on any rung.
     *
     * Uses a throwaway connection so a successful test doesn't pollute the cache
     * before the share has even been saved.
     */
    fun test(
        host: String,
        port: Int,
        shareName: String,
        creds: SmbCreds?,
    ): Result<Unit> = runCatching {
        val connection = client.connect(host, port)
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

    @Synchronized
    fun close(shareId: String) {
        val entry = cache.remove(shareId) ?: return
        runCatching { entry.share.close() }
        runCatching { entry.session.close() }
        runCatching { entry.connection.close() }
    }

    @Synchronized
    fun closeAll() {
        cache.values.forEach { entry ->
            runCatching { entry.share.close() }
            runCatching { entry.session.close() }
            runCatching { entry.connection.close() }
        }
        cache.clear()
    }

    /** Currently SmbProtocol is metadata only — smbj negotiates the best dialect
     *  automatically. Reserved for an explicit "force SMB2-only" toggle later. */
    @Suppress("UNUSED_PARAMETER")
    fun preferProtocol(p: SmbProtocol) { /* TODO Phase 7.x: pin dialect on SmbConfig */ }
}
