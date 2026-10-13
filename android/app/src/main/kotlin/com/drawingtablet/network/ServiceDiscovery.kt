package com.drawingtablet.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

private const val TAG = "ServiceDiscovery"
private const val SERVICE_TYPE = "_drawingtablet._udp."

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

    fun start() {
        try {
            nsdManager.discoverServices(SERVICE_TYPE, 1 /* PROTOCOL_NSD */, discoveryListener)
            Log.i(TAG, "Service discovery started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
        }
    }

    fun stop() {
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (_: Exception) {}
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

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery start failed: $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery stop failed: $errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(serviceInfo, Executors.newSingleThreadExecutor(),
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for '${info.serviceName}': $errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
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
                        val host = advertised ?: pickAddress(candidates)?.hostAddress ?: return

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
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            _servers.value = _servers.value.filterNot { it.name == serviceInfo.serviceName }
            Log.i(TAG, "Server lost: ${serviceInfo.serviceName}")
        }
    }
}
