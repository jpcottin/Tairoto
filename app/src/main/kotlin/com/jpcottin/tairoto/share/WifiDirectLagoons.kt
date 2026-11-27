package com.jpcottin.tairoto.share

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jpcottin.tairoto.net.RelayServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom
import kotlin.coroutines.resume

/**
 * Lagoons over Wi-Fi Direct, for devices with no Wi-Fi network in common:
 * faster than Bluetooth, and needs no router.
 *
 * The host makes a Wi-Fi Direct group of its own, with a network name and a
 * passphrase it picks, and says so through Wi-Fi Direct service discovery,
 * with its relay's port. A guest joins that group by name and passphrase (no
 * invitation to accept on either device) and reaches the relay at the
 * group owner's address, the same relay Wi-Fi and Bluetooth serve. A lagoon
 * is no secret, so the passphrase travels in the open, as the Bluetooth
 * channel is unpaired.
 *
 * Needs Android 13 (NEARBY_WIFI_DEVICES, never for location): below that it
 * is simply not offered.
 */
class WifiDirectLagoons(context: Context) {
    private val app = context.applicationContext
    private val manager: WifiP2pManager? =
        if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) app.getSystemService(WifiP2pManager::class.java) else null
    private var channel: WifiP2pManager.Channel? = null

    private fun channel(): WifiP2pManager.Channel? {
        val m = manager ?: return null
        return channel ?: m.initialize(app, Looper.getMainLooper(), null).also { channel = it }
    }

    /** True if this device can share over Wi-Fi Direct: new enough, has it, allowed. */
    fun usable(): Boolean = Build.VERSION.SDK_INT >= MIN_SDK && manager != null &&
        app.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- hosting

    /** Makes a group, says so around with [relay]'s port, until closed; null if Wi-Fi Direct cannot. */
    @SuppressLint("MissingPermission")
    suspend fun host(relay: RelayServer, name: String): AutoCloseable? {
        if (!usable()) return null
        val m = manager ?: return null
        val ch = channel() ?: return null
        val group = Group.make(SecureRandom())
        // A group of ours, left over from a lagoon that ended badly, goes first.
        call { m.removeGroup(ch, it) }
        val config = WifiP2pConfig.Builder()
            .setNetworkName(group.networkName)
            .setPassphrase(group.passphrase)
            .build()
        if (!call { m.createGroup(ch, config, it) }) {
            Log.w(TAG, "no Wi-Fi Direct group")
            return null
        }
        val record = Record(name, relay.port, group.networkName, group.passphrase)
        val service = WifiP2pDnsSdServiceInfo.newInstance(record.instance(), SERVICE_TYPE, record.txt())
        call { m.addLocalService(ch, service, it) }
        Log.i(TAG, "lagoon over Wi-Fi Direct as ${group.networkName}, relay port ${relay.port}")
        return AutoCloseable {
            m.removeLocalService(ch, service, null)
            m.removeGroup(ch, null)
        }
    }

    // ---------------------------------------------------------------- finding

    private val seen = HashMap<String, Pair<NearbyLagoon, Long>>()
    private var request: WifiP2pDnsSdServiceRequest? = null
    private var onChange: ((List<NearbyLagoon>) -> Unit)? = null

    /** Looks for lagoons over Wi-Fi Direct, calling [onChange] with all those heard of lately. */
    @SuppressLint("MissingPermission")
    fun scan(onChange: (List<NearbyLagoon>) -> Unit) {
        if (!usable()) return
        val m = manager ?: return
        val ch = channel() ?: return
        this.onChange = onChange
        m.setDnsSdResponseListeners(
            ch,
            { _, _, _ -> },
            { fullDomain, txt, _ ->
                if (!fullDomain.contains(SERVICE_TYPE)) return@setDnsSdResponseListeners
                val record = Record.parse(txt) ?: return@setDnsSdResponseListeners
                synchronized(seen) { seen[record.networkName] = record.lagoon() to SystemClock.elapsedRealtime() }
                this.onChange?.invoke(current())
            },
        )
        val r = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)
        request = r
        m.addServiceRequest(ch, r, null)
        m.discoverServices(ch, null)
    }

    private fun current(): List<NearbyLagoon> = synchronized(seen) {
        val now = SystemClock.elapsedRealtime()
        seen.entries.removeAll { now - it.value.second > FORGET_MS }
        seen.values.map { it.first }
    }

    fun stopScan() {
        val m = manager ?: return
        val ch = channel ?: return
        request?.let { m.removeServiceRequest(ch, it, null) }
        request = null
        onChange = null
        m.stopPeerDiscovery(ch, null)
        synchronized(seen) { seen.clear() }
    }

    // ---------------------------------------------------------------- joining

    /**
     * Joins the lagoon's group and gives the address its relay answers at
     * (the group owner's) with its port, and what leaves the group again.
     */
    @SuppressLint("MissingPermission")
    suspend fun join(lagoon: NearbyLagoon): Pair<RelayAddress, AutoCloseable> {
        val m = manager ?: throw java.io.IOException("no Wi-Fi Direct here")
        val ch = channel() ?: throw java.io.IOException("no Wi-Fi Direct here")
        val record = Record.fromLagoon(lagoon) ?: throw java.io.IOException("not a Wi-Fi Direct lagoon")
        val config = WifiP2pConfig.Builder()
            .setNetworkName(record.networkName)
            .setPassphrase(record.passphrase)
            .build()
        if (!call { m.connect(ch, config, it) }) throw java.io.IOException("could not join the group")
        val leave = AutoCloseable { m.removeGroup(ch, null) }
        try {
            val owner = withTimeout(JOIN_TIMEOUT_MS) {
                var info: WifiP2pInfo? = null
                while (info?.groupFormed != true || info.groupOwnerAddress == null) {
                    delay(400)
                    info = suspendCancellableCoroutine { k -> m.requestConnectionInfo(ch) { k.resume(it) } }
                }
                info.groupOwnerAddress.hostAddress!!
            }
            return RelayAddress(owner, record.port) to leave
        } catch (e: Exception) {
            leave.close()
            throw java.io.IOException("the group did not form: ${e.message}")
        }
    }

    /** Runs a Wi-Fi Direct request and waits for its answer: true if it succeeded. */
    private suspend fun call(request: (WifiP2pManager.ActionListener) -> Unit): Boolean =
        suspendCancellableCoroutine { k ->
            request(object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (k.isActive) k.resume(true) }
                override fun onFailure(reason: Int) {
                    Log.i(TAG, "Wi-Fi Direct said no: $reason")
                    if (k.isActive) k.resume(false)
                }
            })
        }

    /** A group's name and passphrase. Wi-Fi Direct names start with DIRECT- and two characters. */
    data class Group(val networkName: String, val passphrase: String) {
        companion object {
            private const val LETTERS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

            fun make(random: java.util.Random): Group {
                fun pick(n: Int) = String(CharArray(n) { LETTERS[random.nextInt(LETTERS.length)] })
                return Group("DIRECT-${pick(2)}-Tairoto", pick(12))
            }
        }
    }

    /**
     * What a lagoon says of itself in Wi-Fi Direct service discovery: its
     * name, its relay's port, and the group to join. As a [NearbyLagoon], the
     * group travels in [NearbyLagoon.host] ("network\tpassphrase").
     */
    data class Record(val name: String, val port: Int, val networkName: String, val passphrase: String) {
        fun instance(): String = "Tairoto"

        fun txt(): Map<String, String> = mapOf("n" to name.take(MAX_NAME), "p" to port.toString(), "g" to networkName, "k" to passphrase)

        fun lagoon() = NearbyLagoon(name, "$networkName\t$passphrase", port, NearbyLagoon.Via.WIFI_DIRECT)

        companion object {
            const val MAX_NAME = 40

            fun parse(txt: Map<String, String>): Record? {
                val port = txt["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                val group = txt["g"]?.takeIf { it.startsWith("DIRECT-") && it.length <= 32 } ?: return null
                val key = txt["k"]?.takeIf { it.length in 8..63 } ?: return null
                val name = txt["n"]?.takeIf { it.isNotBlank() } ?: "A lagoon"
                return Record(name, port, group, key)
            }

            fun fromLagoon(l: NearbyLagoon): Record? {
                if (l.via != NearbyLagoon.Via.WIFI_DIRECT) return null
                val parts = l.host.split('\t')
                if (parts.size != 2) return null
                return parse(mapOf("n" to l.name, "p" to l.port.toString(), "g" to parts[0], "k" to parts[1]))
            }
        }
    }

    companion object {
        const val MIN_SDK = 33
        const val PERMISSION = "android.permission.NEARBY_WIFI_DEVICES"
        const val SERVICE_TYPE = "_tairoto._tcp"
        private const val FORGET_MS = 30_000L
        private const val JOIN_TIMEOUT_MS = 25_000L
        private const val TAG = "TairotoWifiDirect"
    }
}
