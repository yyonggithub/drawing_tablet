package com.drawingtablet.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val TAG = "ServiceDiscovery"
private const val SERVICE_TYPE = "_drawingtablet._udp."

// ── Self-healing timing ────────────────────────────────────────────────────
// Android's NSD is edge-triggered: after the server restarts (goodbye +
// re-announce), the "found" event may arrive tens of seconds late or never
// (measured 0–30s+ on a Xiaomi Pad 8), which left the server list empty and
// the UI spinning until some unrelated event woke the stack up. Restarting
// the browse re-scans and re-delivers the edge, so the recovery delay is
// bounded by a single refresh cycle.
private const val REFRESH_EMPTY_MS = 5_000L   // no known server: re-scan fast
private const val REFRESH_KNOWN_MS = 20_000L  // server known: keep cache fresh
private const val REDISCOVER_DELAY_MS = 250L  // gap between stop and start
private const val STOP_FALLBACK_MS = 3_000L   // onDiscoveryStopped never came
private const val RESOLVE_RETRY_MS = 1_000L
private const val RESOLVE_TIMEOUT_MS = 10_000L // resolve callback never came
private const val RESOLVE_MAX_ATTEMPTS = 3

/**
 * A discovered Drawing Tablet server on the local network.
 */
data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int
)

/**
 * Discovers Drawing Tablet servers via mDNS (NSD).
 *
 * Call [start] when the app is ready to browse, and [stop] when it is no
 * longer needed.  Observed servers are exposed via the [servers] flow.
 */
class ServiceDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers

    /** All NSD calls are sequenced on the main looper. */
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var stopped = true
    /** True while a browse we started is believed to be running. */
    @Volatile private var browseActive = false

    /** One shared executor: resolve callbacks must not leak a thread each. */
    private var resolveExecutor = Executors.newSingleThreadExecutor()

    /** Names with an in-flight resolve, so refreshes never race a resolve. */
    private val resolving = Collections.synchronizedSet(mutableSetOf<String>())
    private val resolveAttempts = ConcurrentHashMap<String, Int>()

    private val refreshRunnable = Runnable { refreshBrowse() }

    fun start() {
        stopped = false
        if (resolveExecutor.isShutdown) {
            resolveExecutor = Executors.newSingleThreadExecutor()
        }
        discover()
        scheduleRefresh()
        Log.i(TAG, "Service discovery started")
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        resolving.clear()
        browseActive = false
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (_: Exception) {}
        resolveExecutor.shutdown()
        Log.i(TAG, "Service discovery stopped")
    }

    /**
     * mDNS advertises the host on every interface it is reachable from — the
     * LAN address, but also docker/loopback bridges and IPv6 link-local — and
     * only the LAN address is usable. Prefer an address on the same subnet as
     * one of our own interfaces, then any routable IPv4 (the server binds
     * IPv4), and only then IPv6.
     */
    private fun pickAddress(addresses: List<InetAddress>): InetAddress? {
        val localNetworks = localIPv4Networks()
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        val ipv6 = addresses.filterIsInstance<Inet6Address>()

        return ipv4.firstOrNull { addr ->
            localNetworks.any { (network, mask) -> (toInt(addr) and mask) == network }
        }
            ?: ipv4.firstOrNull { !it.isLinkLocalAddress && !it.isLoopbackAddress }
            ?: ipv4.firstOrNull()
            ?: ipv6.firstOrNull { !it.isLinkLocalAddress && !it.isLoopbackAddress }
            ?: ipv6.firstOrNull()
            ?: addresses.firstOrNull()
    }

    /** (network, mask) pairs for this device's non-loopback IPv4 interfaces. */
    private fun localIPv4Networks(): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ifAddr in nif.interfaceAddresses) {
                    val addr = ifAddr.address
                    if (addr is Inet4Address) {
                        val prefix = ifAddr.networkPrefixLength.toInt()
                        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
                        result.add((toInt(addr) and mask) to mask)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate local interfaces", e)
        }
        return result
    }

    private fun toInt(addr: InetAddress): Int =
        ByteBuffer.wrap(addr.address).order(ByteOrder.BIG_ENDIAN).int

    // ── Browse state machine ───────────────────────────────────────────────

    private fun discover() {
        if (stopped || browseActive) return
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            browseActive = true
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices failed: $e")
            handler.postDelayed({ discover() }, REDISCOVER_DELAY_MS)
        }
    }

    /**
     * Re-arm the periodic refresh. Fast while no server is known (that is the
     * stuck state we are healing), slower once one is.
     */
    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        val delay =
            if (_servers.value.isEmpty()) REFRESH_EMPTY_MS else REFRESH_KNOWN_MS
        handler.postDelayed(refreshRunnable, delay)
    }

    /**
     * Restart the browse. This is the only reliable way to recover a "found"
     * event the stack never delivered: a fresh browse re-scans the network.
     */
    private fun refreshBrowse() {
        if (stopped) return
        Log.d(TAG, "Refreshing browse (${_servers.value.size} server(s) known)")
        browseActive = false
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "stopServiceDiscovery failed during refresh: $e")
        }
        // Fallback for stacks that never deliver onDiscoveryStopped.
        handler.postDelayed({ discover() }, STOP_FALLBACK_MS)
        scheduleRefresh()
    }

    // ── Resolve with bounded retries ───────────────────────────────────────

    private fun resolveOrRetry(serviceInfo: NsdServiceInfo, attempt: Int) {
        val name = serviceInfo.serviceName
        if (stopped || attempt > RESOLVE_MAX_ATTEMPTS) {
            if (attempt > RESOLVE_MAX_ATTEMPTS) {
                Log.w(TAG, "Giving up on '$name' after $RESOLVE_MAX_ATTEMPTS resolve attempts")
                resolving.remove(name)
                resolveAttempts.remove(name)
            }
            return
        }
        if (!resolving.add(name)) {
            Log.d(TAG, "Resolve already in flight for '$name'")
            return
        }
        // Watchdog: if the stack never calls back, free the slot so the next
        // browse refresh can try again instead of blocking forever.
        handler.postDelayed({
            if (resolving.remove(name)) {
                Log.w(TAG, "Resolve timed out for '$name'")
            }
        }, RESOLVE_TIMEOUT_MS)

        try {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(serviceInfo, resolveExecutor,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        resolving.remove(name)
                        val tries = resolveAttempts.merge(name, 1, Int::plus) ?: 1
                        Log.w(
                            TAG,
                            "Resolve failed for '$name' " +
                                "(attempt $tries/$RESOLVE_MAX_ATTEMPTS): $errorCode"
                        )
                        handler.postDelayed(
                            { resolveOrRetry(info, tries + 1) },
                            RESOLVE_RETRY_MS
                        )
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        resolving.remove(name)
                        resolveAttempts.remove(name)
                        // Prefer the address the server advertises in its TXT
                        // record. Android's NSD often reports only the IPv6
                        // link-local address for a dual-stack host, and the
                        // server listens on IPv4.
                        val advertised = info.attributes
                            ?.get("ip")
                            ?.toString(Charsets.UTF_8)
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }

                        val candidates = info.hostAddresses ?: emptyList()
                        val host = advertised ?: pickAddress(candidates)?.hostAddress ?: run {
                            Log.w(
                                TAG,
                                "No usable address for '$name' " +
                                    "(candidates=${candidates.map { it.hostAddress }})"
                            )
                            return
                        }

                        val server = DiscoveredServer(
                            name = info.serviceName,
                            host = host,
                            port = info.port
                        )
                        _servers.value = _servers.value.filterNot { it.name == server.name } + server
                        Log.i(
                            TAG,
                            "Resolved server: $server (advertised=$advertised, " +
                                "candidates=${candidates.map { it.hostAddress }})"
                        )
                    }
                }
            )
        } catch (e: Exception) {
            resolving.remove(name)
            Log.w(TAG, "resolveService threw for '$name': $e")
            handler.postDelayed({ resolveOrRetry(serviceInfo, attempt + 1) }, RESOLVE_RETRY_MS)
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery start failed: $errorCode")
            browseActive = false
            if (!stopped) handler.postDelayed({ discover() }, REDISCOVER_DELAY_MS)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            // The old browse is still running; let the fallback discover()
            // call fail benignly ("already discovering") and re-sync.
            Log.w(TAG, "Discovery stop failed: $errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String) {
            Log.i(TAG, "Discovery started: $serviceType")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "Discovery stopped: $serviceType")
            if (!stopped && !browseActive) {
                handler.postDelayed({ discover() }, REDISCOVER_DELAY_MS)
            }
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.i(
                TAG,
                "Service found: ${serviceInfo.serviceName} port=${serviceInfo.port}"
            )
            // Refreshes re-deliver edges for services we already know; resolving
            // them again would only churn the list (and rate-limit NSD).
            if (_servers.value.any { it.name == serviceInfo.serviceName }) {
                Log.d(TAG, "'${serviceInfo.serviceName}' already in list; skipping resolve")
                return
            }
            resolveOrRetry(serviceInfo, 1)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            resolveAttempts.remove(serviceInfo.serviceName)
            _servers.value = _servers.value.filterNot { it.name == serviceInfo.serviceName }
            Log.i(TAG, "Server lost: ${serviceInfo.serviceName}")
            scheduleRefresh()
        }
    }
}
