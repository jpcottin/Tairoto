package com.jpcottin.tairoto.world

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * A network in memory for tests: every device on it gets a [Link], messages
 * take [latencyMs] (plus up to [jitterMs]) to arrive and still arrive in order
 * between two devices, as over TCP. Links can be cut one way or both, and a
 * device can drop off. Time is the coroutine scope's, so under runTest a
 * minute of network passes in milliseconds.
 */
class FakeNetwork(
    private val scope: CoroutineScope,
    /** The time now, in ms: the test scheduler's. */
    private val nowMs: () -> Long,
    var latencyMs: Long = 15,
    var jitterMs: Long = 10,
    seed: Int = 1,
) {
    private val random = Random(seed)
    private val links = LinkedHashMap<Int, FakeLink>()
    private val lastArrival = HashMap<Pair<Int, Int>, Long>()
    private val cut = HashSet<Pair<Int, Int>>()
    private var nextSlot = 0

    /** Messages sent, and delivered, so far. */
    var sent = 0
        private set
    var delivered = 0
        private set
    var bytes = 0L
        private set

    /** Joins a device; the first one is the host (slot 0). */
    fun join(name: String): FakeLink {
        val link = FakeLink(this, DeviceId(nextSlot++, name))
        links[link.self.slot] = link
        refreshPeers()
        return link
    }

    /** A device drops off the network: nothing more goes to or from it. */
    fun drop(link: FakeLink) {
        links.remove(link.self.slot)
        link.closed = true
        refreshPeers()
    }

    /** Cuts (or mends) the way from one device to another; [bothWays] does the other direction too. */
    fun setCut(from: DeviceId, to: DeviceId, isCut: Boolean, bothWays: Boolean = true) {
        val pairs = if (bothWays) listOf(from.slot to to.slot, to.slot to from.slot) else listOf(from.slot to to.slot)
        if (isCut) cut += pairs else cut -= pairs.toSet()
    }

    private fun refreshPeers() {
        val all = links.values.map { it.self }
        links.values.forEach { it.peerState.value = all }
    }

    internal fun deliver(from: FakeLink, msg: Msg, to: DeviceId?) {
        if (from.closed) return
        val now = nowMs()
        val text = Wire.encode(msg)
        sent++
        val targets = if (to == null) links.values.toList() else listOfNotNull(links[to.slot])
        for (target in targets) {
            if (target === from) {
                // To itself: at once, as a link hands its own broadcasts back.
                target.inbox.trySend(Envelope(from.self, to, Wire.decode(text)))
                delivered++
                continue
            }
            val pair = from.self.slot to target.self.slot
            if (pair in cut) continue
            val wanted = now + latencyMs + (if (jitterMs > 0) random.nextLong(jitterMs + 1) else 0L)
            val at = maxOf(wanted, lastArrival[pair] ?: 0L)
            lastArrival[pair] = at
            bytes += text.length
            scope.launch {
                delay(at - now)
                if (target.closed || from.closed || pair in cut) return@launch
                target.inbox.trySend(Envelope(from.self, to, Wire.decode(text)))
                delivered++
            }
        }
    }
}

class FakeLink internal constructor(private val net: FakeNetwork, override val self: DeviceId) : Link {
    internal val inbox = Channel<Envelope>(Channel.UNLIMITED)
    internal val peerState = MutableStateFlow(listOf(self))
    internal var closed = false

    override val peers: StateFlow<List<DeviceId>> = peerState.asStateFlow()
    override val incoming: Flow<Envelope> = inbox.receiveAsFlow()

    override suspend fun send(msg: Msg, to: DeviceId?) {
        net.deliver(this, msg, to)
    }
}
