package com.jpcottin.tairoto.share

import com.jpcottin.tairoto.world.Link
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A host:port to reach a relay at: the computer emulators run on, or a relay elsewhere. */
data class RelayAddress(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"

    companion object {
        /** Reads `host:port` (the port may be left out); null if it makes no sense. */
        fun parse(text: String?, defaultPort: Int = DEFAULT_PORT): RelayAddress? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            val colon = t.lastIndexOf(':')
            val (host, port) = if (colon > 0) t.substring(0, colon) to t.substring(colon + 1).toIntOrNull() else t to defaultPort
            if (port == null || port !in 1..65535) return null
            if (host.isBlank() || host.any { it.isWhitespace() || it == '/' }) return null
            return RelayAddress(host, port)
        }

        const val DEFAULT_PORT = 7171
        /** The computer an emulator runs on, as the emulator sees it. */
        val EMULATOR_HOST = RelayAddress("10.0.2.2", DEFAULT_PORT)
    }
}

/**
 * A shared lagoon found nearby: on the local network ([host] an IP address,
 * [port] a TCP port), over Wi-Fi Direct ([host] the group to join, [port] the
 * relay's) or over Bluetooth ([host] the device's address, [port] its L2CAP
 * channel).
 */
data class NearbyLagoon(val name: String, val host: String, val port: Int, val via: Via = Via.WIFI) {
    /** The ways, fastest first. */
    enum class Via { WIFI, WIFI_DIRECT, BLUETOOTH }

    companion object {
        /** One entry a lagoon, the fastest way it was heard of (Wi-Fi, then Wi-Fi Direct, then Bluetooth), in the order given. */
        fun oneEach(lagoons: List<NearbyLagoon>): List<NearbyLagoon> =
            lagoons.groupBy { it.name }.values.map { ways -> ways.minBy { it.via.ordinal } }
    }
}

/** How to share: through a relay, or with devices nearby (Wi-Fi and Bluetooth) with this device as the relay. */
sealed interface ShareRoute {
    data class Relay(val address: RelayAddress, val session: String = DEFAULT_SESSION) : ShareRoute
    data object Nearby : ShareRoute

    companion object {
        const val DEFAULT_SESSION = "lagoon"
    }
}

/** A lagoon to join: one found nearby, or one through a relay. */
sealed interface JoinTarget {
    data class Relay(val address: RelayAddress, val session: String = ShareRoute.DEFAULT_SESSION) : JoinTarget
    data class Nearby(val lagoon: NearbyLagoon) : JoinTarget
}

/** A device's way into a shared lagoon, open until [close]. */
interface ShareConnection {
    val link: Link
    /** Says why, once, if the connection ends on its own (the host left, the network went). */
    val ended: Flow<String>
    /** Bytes received and sent so far, for logs (Bluetooth carries far less than Wi-Fi). */
    val bytesIn: Long get() = 0
    val bytesOut: Long get() = 0
    fun close()
}

/** Opens shared lagoons and finds them nearby. Real on a device, in memory in tests. */
interface ShareConnector {
    /** Lagoons shared nearby, on the local network or over Bluetooth, while [discover] runs. */
    val nearby: StateFlow<List<NearbyLagoon>>

    suspend fun host(name: String, route: ShareRoute): ShareConnection
    suspend fun join(name: String, target: JoinTarget): ShareConnection

    /** Starts looking for lagoons nearby (until [stopDiscovery]). */
    fun discover()
    fun stopDiscovery()
}
