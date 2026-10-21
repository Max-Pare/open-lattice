package dev.openlattice.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import dev.openlattice.cc2.Cc2Discovery
import dev.openlattice.cc2.Cc2DiscoveryResult
import java.net.Inet4Address
import java.net.InetAddress

object LanDiscovery {
    /** Broadcast on every active IPv4 network; replies come back unicast. */
    suspend fun scan(context: Context): List<Cc2DiscoveryResult> {
        val targets = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION") // allNetworks is the only API covering every active network on API 26+.
        cm.allNetworks.mapNotNull { cm.getLinkProperties(it) }.forEach { lp ->
            lp.linkAddresses.filter { it.address is Inet4Address }.forEach { la ->
                broadcastOf(la.address as Inet4Address, la.prefixLength)?.let(targets::add)
            }
        }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("open-lattice-discovery")?.apply { setReferenceCounted(false); acquire() }
        try {
            return Cc2Discovery.probe(targets.toList(), timeoutMs = 2500)
        } finally {
            lock?.release()
        }
    }

    /** Direct probe of one host; works across routed VPNs where broadcast does not. */
    suspend fun probe(host: String): Cc2DiscoveryResult? {
        val address = runCatching { InetAddress.getByName(host.trim()) }.getOrNull() ?: return null
        return Cc2Discovery.probe(listOf(address), timeoutMs = 2500).firstOrNull()
    }

    private fun broadcastOf(address: Inet4Address, prefix: Int): InetAddress? {
        if (prefix !in 8..30 || address.isLoopbackAddress) return null
        val ip = address.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        val mask = -1 shl (32 - prefix)
        val bcast = (ip and mask) or mask.inv()
        return InetAddress.getByAddress(byteArrayOf((bcast ushr 24).toByte(), (bcast ushr 16).toByte(), (bcast ushr 8).toByte(), bcast.toByte()))
    }
}
