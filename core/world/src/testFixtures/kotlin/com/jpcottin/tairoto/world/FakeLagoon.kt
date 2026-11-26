package com.jpcottin.tairoto.world

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Watches every device's simulation at once: no creature may ever be simulated by two. */
class ExactlyOnce {
    private val simulating = HashMap<EntityId, String>()
    val violations = ArrayList<String>()
    var takes = 0
        private set

    fun take(id: EntityId, by: String) {
        val cur = simulating[id]
        if (cur != null && cur != by) violations += "$id simulated by $cur and $by at once"
        simulating[id] = by
        takes++
    }

    fun release(id: EntityId, by: String) {
        if (simulating[id] == by) simulating.remove(id)
    }

    fun simulator(id: EntityId): String? = simulating[id]
}

/**
 * A stand-in for the native simulation: creatures swim straight and bounce
 * off the walls, food drifts, and an owned creature bites any food it passes
 * over. Enough to drive every path of the shared lagoon.
 */
class FakeSim(
    val name: String,
    private val slot: Int,
    private val tank: TankSize,
    private val monitor: ExactlyOnce,
    seed: Int,
) : SharedSim {
    class Body(var x: Float, var y: Float, val z: Float, var heading: Float, var speed: Float, var owned: Boolean = false) {
        var hunger = 0.5f
        var eaten = 0f
        var cooldown = 0f
    }

    class Bit(var x: Float, var y: Float, var vx: Float, var vy: Float, var mass: Float, var owned: Boolean, var seen: Double = 0.0)

    private val random = Random(seed)
    val bodies = LinkedHashMap<EntityId, Body>()
    val food = LinkedHashMap<Long, Bit>()
    private var nextFood = 1L
    private val wants = ArrayList<BiteWant>()
    var ledger = FoodLedger()
        private set
    var frozen = false
    val problems = ArrayList<String>()
    var time = 0.0
    var exchanges = 0
        private set
    /** The scents of other devices' food, as last given. */
    var lastScents: List<NetScent>? = null
        private set

    /** Keeps level with the scene: new creatures arrive as ghosts, gone ones leave. */
    fun sync(scene: TankScene) {
        val ids = scene.creatures.mapTo(HashSet()) { it.id }
        for (c in scene.creatures) {
            if (c.id !in bodies) {
                bodies[c.id] = Body(c.spawnAt.x, c.spawnAt.y, c.spawnDepthMm, random.nextFloat() * 2 * PI.toFloat(), 18f + random.nextFloat() * 14f)
            }
        }
        for (id in bodies.keys.toList()) if (id !in ids) {
            monitor.release(id, name)
            bodies.remove(id)
        }
    }

    fun drop(at: Vec2, massMg: Float, velocity: Vec2) {
        val id = (slot.toLong() shl 24) or nextFood++
        food[id] = Bit(at.x, at.y, velocity.x, velocity.y, massMg, owned = true)
        ledger = ledger.copy(dropped = ledger.dropped + massMg)
    }

    fun advance(dt: Float) {
        time += dt
        if (frozen) return
        for (b in bodies.values) {
            if (b.cooldown > 0) b.cooldown -= dt
            if (!b.owned) {
                // A ghost drifts on (dead reckoning) until the next state.
                b.x += cos(b.heading) * b.speed * dt
                b.y += sin(b.heading) * b.speed * dt
                continue
            }
            if (random.nextFloat() < 0.4f * dt) b.heading += (random.nextFloat() - 0.5f) * 1.5f
            b.x += cos(b.heading) * b.speed * dt
            b.y += sin(b.heading) * b.speed * dt
            if (b.x < 0f || b.x > tank.widthMm) { b.heading = PI.toFloat() - b.heading; b.x = b.x.coerceIn(0f, tank.widthMm) }
            if (b.y < 0f || b.y > tank.heightMm) { b.heading = -b.heading; b.y = b.y.coerceIn(0f, tank.heightMm) }
        }
        for (f in food.values) {
            if (!f.owned) continue
            f.x += f.vx * dt
            f.y += f.vy * dt
            if (f.x < 0f || f.x > tank.widthMm) { f.vx = -f.vx; f.x = f.x.coerceIn(0f, tank.widthMm) }
            if (f.y < 0f || f.y > tank.heightMm) { f.vy = -f.vy; f.y = f.y.coerceIn(0f, tank.heightMm) }
        }
        // Owned creatures bite what they pass over: their own food at once, another's by asking.
        for ((id, b) in bodies) {
            if (!b.owned || b.cooldown > 0) continue
            val (fid, f) = food.entries.firstOrNull { (_, f) -> f.mass > 0 && hypot(f.x - b.x, f.y - b.y) < BITE_REACH_MM }?.toPair() ?: continue
            b.cooldown = 0.3f
            if (f.owned) {
                val got = min(f.mass, BITE_MG)
                f.mass -= got
                b.eaten += got
                ledger = ledger.copy(eaten = ledger.eaten + got)
            } else {
                wants += BiteWant(fid, id, BITE_MG, 0.2f)
            }
        }
        food.entries.removeAll { (_, f) -> f.owned && f.mass <= 1e-6f }
    }

    override suspend fun exchange(input: SimInput): SimOutput {
        exchanges++
        val released = ArrayList<NetCreature>()
        for (id in input.release) {
            val b = bodies[id] ?: continue
            if (!b.owned) continue
            b.owned = false
            monitor.release(id, name)
            released += state(id, b)
        }
        for (t in input.take) {
            val b = bodies[t.id] ?: continue
            if (b.owned) continue
            monitor.take(t.id, name)
            b.owned = true
            t.state?.let {
                b.x = it.x; b.y = it.y; b.heading = it.heading; b.speed = it.speed
                b.hunger = it.hunger; b.eaten = it.eatenMg
            }
        }
        for (g in input.ghosts) {
            val b = bodies[g.state.id] ?: continue
            if (b.owned) continue
            b.heading = g.state.heading
            b.speed = g.state.speed
            b.x = g.state.x + cos(b.heading) * b.speed * g.ageS
            b.y = g.state.y + sin(b.heading) * b.speed * g.ageS
        }
        for (u in input.ghostFood) {
            val cur = food[u.food.id]
            if (cur?.owned == true) continue
            food[u.food.id] = Bit(u.food.x + u.food.vx * u.ageS, u.food.y + u.food.vy * u.ageS, u.food.vx, u.food.vy, u.food.massMg, owned = false, seen = time)
        }
        for (f in input.adoptFood) {
            if (food[f.id]?.owned == true) problems += "$name adopted ${f.id} it already owned"
            food[f.id] = Bit(f.x, f.y, f.vx, f.vy, f.massMg, owned = true)
            ledger = ledger.copy(received = ledger.received + f.massMg)
        }
        val releasedFood = ArrayList<NetFood>()
        for (id in input.releaseFood) {
            val f = food[id] ?: continue
            if (!f.owned) continue
            f.owned = false
            f.seen = time
            ledger = ledger.copy(sent = ledger.sent + f.mass)
            releasedFood += netFood(id, f)
        }
        val granted = input.bites.map { w ->
            val f = food[w.food]
            if (f == null || !f.owned) {
                Fed(w.creature, 0f)
            } else {
                val got = min(f.mass, w.wantMg)
                f.mass -= got
                ledger = ledger.copy(eaten = ledger.eaten + got)
                Fed(w.creature, got)
            }
        }
        food.entries.removeAll { (_, f) -> f.owned && f.mass <= 1e-6f }
        input.scents?.let { lastScents = it }
        for (fed in input.fed) bodies[fed.creature]?.takeIf { it.owned }?.let { it.eaten += fed.mg }
        // Ghost food nobody has mentioned for a second is gone (eaten, or out of sight).
        food.entries.removeAll { (_, f) -> !f.owned && time - f.seen > 1.0 }
        val out = SimOutput(
            owned = bodies.filter { it.value.owned }.map { (id, b) -> state(id, b) },
            released = released,
            food = food.filter { it.value.owned }.map { (id, f) -> netFood(id, f) },
            releasedFood = releasedFood,
            scents = food.filter { it.value.owned }.map { (_, f) -> NetScent(f.x, f.y, 0f, f.mass, 0f) },
            wants = wants.toList(),
            granted = granted,
            ledger = ledger.copy(inWater = food.values.filter { it.owned }.sumOf { it.mass.toDouble() }),
        )
        wants.clear()
        return out
    }

    /** The ledger with the food in the water counted now. */
    fun ledgerNow(): FoodLedger = ledger.copy(inWater = food.values.filter { it.owned }.sumOf { it.mass.toDouble() })

    fun ownedCount(): Int = bodies.values.count { it.owned }
    fun eatenByCreatures(): Double = bodies.values.filter { it.owned }.sumOf { it.eaten.toDouble() }

    private fun state(id: EntityId, b: Body) = NetCreature(id, b.x, b.y, b.z, b.heading, b.speed, hunger = b.hunger, eatenMg = b.eaten)
    private fun netFood(id: Long, f: Bit) = NetFood(id, 0, 0, f.x, f.y, 0f, f.vx, f.vy, 0f, f.mass)

    companion object {
        const val BITE_REACH_MM = 6f
        const val BITE_MG = 0.25f
    }
}

/** One device of a lagoon under test. */
class Device(val link: Link, val sim: FakeSim, val clockOffsetS: Double) {
    var session: TankSession? = null
    var shared: SharedTank? = null
    val events = ArrayList<ShareEvent>()
    var job: Job? = null
}

/**
 * Several devices on a [FakeNetwork], each with its session, its shared tank
 * and a [FakeSim] stepped at 60 Hz. Every device's clock is off from the
 * others by its own offset, as on real devices.
 */
class FakeLagoon(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    /** The network, when it is a fake one (tests cut it); null over a real relay. */
    val net: FakeNetwork?,
    private val screens: List<ScreenMm>,
    private val scene: (TankSize) -> TankScene,
    private val config: ShareConfig = ShareConfig(),
    /** How device [i] gets its link: the first is the host. */
    private val connect: suspend (i: Int, name: String) -> Link = { _, name -> net!!.join(name) },
) {
    val monitor = ExactlyOnce()
    val devices = ArrayList<Device>()
    lateinit var tank: TankSize

    fun now(d: Device): Double = nowMs() / 1000.0 + d.clockOffsetS

    /** Starts the host, then has every other device join. */
    suspend fun start() {
        val host = connect(0, "dev0")
        val base = TankSize(200f, 200f, TankSize.DEFAULT_DEPTH_MM)
        val first = Layout.sideBySide(listOf(host.self to screens[0]), base)
        val all = Layout.sideBySide(screens.mapIndexed { i, s -> DeviceId(i, "dev$i") to s }, base)
        tank = all.tank
        val sc = scene(tank)
        val d0 = Device(host, FakeSim("dev0", 0, tank, monitor, 100), 0.0)
        devices += d0
        val s0 = TankSession(sc, host, Random(1))
        d0.session = s0
        d0.shared = SharedTank(s0, host, d0.sim, { now(d0) }, first, emptyList(), config) { d0.events += it }
        d0.job = scope.launch { d0.shared!!.run() }
        drive(d0)
        for (i in 1 until screens.size) {
            val link = connect(i, "dev$i")
            val d = Device(link, FakeSim("dev$i", i, tank, monitor, 100 + i), clockOffsetS = 1000.0 * i + 0.37 * i)
            devices += d
            val join = SharedTank.requestJoin(link, screens[i], 5_000) ?: error("dev$i got no answer")
            val s = TankSession(join.scene, link, Random(10 + i))
            d.session = s
            d.shared = SharedTank(s, link, d.sim, { now(d) }, join.layout, join.owners, config) { d.events += it }
            d.job = scope.launch { d.shared!!.run() }
            drive(d)
        }
    }

    private fun drive(d: Device) {
        scope.launch {
            while (isActive) {
                d.session?.let { d.sim.sync(it.scene.value) }
                d.sim.advance(1f / 60f)
                delay(16)
            }
        }
    }

    /** Every creature of the scene, and who simulates it. */
    fun simulators(): Map<EntityId, List<String>> {
        val ids = devices.first().session!!.scene.value.creatures.map { it.id }
        return ids.associateWith { id -> devices.filter { it.sim.bodies[id]?.owned == true }.map { it.sim.name } }
    }

    fun totalLedger(): FoodLedger = devices.fold(FoodLedger()) { acc, d -> acc + d.sim.ledgerNow() }
}
