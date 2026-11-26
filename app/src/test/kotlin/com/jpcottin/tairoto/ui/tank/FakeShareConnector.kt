package com.jpcottin.tairoto.ui.tank

import com.jpcottin.tairoto.share.JoinTarget
import com.jpcottin.tairoto.share.NearbyLagoon
import com.jpcottin.tairoto.share.ShareConnection
import com.jpcottin.tairoto.share.ShareConnector
import com.jpcottin.tairoto.share.ShareRoute
import com.jpcottin.tairoto.world.FakeLink
import com.jpcottin.tairoto.world.FakeNetwork
import com.jpcottin.tairoto.world.Link
import com.jpcottin.tairoto.world.SharedTank
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import java.io.IOException

/** Shared lagoons on a [FakeNetwork]: the first device to host is the host, the others join it. */
class FakeShareConnector(private val net: FakeNetwork) : ShareConnector {
    private val found = MutableStateFlow<List<NearbyLagoon>>(emptyList())
    override val nearby: StateFlow<List<NearbyLagoon>> = found
    var discovering = false
        private set
    /** When set, connecting fails with it. */
    var failure: IOException? = null
    val routes = mutableListOf<ShareRoute>()
    val targets = mutableListOf<JoinTarget>()

    fun announce(vararg lagoons: NearbyLagoon) {
        found.value = lagoons.toList()
    }

    override suspend fun host(name: String, route: ShareRoute): ShareConnection {
        failure?.let { throw it }
        routes += route
        return connection(net.join(name), guest = false)
    }

    override suspend fun join(name: String, target: JoinTarget): ShareConnection {
        failure?.let { throw it }
        targets += target
        return connection(net.join(name), guest = true)
    }

    private fun connection(l: FakeLink, guest: Boolean) = object : ShareConnection {
        override val link: Link = l
        // A guest's connection ends when the host is gone from the network.
        override val ended: Flow<String> = if (guest) {
            l.peers.filter { peers -> peers.none { it.slot == SharedTank.HOST_SLOT } }.map { "the host left" }.take(1)
        } else {
            emptyFlow()
        }
        override fun close() = net.drop(l)
    }

    override fun discover() {
        discovering = true
    }

    override fun stopDiscovery() {
        discovering = false
    }
}
