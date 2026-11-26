package com.jpcottin.tairoto.share

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.jpcottin.tairoto.net.LinkState
import com.jpcottin.tairoto.net.RelayLink
import com.jpcottin.tairoto.net.RelayServer
import com.jpcottin.tairoto.world.Link
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Shared lagoons on a device. Through a relay it is one connection. On the
 * local network the host runs the relay itself and announces it with NSD
 * (mDNS) as a `_tairoto._tcp` service; the others find it and connect to it,
 * the host included (to itself), so both ways run the same code.
 */
class AndroidShareConnector(context: Context) : ShareConnector {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val found = MutableStateFlow<List<NearbyLagoon>>(emptyList())
    override val nearby: StateFlow<List<NearbyLagoon>> = found.asStateFlow()

    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val resolver = Executors.newSingleThreadExecutor()

    override suspend fun host(name: String, route: ShareRoute): ShareConnection = when (route) {
        is ShareRoute.Relay -> relayConnection(
            RelayLink.connect(route.address.host, route.address.port, route.session, name, asHost = true),
        )
        ShareRoute.Nearby -> withContext(Dispatchers.IO) {
            val server = RelayServer(0) { Log.i(TAG, "relay: $it") }
            try {
                val link = RelayLink.connect("127.0.0.1", server.port, ShareRoute.DEFAULT_SESSION, name, asHost = true)
                val registration = advertise(name, server.port)
                relayConnection(link) {
                    registration?.let { runCatching { nsd?.unregisterService(it) } }
                    server.close()
                }
            } catch (e: Exception) {
                server.close()
                throw e
            }
        }
    }

    override suspend fun join(name: String, target: JoinTarget): ShareConnection = when (target) {
        is JoinTarget.Relay -> relayConnection(
            RelayLink.connect(target.address.host, target.address.port, target.session, name, asHost = false),
        )
        is JoinTarget.Nearby -> relayConnection(
            RelayLink.connect(target.lagoon.host, target.lagoon.port, ShareRoute.DEFAULT_SESSION, name, asHost = false),
        )
    }

    private fun relayConnection(link: RelayLink, onClose: () -> Unit = {}) = object : ShareConnection {
        override val link: Link = link
        override val ended: Flow<String> = link.state.filterIsInstance<LinkState.Closed>().map { it.reason }.take(1)
        override fun close() {
            link.close()
            onClose()
        }
    }

    private fun advertise(name: String, port: Int): NsdManager.RegistrationListener? {
        val manager = nsd ?: return null
        val info = NsdServiceInfo().apply {
            serviceName = "Tairoto · $name"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "announced ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "could not announce the lagoon: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return listener
    }

    override fun discover() {
        val manager = nsd ?: return
        if (discovery != null) return
        multicast = wifi?.createMulticastLock("tairoto")?.apply { setReferenceCounted(false); acquire() }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.w(TAG, "could not look for lagoons: $code")
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = resolve(manager, info)
            override fun onServiceLost(info: NsdServiceInfo) {
                found.value = found.value.filterNot { it.name == displayName(info.serviceName) }
            }
        }
        discovery = listener
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Suppress("DEPRECATION")
    private fun resolve(manager: NsdManager, info: NsdServiceInfo) {
        fun add(resolved: NsdServiceInfo) {
            val host = if (Build.VERSION.SDK_INT >= 34) resolved.hostAddresses.firstOrNull() else resolved.host
            val address = host?.hostAddress ?: return
            val lagoon = NearbyLagoon(displayName(resolved.serviceName), address, resolved.port)
            found.value = (found.value.filterNot { it.name == lagoon.name } + lagoon).sortedBy { it.name }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            manager.registerServiceInfoCallback(info, resolver, object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(code: Int) = Unit
                override fun onServiceUpdated(resolved: NsdServiceInfo) {
                    add(resolved)
                    runCatching { manager.unregisterServiceInfoCallback(this) }
                }
                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            })
        } else {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
                override fun onServiceResolved(resolved: NsdServiceInfo) = add(resolved)
            })
        }
    }

    override fun stopDiscovery() {
        discovery?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        discovery = null
        multicast?.release()
        multicast = null
        found.value = emptyList()
    }

    private fun displayName(service: String) = service.removePrefix("Tairoto · ")

    companion object {
        const val SERVICE_TYPE = "_tairoto._tcp"
        private const val TAG = "TairotoShare"
    }
}
