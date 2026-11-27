package com.jpcottin.tairoto.world

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** The timings of a shared lagoon. */
data class ShareConfig(
    /** How often a device swaps states with its neighbours: 15 times a second. */
    val tickS: Double = 1.0 / 15.0,
    /** Every so many ticks every creature goes to every device, not only those near its screen. */
    val farEveryTicks: Int = 4,
    /** Every so many ticks a device pings the host, renewing its lease. */
    val pingEveryTicks: Int = 4,
    val scentEveryTicks: Int = 8,
    /** A screen hears about everything this close to it at the full rate. */
    val interestMm: Float = 45f,
    /** A creature has to be this far inside the next screen before it is handed over, so an edge is no coin toss. */
    val insetMm: Float = 3f,
    /** An offer nobody claims is cancelled after this. */
    val handoffTimeoutS: Double = 0.5,
    /** After an offer was not taken up, the creature is not offered again for this long. */
    val offerBackoffS: Double = 2.0,
    /**
     * A device simulates only while the host answered a ping sent less than
     * this long ago. The host takes over a device's creatures only after not
     * hearing from it for [lostS], longer, so the two never both simulate a
     * creature, even when the network splits them apart.
     */
    val leaseS: Double = 2.0,
    val lostS: Double = 2.5,
    val maxFoodPerMessage: Int = 300,
    /** A finger leaving one screen and coming onto another this close in time is one swipe across both. */
    val swipePairS: Double = 0.5,
) {
    init {
        require(lostS > leaseS + 2 * tickS) { "the host must wait longer than a lease" }
    }
}

/** What happened, for logs and for the tests that follow creatures across screens. */
sealed interface ShareEvent {
    val t: Double

    data class Took(override val t: Double, val id: EntityId, val epoch: Int) : ShareEvent
    data class Released(override val t: Double, val id: EntityId, val to: DeviceId?) : ShareEvent
    data class FoodSent(override val t: Double, val id: Long, val to: DeviceId, val mg: Float) : ShareEvent
    data class FoodTaken(override val t: Double, val id: Long, val mg: Float) : ShareEvent
    data class DeviceLost(override val t: Double, val device: DeviceId, val taken: Int) : ShareEvent
    data class Fenced(override val t: Double, val fenced: Boolean) : ShareEvent
    /** A swipe across two screens laid [to] beside [from]. */
    data class Stitched(override val t: Double, val from: DeviceId, val to: DeviceId) : ShareEvent
}

/** How the shared lagoon is doing, for the screen. */
data class ShareStatus(
    val self: DeviceId,
    val isHost: Boolean,
    val devices: Int,
    /** True while this device may simulate (it has the host's word): false after losing touch. */
    val connected: Boolean = true,
    /** True once the host has given up on this device. */
    val gone: Boolean = false,
    val rttMs: Float? = null,
    val owned: Int = 0,
    val handoffsOut: Int = 0,
    val handoffsIn: Int = 0,
)

/**
 * One device's part in a lagoon shared by several.
 *
 * Every creature has exactly one owner, the only device that simulates it;
 * the others show a ghost that follows the owner's states. When a creature
 * swims onto another screen its owner stops simulating it and offers it
 * (epoch + 1); the receiver claims it, and the host, the referee, confirms
 * with [Msg.Owner]. The receiver starts only on that confirmation, so there
 * is a moment when nobody simulates it (its ghosts drift on), never one when
 * two do. Food changes hands the same way, but straight from owner to
 * receiver, and a bite at food another device owns is decided by that device.
 *
 * Single-threaded: [handle] and [tick] run on the same dispatcher.
 */
class SharedTank(
    val session: TankSession,
    private val link: Link,
    private val sim: SharedSim,
    /** The local monotonic clock, in seconds. */
    private val now: () -> Double,
    layout: Layout,
    owners: List<OwnerEntry>,
    private val config: ShareConfig = ShareConfig(),
    private val onEvent: (ShareEvent) -> Unit = {},
) {
    /** Every ownership message sent and received, for chasing a bug through the logs. */
    var trace: ((String) -> Unit)? = null

    val self: DeviceId = link.self
    val isHost: Boolean = self.slot == HOST_SLOT

    private val layoutState = MutableStateFlow(layout)
    val layout: StateFlow<Layout> = layoutState.asStateFlow()

    private val statusState = MutableStateFlow(ShareStatus(self, isHost, layout.placements.size))
    val status: StateFlow<ShareStatus> = statusState.asStateFlow()

    val clock = ClockSync()

    /** Who owns what, as far as this device knows: the host's word, newest epoch wins. */
    private val mirror = HashMap<EntityId, Ownership>()
    /** The host's referee (only used on the host). */
    private val registry = OwnershipRegistry(host = self)
    private val offeredAt = HashMap<EntityId, Double>()
    private val lastHeard = HashMap<DeviceId, Double>()
    private val lost = HashSet<DeviceId>()

    /** What the local simulation simulated at the last exchange. */
    private var simOwned: Set<EntityId> = emptySet()
    /** Decided to hand over: released at the next exchange, offered just after. */
    private val toRelease = LinkedHashMap<EntityId, DeviceId>()
    /** Offered, waiting for the host's word. */
    private val awaiting = HashMap<EntityId, DeviceId>()
    /** Creatures whose last offer went unclaimed, and until when they are not offered again. */
    private val backoff = HashMap<EntityId, Double>()
    /** The state a creature was handed over with, for when it becomes this device's. */
    private val handedState = HashMap<EntityId, NetCreature>()
    /** The latest state heard of every creature this device does not simulate. */
    private val lastState = HashMap<EntityId, Pair<NetCreature, Double>>()

    private val ghostIn = LinkedHashMap<EntityId, GhostUpdate>()
    private val foodIn = LinkedHashMap<Long, FoodUpdate>()
    private val adoptIn = ArrayList<NetFood>()
    private val foodToRelease = LinkedHashMap<Long, DeviceId>()
    private val foodOwner = HashMap<Long, DeviceId>()
    private val bitesIn = ArrayList<Pair<DeviceId, BiteWant>>()
    private val fedIn = ArrayList<Pair<Fed, Int>>()
    private val tapsIn = ArrayList<Vec2>()
    private val remoteScents = HashMap<DeviceId, List<NetScent>>()
    private var scentsDirty = false

    private var lastAnsweredPing: Double? = null
    private var left = false
    private var gone = false
    private var ticks = 0L
    private var handoffsOut = 0
    private var handoffsIn = 0

    private var lastOwned: List<NetCreature> = emptyList()

    /** The food ledger as of the last exchange. */
    var ledger: FoodLedger = FoodLedger()
        private set

    init {
        for (o in owners) {
            mirror[o.id] = Ownership(o.owner, o.epoch)
            if (isHost) registry.register(o.id, o.owner)
        }
        if (isHost) {
            // Creatures in the scene nobody claims (a fresh host): the host's.
            for (c in session.scene.value.creatures) {
                if (c.id !in mirror) {
                    mirror[c.id] = Ownership(self, 0)
                    registry.register(c.id, self)
                }
            }
        }
    }

    /** The shared time, in seconds: the host's clock. */
    fun sharedNow(): Double = if (isHost) now() else clock.sharedTime(now())

    fun ownerOf(id: EntityId): DeviceId? = mirror[id]?.owner

    /** Who owns what, for a device joining (host only). */
    fun ownersSnapshot(): List<OwnerEntry> =
        session.scene.value.creatures.mapNotNull { c -> mirror[c.id]?.let { OwnerEntry(c.id, it.owner, it.epoch) } }

    /** True while this device has the host's word that it may simulate. */
    fun leaseValid(): Boolean {
        if (isHost) return true
        if (left || gone) return false
        val last = lastAnsweredPing ?: return false
        return now() - last < config.leaseS - 2 * config.tickS
    }

    /** Should the local simulation simulate this creature right now? */
    fun shouldSimulate(id: EntityId): Boolean =
        mirror[id]?.owner == self && id !in awaiting && leaseValid()

    /** Runs the shared lagoon: applies what arrives and ticks, until cancelled. */
    suspend fun run() = coroutineScope {
        launch {
            link.incoming.collect { env ->
                session.handle(env)
                handle(env)
                flush()
            }
        }
        // What the simulation already simulates (a host starting out simulates its whole lagoon).
        simOwned = sim.exchange(SimInput()).owned.mapTo(HashSet()) { it.id }
        if (!isHost) link.send(Msg.Ping(now()), hostOf())
        while (coroutineContext.isActive) {
            tick()
            delay((config.tickS * 1000).toLong())
        }
    }

    /** Leaves the lagoon: stops simulating everything, then says so. */
    suspend fun leave() {
        left = true
        sim.exchange(SimInput(release = simOwned.toList()))
        simOwned = emptySet()
        if (!isHost) link.send(Msg.Bye(self), hostOf())
        publishStatus()
    }

    private fun hostOf(): DeviceId =
        layoutState.value.placements.firstOrNull { it.device.slot == HOST_SLOT }?.device
            ?: link.peers.value.firstOrNull { it.slot == HOST_SLOT }
            ?: DeviceId(HOST_SLOT, "host")

    private fun members(): List<DeviceId> = layoutState.value.placements.map { it.device }.filter { it != self }

    // ------------------------------------------------------------ messages

    fun handle(env: Envelope) {
        val from = env.from
        trace?.let { t ->
            when (val m = env.msg) {
                is Msg.Handoff -> t("in handoff ${m.id.raw} ${m.from.slot}->${m.to.slot} e${m.epoch} from ${from.slot}")
                is Msg.Claim -> t("in claim ${m.id.raw} by ${m.by.slot} e${m.epoch}")
                is Msg.Owner -> t("in owner ${m.id.raw} ${m.owner.slot} e${m.epoch} mirror ${mirror[m.id]?.let { "${it.owner.slot}e${it.epoch}" }}")
                else -> Unit
            }
        }
        if (isHost && from != self) {
            if (from in lost) {
                // A device given up on: it must start over.
                if (env.msg !is Msg.Hello) return
            } else {
                lastHeard[from] = now()
            }
        }
        when (val m = env.msg) {
            is Msg.Hello -> if (isHost) welcome(from, m.screen, m.brings)
            is Msg.Arrange -> if (isHost) {
                val lay = layoutState.value
                apply(lay.moved(m.device, m.center, m.turns, baseSize()))
            }
            is Msg.EdgeSwipe -> if (isHost) onSwipe(m)
            is Msg.Arranging -> if (isHost && layoutState.value.arranging != m.on) apply(layoutState.value.copy(arranging = m.on))
            is Msg.LayoutChanged -> {
                layoutState.value = m.layout
                session.growTo(m.layout.tank)
                publishStatus()
            }
            is Msg.Ping -> if (isHost) send(Msg.Pong(m.sent, now()), from)
            is Msg.Pong -> {
                clock.onPong(m.sent, m.host, now())
                val before = leaseValid()
                lastAnsweredPing = maxOf(lastAnsweredPing ?: m.sent, m.sent)
                if (!before && leaseValid()) onEvent(ShareEvent.Fenced(sharedNow(), false))
            }
            is Msg.States -> {
                val age = (sharedNow() - m.t).toFloat().coerceAtLeast(0f)
                for (c in m.creatures) {
                    if (shouldSimulate(c.id)) continue
                    lastState[c.id] = c to m.t
                    ghostIn[c.id] = GhostUpdate(c, age)
                }
            }
            is Msg.Handoff -> onHandoff(m)
            is Msg.Claim -> if (isHost) {
                val r = registry.claim(m.id, m.by, m.epoch)
                if (r is OwnershipResult.Accepted) {
                    offeredAt.remove(m.id)
                    broadcast(Msg.Owner(m.id, r.ownership.owner, r.ownership.epoch))
                }
            }
            is Msg.Owner -> onOwner(m)
            is Msg.Spawn -> {
                mirror[m.creature.id] = Ownership(m.owner, 0)
                if (isHost) registry.register(m.creature.id, m.owner)
            }
            is Msg.Remove -> forget(m.id)
            is Msg.Reset -> {
                mirror.keys.toList().forEach { forget(it) }
                for (c in m.scene.creatures) {
                    mirror[c.id] = Ownership(from, 0)
                    if (isHost) registry.register(c.id, from)
                }
            }
            is Msg.Foods -> {
                val age = (sharedNow() - m.t).toFloat().coerceAtLeast(0f)
                for (f in m.food) {
                    if (foodOwner[f.id] == self) continue // late news of food handed here since
                    foodOwner[f.id] = from
                    foodIn[f.id] = FoodUpdate(f, age)
                }
            }
            is Msg.FoodHandoff -> {
                foodOwner[m.food.id] = m.to
                if (m.to == self) {
                    foodIn.remove(m.food.id)
                    adoptIn += m.food
                }
            }
            is Msg.Scents -> {
                remoteScents[from] = m.scents
                scentsDirty = true
            }
            is Msg.Bite -> bitesIn += from to m.want
            is Msg.FedMsg -> fedIn += m.fed to m.hops
            is Msg.Tap -> if (from != self) tapsIn += m.at
            is Msg.Bye -> if (isHost && m.device != self) deviceLost(m.device, now())
            is Msg.Gone -> if (m.device == self) {
                gone = true
                onEvent(ShareEvent.Fenced(sharedNow(), true))
                publishStatus()
            }
            else -> Unit
        }
    }

    /**
     * This device's screen changed size (a foldable opened, a window was
     * stretched): the host lays the table out again, a guest asks it to.
     */
    suspend fun screenChanged(screen: ScreenMm) {
        if (layoutState.value.placement(self)?.screen == screen) return
        if (isHost) welcome(self, screen) else send(Msg.Hello(self, screen), hostOf())
        flush()
    }

    // ------------------------------------------------------------ the table

    /** Asks for [device]'s screen to lie at [center], turned [turns] (anyone may; the host decides). */
    suspend fun arrange(device: DeviceId, center: Vec2, turns: Int) = toHost(Msg.Arrange(device, center, turns))

    /** A finger left this screen through [edge] (or came onto it, [entering]), [alongMm] along it. */
    suspend fun edgeSwipe(edge: Edge, alongMm: Float, entering: Boolean) =
        toHost(Msg.EdgeSwipe(self, edge, alongMm, entering, sharedNow()))

    /** Starts or ends arranging the screens, for every device. */
    suspend fun setArranging(on: Boolean) = toHost(Msg.Arranging(on))

    private suspend fun toHost(msg: Msg) {
        if (isHost) handle(Envelope(self, self, msg)) else send(msg, hostOf())
        flush()
    }

    private fun baseSize(): TankSize = session.scene.value.size.grownTo(layoutState.value.tank)

    /** The host lays the table out anew and tells everyone. */
    private fun apply(next: Layout) {
        if (next == layoutState.value) return
        layoutState.value = next
        session.growTo(next.tank)
        broadcast(Msg.LayoutChanged(next))
        publishStatus()
    }

    private val swipes = ArrayList<Msg.EdgeSwipe>()

    /** Pairs a finger leaving one screen with one coming onto another, and lays the second beside the first. */
    private fun onSwipe(m: Msg.EdgeSwipe) {
        swipes.removeAll { kotlin.math.abs(m.t - it.t) > 4 * config.swipePairS || (it.device == m.device && it.entering == m.entering) }
        val partner = swipes
            .filter { it.entering != m.entering && it.device != m.device && kotlin.math.abs(it.t - m.t) <= config.swipePairS }
            .minByOrNull { kotlin.math.abs(it.t - m.t) }
        if (partner == null) {
            swipes += m
            return
        }
        swipes.remove(partner)
        val (out, into) = if (m.entering) partner to m else m to partner
        // Leaving comes first: a finger cannot come onto the next screen before it has left this one.
        if (into.t < out.t - 0.05) return
        val next = layoutState.value.stitched(out.device, out.edge, out.alongMm, into.device, into.edge, into.alongMm, baseSize()) ?: return
        onEvent(ShareEvent.Stitched(sharedNow(), out.device, into.device))
        apply(next)
    }

    private fun welcome(device: DeviceId, screen: ScreenMm, brings: Visitors? = null) {
        lost.remove(device)
        lastHeard[device] = now()
        val current = layoutState.value
        val next = current.withScreen(device, screen, baseSize())
        layoutState.value = next
        session.growTo(next.tank)
        broadcast(Msg.LayoutChanged(next))
        // The host itself, or a device already in that only changed size: the layout says it all.
        if (device == self || current.placement(device) != null) {
            publishStatus()
            return
        }
        // What it brings comes in under its screen, and is its own to simulate from the start.
        val visitors = brings?.takeIf { !it.isEmpty }?.let { v ->
            val p = next.placement(device)!!
            val half = Vec2(screen.widthMm / 2f, screen.heightMm / 2f)
            session.visitorsFrom(v, device, { at -> p.toTable(at + half) }, Turns.radians(p.turns))
        } ?: Visitors()
        for (c in visitors.creatures) {
            mirror[c.id] = Ownership(device, 0)
            registry.register(c.id, device)
        }
        // The lagoon as it is this moment, every creature where it swims now.
        val places = HashMap<EntityId, CreaturePlace>()
        for ((id, st) in lastState) places[id] = CreaturePlace(st.first.at, st.first.z)
        for (st in lastOwned) places[st.id] = CreaturePlace(st.at, st.z)
        val scene = session.scene.value.withPlaces(places).let { it.copy(creatures = it.creatures + visitors.creatures, decor = it.decor + visitors.decor) }
        send(Msg.Join(scene, ownersSnapshot() + visitors.creatures.map { OwnerEntry(it.id, device, 0) }, next, now()), device)
        // Everyone else (and this device) hears of them as of anything new.
        for (c in visitors.creatures) broadcast(Msg.Spawn(c, device))
        for (d in visitors.decor) broadcast(Msg.PlaceDecor(d))
        publishStatus()
    }

    /** What [device] brought goes home with it. */
    private fun sendHome(device: DeviceId) {
        val scene = session.scene.value
        for (c in scene.creatures) if (c.home == device.slot) broadcast(Msg.Remove(c.id))
        for (d in scene.decor) if (d.home == device.slot) broadcast(Msg.RemoveDecor(d.id))
    }

    private fun onHandoff(m: Msg.Handoff) {
        if (isHost) {
            val r = registry.handoff(m.id, m.from, m.to, m.epoch)
            if (r is OwnershipResult.Accepted) {
                offeredAt[m.id] = now()
            } else {
                // Refused (stale, or it changed hands meanwhile): tell the one who offered what is true.
                registry[m.id]?.let { send(Msg.Owner(m.id, it.owner, it.epoch), m.from) }
            }
        }
        if (m.to == self) {
            m.state?.let {
                handedState[m.id] = it
                lastState[m.id] = it to m.t
            }
            if (leaseValid()) send(Msg.Claim(m.id, self, m.epoch), hostOf())
        }
    }

    private fun onOwner(m: Msg.Owner) {
        val cur = mirror[m.id]
        if (cur != null && m.epoch < cur.epoch) return // late: something newer is known
        if (session.scene.value.creature(m.id) == null && cur == null) return
        mirror[m.id] = Ownership(m.owner, m.epoch)
        // Still this device's after an offer: nobody took it up; give the other a rest.
        if (awaiting.containsKey(m.id) && m.owner == self) backoff[m.id] = now() + config.offerBackoffS
        awaiting.remove(m.id)
        toRelease.remove(m.id)
        if (m.owner != self) handedState.remove(m.id)
    }

    private fun forget(id: EntityId) {
        mirror.remove(id)
        if (isHost) registry.unregister(id)
        offeredAt.remove(id)
        toRelease.remove(id)
        awaiting.remove(id)
        handedState.remove(id)
        backoff.remove(id)
        lastState.remove(id)
        ghostIn.remove(id)
    }

    private fun deviceLost(device: DeviceId, at: Double) {
        if (device in lost) return
        lost += device
        lastHeard.remove(device)
        val results = registry.deviceLost(device)
        for (r in results) if (r is OwnershipResult.Accepted) {
            broadcast(Msg.Owner(r.id, r.ownership.owner, r.ownership.epoch))
            offeredAt.remove(r.id)
        }
        remoteScents.remove(device)
        scentsDirty = true
        val next = layoutState.value.without(device)
        layoutState.value = next
        broadcast(Msg.LayoutChanged(next))
        sendHome(device)
        send(Msg.Gone(device), device)
        onEvent(ShareEvent.DeviceLost(at, device, results.count { (it as? OwnershipResult.Accepted)?.ownership?.owner == self }))
        publishStatus()
    }

    // ------------------------------------------------------------ the tick

    /** One exchange with the local simulation, then what goes out. Public so tests can step it. */
    suspend fun tick() {
        val t = sharedNow()
        val local = now()
        if (isHost) housekeeping(local)
        val wasValid = leaseValid()

        // Those decided on last tick stop now, and are offered once stopped.
        val releasing = LinkedHashMap(toRelease)
        toRelease.clear()
        val foodReleasing = LinkedHashMap(foodToRelease)
        foodToRelease.clear()

        // What the simulation should simulate now, against what it does.
        val scene = session.scene.value
        val want = HashSet<EntityId>()
        if (leaseValid() && !left) {
            for (c in scene.creatures) {
                if (mirror[c.id]?.owner == self && c.id !in awaiting && c.id !in releasing) want += c.id
            }
        }
        val release = simOwned.filter { it !in want }
        trace?.let { t -> if (release.isNotEmpty() || want.any { it !in simOwned }) t("tick release ${release.map { it.raw }} take ${want.filter { it !in simOwned }.map { it.raw }}") }
        val take = want.filter { it !in simOwned }.map { id -> Take(id, handedState.remove(id) ?: lastState[id]?.first) }

        val bites = bitesIn.toList()
        bitesIn.clear()
        // What bites got goes to the creature where it is simulated: here, or on to its new owner.
        val fed = ArrayList<Fed>()
        val fedLater = ArrayList<Pair<Fed, Int>>()
        for ((f, hops) in fedIn) {
            val owner = mirror[f.creature]?.owner ?: continue
            when {
                owner == self && f.creature in want && f.creature in simOwned -> fed += f
                owner == self -> if (hops < MAX_FED_HOPS) fedLater += f to hops + 1 // changing hands: wait for the word
                hops < MAX_FED_HOPS -> send(Msg.FedMsg(f, hops + 1), owner)
            }
        }
        fedIn.clear()
        fedIn += fedLater
        val input = SimInput(
            release = release,
            take = take,
            ghosts = ghostIn.values.filter { it.state.id !in want }.toList(),
            ghostFood = foodIn.values.toList(),
            adoptFood = adoptIn.toList(),
            releaseFood = foodReleasing.keys.toList(),
            bites = bites.map { it.second },
            fed = fed,
            scents = if (scentsDirty) remoteScents.values.flatten() else null,
            taps = tapsIn.toList(),
        )
        ghostIn.clear()
        foodIn.clear()
        adoptIn.clear()
        tapsIn.clear()
        scentsDirty = false

        val out = sim.exchange(input)
        ledger = out.ledger
        lastOwned = out.owned
        simOwned = out.owned.mapTo(HashSet()) { it.id }
        for (tk in take) if (tk.id in simOwned) {
            handoffsIn++
            onEvent(ShareEvent.Took(t, tk.id, mirror[tk.id]?.epoch ?: 0))
        }
        for (f in input.adoptFood) {
            foodOwner[f.id] = self
            onEvent(ShareEvent.FoodTaken(t, f.id, f.massMg))
        }

        // Released this exchange: those decided on are offered, now that they have stopped.
        for (st in out.released) {
            lastState[st.id] = st to t
            val to = releasing[st.id]
            onEvent(ShareEvent.Released(t, st.id, to))
            val cur = mirror[st.id] ?: continue
            if (to == null || cur.owner != self || !leaseValid()) continue
            // Gone since it was decided on: nobody to offer it to, so it simply swims on here.
            if (to != self && to !in members()) continue
            awaiting[st.id] = to
            handoffsOut++
            val msg = Msg.Handoff(st.id, self, to, cur.epoch + 1, st.compact(), t)
            val host = hostOf()
            send(msg, host)
            if (to != host) send(msg, to)
        }
        for (f in out.releasedFood) {
            val to = foodReleasing[f.id] ?: continue
            foodOwner[f.id] = to
            send(Msg.FoodHandoff(t, f.compact(), to), to)
            onEvent(ShareEvent.FoodSent(t, f.id, to, f.massMg))
        }
        // Bites decided here go back to whoever asked; bites taken here go to the food's owner.
        out.granted.forEachIndexed { i, fed -> bites.getOrNull(i)?.let { (who, _) -> send(Msg.FedMsg(fed), who) } }
        for (w in out.wants) {
            val owner = foodOwner[w.food] ?: continue
            if (owner != self) send(Msg.Bite(w), owner)
        }

        if (leaseValid() && !left) decideHandoffs(out)
        sendStates(t, out)
        if (!isHost && !left && ticks % config.pingEveryTicks == 0L) send(Msg.Ping(local), hostOf())
        if (wasValid && !leaseValid() && !isHost) onEvent(ShareEvent.Fenced(t, true))
        ticks++
        publishStatus()
        flush()
    }

    private fun housekeeping(local: Double) {
        for ((id, at) in offeredAt.entries.toList()) {
            if (local - at < config.handoffTimeoutS) continue
            offeredAt.remove(id)
            val r = registry.expire(id)
            if (r is OwnershipResult.Accepted) broadcast(Msg.Owner(id, r.ownership.owner, r.ownership.epoch))
        }
        for (d in members()) {
            val heard = lastHeard[d] ?: local.also { lastHeard[d] = it }
            if (local - heard > config.lostS) deviceLost(d, local)
        }
    }

    private fun decideHandoffs(out: SimOutput) {
        val lay = layoutState.value
        val mine = lay.rectOf(self)
        val live = members().toSet()
        val t = now()
        backoff.entries.removeAll { it.value <= t }
        for (st in out.owned) {
            if (st.id in awaiting || st.id in toRelease || st.id in backoff) continue
            if (mine != null && mine.contains(lay.visibleAt(st.at, st.z))) continue
            val to = lay.deviceShowing(st.at, st.z, config.insetMm) ?: continue
            if (to != self && to in live) toRelease[st.id] = to
        }
        for (f in out.food) {
            if (f.id in foodToRelease) continue
            if (mine != null && mine.contains(lay.visibleAt(f.at, f.z))) continue
            val to = lay.deviceShowing(f.at, f.z, config.insetMm) ?: continue
            if (to != self && to in live) foodToRelease[f.id] = to
        }
    }

    private fun sendStates(t: Double, out: SimOutput) {
        val lay = layoutState.value
        val far = ticks % config.farEveryTicks == 0L
        val scentTick = ticks % config.scentEveryTicks == 0L
        val peers = members()
        // Creatures each screen should hear about. Said once to everyone when more than one screen
        // wants news: this device's own link is the narrow part (Bluetooth), the relay fans it out.
        val wanted = peers.associateWith { p -> out.owned.filter { far || lay.near(p, it.at, it.z, config.interestMm) } }
        val askers = wanted.filterValues { it.isNotEmpty() }.keys
        if (askers.size > 1 || (far && peers.size > 1)) {
            val union = out.owned.filter { c -> wanted.values.any { c in it } }
            broadcast(Msg.States(t, union.map { it.compact() }))
        } else {
            for (p in peers) {
                val creatures = wanted.getValue(p)
                if (creatures.isNotEmpty() || far) send(Msg.States(t, creatures.map { it.compact() }), p)
            }
        }
        for (p in peers) {
            val food = out.food.filter { lay.near(p, it.at, it.z, config.interestMm) }.take(config.maxFoodPerMessage).map { it.compact() }
            if (food.isNotEmpty()) send(Msg.Foods(t, food), p)
            if (scentTick) {
                // What is near that screen it sees as food; further off, only as a scent.
                val scents = out.scents.filter { !lay.near(p, it.at, it.z, config.interestMm) }.map { it.compact() }
                send(Msg.Scents(scents), p)
            }
        }
    }

    // ------------------------------------------------------------ out

    /** Sends a tap on this device's glass to the neighbours. */
    suspend fun tapped(at: Vec2) {
        for (p in members()) link.send(Msg.Tap(at), p)
    }

    private val outbox = ArrayList<Pair<Msg, DeviceId?>>()

    private fun send(msg: Msg, to: DeviceId) {
        traceOut(msg, to.slot)
        outbox += msg to to
    }

    private fun broadcast(msg: Msg) {
        traceOut(msg, -1)
        outbox += msg to null
    }

    private fun traceOut(msg: Msg, to: Int) {
        val t = trace ?: return
        when (msg) {
            is Msg.Handoff -> t("out handoff ${msg.id.raw} ${msg.from.slot}->${msg.to.slot} e${msg.epoch} to $to")
            is Msg.Claim -> t("out claim ${msg.id.raw} e${msg.epoch} to $to")
            is Msg.Owner -> t("out owner ${msg.id.raw} ${msg.owner.slot} e${msg.epoch} to $to")
            else -> Unit
        }
    }

    /** Sends what [handle] and [tick] queued: they decide without suspending, then this sends. */
    suspend fun flush() {
        while (outbox.isNotEmpty()) {
            val batch = outbox.toList()
            outbox.clear()
            for ((m, to) in batch) link.send(m, to)
        }
    }

    private fun publishStatus() {
        statusState.value = ShareStatus(
            self = self,
            isHost = isHost,
            devices = layoutState.value.placements.size,
            connected = leaseValid(),
            gone = gone,
            rttMs = if (clock.synced) (clock.rttS * 1000).toFloat() else null,
            owned = simOwned.size,
            handoffsOut = handoffsOut,
            handoffsIn = handoffsIn,
        )
    }

    companion object {
        const val HOST_SLOT = 0
        /** A bite's answer chases its creature across at most this many hand-overs (or ticks). */
        private const val MAX_FED_HOPS = 12

        /**
         * Asks the host of the session [link] is in to let this device in:
         * sends its screen, waits for the lagoon. Null if no answer in time.
         */
        suspend fun requestJoin(link: Link, screen: ScreenMm, timeoutMs: Long, brings: Visitors? = null): Msg.Join? = withTimeoutOrNull(timeoutMs) {
            val host = link.peers.first { peers -> peers.any { it.slot == HOST_SLOT } }.first { it.slot == HOST_SLOT }
            link.send(Msg.Hello(link.self, screen, brings), host)
            link.incoming.first { it.msg is Msg.Join }.msg as Msg.Join
        }

        /** The layout of a lagoon shared by one: the host's screen in the middle of its tank. */
        fun soloLayout(self: DeviceId, screen: ScreenMm, tank: TankSize): Layout =
            Layout.sideBySide(listOf(self to screen), tank)
    }
}
