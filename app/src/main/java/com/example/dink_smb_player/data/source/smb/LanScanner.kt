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
        val discoveryListener = nsd?.let { manager ->
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    manager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = resolved.host ?: return
                            val addr = host.hostAddress ?: return
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
                        }
                    })
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
