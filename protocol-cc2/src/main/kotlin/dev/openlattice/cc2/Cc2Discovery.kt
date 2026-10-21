package dev.openlattice.cc2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Reply to the CC2 UDP discovery probe (`{"id":0,"method":7000}` on port 52700). */
data class Cc2DiscoveryResult(
    val host: String,
    val name: String,
    val model: String,
    val serial: String,
    val protocolVersion: String,
    /** True when the printer requires a user-set access code (`token_status` 1). */
    val accessCodeRequired: Boolean,
    /** True when the printer is in LAN-only mode (`lan_status` 1). The local API needs it. */
    val lanOnly: Boolean,
)

object Cc2Discovery {
    private val PROBE = """{"id":0,"method":${Cc2Method.DISCOVER}}""".toByteArray()

    /**
     * Sends the probe to each of [targets] (unicast addresses and/or broadcast addresses)
     * and collects replies until [timeoutMs] passes. Unicast works across routed VPNs;
     * broadcast only on the local segment.
     */
    suspend fun probe(targets: List<InetAddress>, timeoutMs: Int = 2000): List<Cc2DiscoveryResult> = withContext(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (t in targets) runCatching { socket.send(DatagramPacket(PROBE, PROBE.size, t, Cc2Method.DISCOVERY_PORT)) }
            val found = linkedMapOf<String, Cc2DiscoveryResult>()
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                socket.soTimeout = left.toInt()
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                parse(packet.address.hostAddress, packet.data.copyOf(packet.length))?.let { found[it.serial] = it }
            }
            found.values.toList()
        }
    }

    internal fun parse(host: String, payload: ByteArray): Cc2DiscoveryResult? = runCatching {
        val result = (Cc2Json.parseToJsonElement(payload.decodeToString()).jsonObject / "result").obj() ?: return null
        val serial = (result / "sn").str()?.takeIf { it.isNotBlank() } ?: return null
        Cc2DiscoveryResult(
            host = host,
            name = (result / "host_name").str().orEmpty(),
            model = (result / "machine_model").str().orEmpty(),
            serial = serial,
            protocolVersion = (result / "protocol_version").str().orEmpty(),
            accessCodeRequired = (result / "token_status").int() == 1,
            lanOnly = (result / "lan_status").int() == 1,
        )
    }.getOrNull()
}
