package com.example.dink_smb_player.data.source.smb

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

data class DiscoveredHost(
    val name: String,
    val address: String,
    val port: Int = 445,
    /** Source of discovery — "mDNS" or "scan". UI uses this to badge entries. */
    val via: String,
)

/**
 * Two-prong LAN host discovery for SMB.
 *
 * 1. NsdManager browses `_smb._tcp.` advertisements (Synology / QNAP / TrueNAS /
 *    Samba w/ Avahi / macOS — anything that does mDNS). Cheap and accurate.
 * 2. TCP-445 sweep across the device's /24 subnet (32-concurrent connect probes
 *    with a 200 ms per-host timeout). Picks up Windows shares + bare smbj boxes
 *    that don't advertise mDNS.
 *
 * Results stream as the union grows. Caller cancels by collecting on a job that
 * outlives a UX timeout (~3-4 s of the slowest probe).
 */
object LanScanner {

    private const val SMB_SERVICE_TYPE = "_smb._tcp."

    fun discover(context: Context, scope: CoroutineScope): Flow<List<DiscoveredHost>> = callbackFlow {
        val ctx = context.applicationContext
        val results = LinkedHashMap<String, DiscoveredHost>()
        val resultsLock = Any()

        fun emitSnapshot() {
            val snapshot = synchronized(resultsLock) { results.values.toList() }
            trySend(snapshot)
        }

        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager
        // Below API 34 NsdManager runs ONE resolve at a time: a second resolveService while one
        // is in flight fails at once with FAILURE_ALREADY_ACTIVE — and onServiceFound fires for
        // every host in a burst, so only the first mDNS host was ever resolved. Resolves go
        // through a serial queue instead (on 34+ that costs a few ms per host, nothing more).
        val resolveQueue = nsd?.let { manager ->
            SerialQueue<NsdServiceInfo> { info, done ->
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = done()
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        try {
                            val addr = resolved.host?.hostAddress ?: return
                            synchronized(resultsLock) {
                                if (!results.containsKey(addr)) {
                                    results[addr] = DiscoveredHost(
                                        name = resolved.serviceName ?: addr,
                                        address = addr,
                                        port = if (resolved.port > 0) resolved.port else 445,
                                        via = "mDNS",
                                    )
                                }
                            }
                            emitSnapshot()
                        } finally {
                            done()
                        }
                    }
                })
            }
        }
        // The same service is often reported more than once (per interface / address family).
        val queuedServices = HashSet<String>()
        val discoveryListener = nsd?.let { manager ->
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    val fresh = synchronized(queuedServices) { queuedServices.add(info.serviceName ?: return) }
                    if (fresh) resolveQueue?.submit(info)
                }
            }.also { runCatching { manager.discoverServices(SMB_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, it) } }
        }

        // Subnet sweep — bounded fan-out so cheap routers don't choke.
        val sweepJob = scope.launch(Dispatchers.IO) {
            val prefix = localSubnetPrefix(ctx) ?: return@launch
            val sem = Semaphore(32)
            val probes = (1..254).map { last ->
                launch {
                    sem.withPermit {
                        val ip = "$prefix.$last"
                        if (tcpProbe(ip, 445, timeoutMs = 200)) {
                            var added = false
                            synchronized(resultsLock) {
                                if (!results.containsKey(ip)) {
                                    results[ip] = DiscoveredHost(name = ip, address = ip, port = 445, via = "scan")
                                    added = true
                                }
                            }
                            if (added) emitSnapshot()
                        }
                    }
                }
            }
            probes.joinAll()
        }

        emitSnapshot()

        awaitClose {
            resolveQueue?.close()
            discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            sweepJob.cancel()
        }
    }

    private fun tcpProbe(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    /** Returns the device's IPv4 /24 prefix (e.g., "192.168.1") or null if no
     *  routable IPv4 interface is active. We deliberately ignore IPv6 — SMB is
     *  almost always advertised on v4 LANs. */
    private fun localSubnetPrefix(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val active = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(active) ?: return null
        for (la in lp.linkAddresses) {
            val ia = la.address
            if (ia is Inet4Address && !ia.isLoopbackAddress) {
                val parts = ia.hostAddress?.split('.') ?: continue
                if (parts.size == 4) return "${parts[0]}.${parts[1]}.${parts[2]}"
            }
        }
        return null
    }
}

/**
 * Runs [start] for one item at a time, in submission order. [start] gets a `done` callback
 * that must be invoked when the item finishes (success or failure) — only then does the next
 * item start. A second `done` for the same item is ignored; a [start] that throws counts as
 * done. [close] drops everything still queued (the in-flight item just finishes).
 */
internal class SerialQueue<T>(private val start: (item: T, done: () -> Unit) -> Unit) {
    private companion object {
        const val RUNNING = 0
        const val DETACHED = 1
        const val DONE = 2
    }

    private val lock = Any()
    private val pending = ArrayDeque<T>()
    private var busy = false
    private var closed = false

    fun submit(item: T) {
        synchronized(lock) {
            if (closed) return
            if (busy) { pending.addLast(item); return }
            busy = true
        }
        run(item)
    }

    fun close() {
        synchronized(lock) { closed = true; pending.clear() }
    }

    private fun run(first: T) {
        // Iterative, so a [start] that finishes synchronously doesn't recurse once per queued
        // item. Per-item state: RUNNING while [start] is on this stack, DETACHED once it has
        // returned with the item still in flight, DONE after `done`. If `done` lands before
        // [start] returns, this loop advances the queue; otherwise the `done` callback does.
        var item: T = first
        while (true) {
            val state = AtomicInteger(RUNNING)
            val done: () -> Unit = {
                if (!state.compareAndSet(RUNNING, DONE) && state.compareAndSet(DETACHED, DONE)) {
                    next()?.let { run(it) }
                }
            }
            try { start(item, done) } catch (_: Throwable) { state.compareAndSet(RUNNING, DONE) }
            if (state.compareAndSet(RUNNING, DETACHED)) return
            item = next() ?: return
        }
    }

    private fun next(): T? = synchronized(lock) {
        val n = if (closed) null else pending.removeFirstOrNull()
        if (n == null) busy = false
        n
    }
}
