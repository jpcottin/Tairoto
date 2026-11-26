package com.jpcottin.tairoto.world

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

class SharedTankTest {
    private val phone = ScreenMm(68f, 150f)
    private val fold = ScreenMm(118f, 150f)

    private fun chromis(n: Int, seed: Int = 4): (TankSize) -> TankScene = { size ->
        TankScene(size, Population.release(SpeciesCatalog.GreenChromis, n, size, EntityIdAllocator(0), Random(seed)).map {
            // Spread out over the whole table, not schooled in one spot.
            val r = Random(it.seed)
            it.copy(spawnAt = Vec2(10f + r.nextFloat() * (size.widthMm - 20f), 10f + r.nextFloat() * (size.heightMm - 20f)))
        })
    }

    private fun TestScope.lagoon(
        screens: List<ScreenMm>,
        scene: (TankSize) -> TankScene,
        latencyMs: Long = 15,
        jitterMs: Long = 10,
    ): FakeLagoon {
        val net = FakeNetwork(backgroundScope, { testScheduler.currentTime }, latencyMs, jitterMs)
        return FakeLagoon(backgroundScope, { testScheduler.currentTime }, net, screens, scene)
    }

    private fun FakeLagoon.assertNeverTwice() =
        assertTrue(monitor.violations.take(5).joinToString("\n"), monitor.violations.isEmpty())

    private fun FakeLagoon.assertEachSimulatedOnce() {
        val sims = simulators()
        val wrong = sims.filter { it.value.size != 1 }
        assertTrue("not simulated exactly once: $wrong", wrong.isEmpty())
    }

    @Test fun `a device joining gets the lagoon, who owns what and where every screen lies`() = runTest {
        val lagoon = lagoon(listOf(phone, phone), chromis(10))
        lagoon.start()
        advanceTimeBy(1_000)
        val (host, peer) = lagoon.devices
        assertEquals(host.session!!.scene.value.creatures.map { it.id }, peer.session!!.scene.value.creatures.map { it.id })
        val layout = peer.shared!!.layout.value
        assertEquals(2, layout.placements.size)
        assertEquals(layout, host.shared!!.layout.value)
        // Side by side, a gap between them, the tank around both.
        val (a, b) = layout.placements.map { it.rect }
        assertEquals(Layout.GAP_MM, b.x - a.right, 0.01f)
        assertTrue(layout.tank.widthMm > b.right && layout.tank.heightMm > a.bottom)
        assertEquals(layout.tank, peer.session!!.scene.value.size)
        assertEquals(layout.tank, host.session!!.scene.value.size)
        peer.session!!.scene.value.creatures.forEach { assertEquals(host.shared!!.ownerOf(it.id), peer.shared!!.ownerOf(it.id)) }
    }

    @Test fun `the shared clock is the host's, whatever each device's own clock says`() = runTest {
        val lagoon = lagoon(listOf(phone, phone, phone), chromis(4), latencyMs = 40, jitterMs = 30)
        lagoon.start()
        advanceTimeBy(5_000)
        val host = lagoon.devices[0].shared!!
        for (d in lagoon.devices.drop(1)) {
            // 40-70 ms each way: the estimate is within the jitter, not off by the devices' 1000 s.
            assertEquals(host.sharedNow(), d.shared!!.sharedNow(), 0.035)
        }
    }

    @Test fun `a fish swimming onto the next screen changes hands, once`() = runTest {
        val lagoon = lagoon(listOf(phone, phone), { size -> TankScene(size, listOf(CreatureSpec(EntityId(1), "green_chromis", 3, 20f, Vec2(0f, 0f), 20f))) })
        lagoon.start()
        val (host, peer) = lagoon.devices
        advanceTimeBy(500)
        val layout = host.shared!!.layout.value
        val start = layout.rectOf(host.link.self)!!.center
        val fish = host.sim.bodies.getValue(EntityId(1))
        fish.x = start.x; fish.y = start.y; fish.heading = 0f; fish.speed = 30f
        assertEquals("dev0", lagoon.monitor.simulator(EntityId(1)))
        advanceTimeBy(4_000)
        assertEquals("dev1", lagoon.monitor.simulator(EntityId(1)))
        assertEquals(peer.link.self, host.shared!!.ownerOf(EntityId(1)))
        assertEquals(peer.link.self, peer.shared!!.ownerOf(EntityId(1)))
        assertEquals(1, peer.events.count { it is ShareEvent.Took })
        assertEquals(1, host.events.count { it is ShareEvent.Released && it.to == peer.link.self })
        // It carried on from where it was, not from where it was released.
        assertTrue(peer.sim.bodies.getValue(EntityId(1)).x > layout.rectOf(peer.link.self)!!.x)
        lagoon.assertNeverTwice()
    }

    @Test fun `exactly once, with three screens, sixty fish and a jittery network`() = runTest(timeout = 2.minutes) {
        val lagoon = lagoon(listOf(phone, fold, phone), chromis(60), latencyMs = 30, jitterMs = 60)
        lagoon.start()
        advanceTimeBy(60_000)
        // Hold still, so the offers on the way are settled.
        lagoon.devices.forEach { it.sim.frozen = true }
        advanceTimeBy(1_500)
        lagoon.assertNeverTwice()
        lagoon.assertEachSimulatedOnce()
        val handoffs = lagoon.devices.sumOf { d -> d.events.count { it is ShareEvent.Took } }
        assertTrue("only $handoffs handoffs", handoffs > 60)
        // Every device simulated some of them at the end.
        lagoon.devices.forEach { assertTrue("${it.sim.name} owns none", it.sim.ownedCount() > 0) }
    }

    @Test fun `an offer nobody answers is cancelled and the fish carries on where it was`() = runTest {
        val lagoon = lagoon(listOf(phone, phone), { size -> TankScene(size, listOf(CreatureSpec(EntityId(1), "green_chromis", 3, 20f, Vec2(0f, 0f), 20f))) })
        lagoon.start()
        val (host, peer) = lagoon.devices
        advanceTimeBy(500)
        // The peer hears nothing more from the host, so it never sees the offer.
        lagoon.net!!.setCut(host.link.self, peer.link.self, isCut = true, bothWays = false)
        val layout = host.shared!!.layout.value
        val fish = host.sim.bodies.getValue(EntityId(1))
        val r = layout.rectOf(peer.link.self)!!
        fish.x = r.center.x; fish.y = r.center.y; fish.speed = 0f
        advanceTimeBy(300)
        // Offered: nobody simulates it for now.
        assertEquals(null, lagoon.monitor.simulator(EntityId(1)))
        advanceTimeBy(700)
        // The offer ran out: back with the host.
        assertEquals("dev0", lagoon.monitor.simulator(EntityId(1)))
        assertEquals(host.link.self, host.shared!!.ownerOf(EntityId(1)))
        lagoon.assertNeverTwice()
    }

    @Test fun `a device cut off stops simulating before the host takes its fish over`() = runTest(timeout = 2.minutes) {
        val lagoon = lagoon(listOf(phone, phone, phone), chromis(40))
        lagoon.start()
        advanceTimeBy(8_000)
        val (host, peer, _) = lagoon.devices
        assertTrue(peer.sim.ownedCount() > 0)
        lagoon.net!!.setCut(host.link.self, peer.link.self, isCut = true)
        advanceTimeBy(1_500)
        assertTrue("still connected before its lease ran out", peer.shared!!.status.value.connected)
        advanceTimeBy(500)
        // Its lease ran out: it simulates nothing, and the host has not taken anything over yet.
        assertEquals(0, peer.sim.ownedCount())
        assertTrue(host.events.none { it is ShareEvent.DeviceLost })
        advanceTimeBy(1_000)
        val lost = host.events.filterIsInstance<ShareEvent.DeviceLost>().single()
        assertEquals(peer.link.self, lost.device)
        advanceTimeBy(4_000)
        lagoon.assertNeverTwice()
        // Everything is simulated once, by the host and the other screen.
        val ids = host.session!!.scene.value.creatures.map { it.id }
        ids.forEach { id -> assertTrue("$id", lagoon.monitor.simulator(id) in setOf("dev0", "dev2")) }
        assertEquals(2, host.shared!!.layout.value.placements.size)
    }

    @Test fun `a device that can hear the host but not reach it stops simulating too`() = runTest(timeout = 2.minutes) {
        val lagoon = lagoon(listOf(phone, phone), chromis(30))
        lagoon.start()
        advanceTimeBy(6_000)
        val (host, peer) = lagoon.devices
        assertTrue(peer.sim.ownedCount() > 0)
        lagoon.net!!.setCut(peer.link.self, host.link.self, isCut = true, bothWays = false)
        advanceTimeBy(2_000)
        assertEquals(0, peer.sim.ownedCount())
        advanceTimeBy(4_000)
        assertTrue(host.events.any { it is ShareEvent.DeviceLost })
        assertTrue(peer.shared!!.status.value.gone)
        lagoon.assertNeverTwice()
        lagoon.assertEachSimulatedOnce()
    }

    @Test fun `a device that leaves hands everything over at once`() = runTest {
        val lagoon = lagoon(listOf(phone, phone), chromis(30))
        lagoon.start()
        advanceTimeBy(6_000)
        val (host, peer) = lagoon.devices
        assertTrue(peer.sim.ownedCount() > 0)
        peer.shared!!.leave()
        runCurrent()
        advanceTimeBy(300)
        assertEquals(0, peer.sim.ownedCount())
        assertTrue(host.events.any { it is ShareEvent.DeviceLost })
        advanceTimeBy(300)
        lagoon.assertNeverTwice()
        assertTrue(host.session!!.scene.value.creatures.all { lagoon.monitor.simulator(it.id) == "dev0" })
    }

    @Test fun `creatures added or netted on any screen are added or netted on all`() = runTest {
        val lagoon = lagoon(listOf(phone, phone), chromis(4))
        lagoon.start()
        advanceTimeBy(1_000)
        val (host, peer) = lagoon.devices
        peer.session!!.add("green_sea_turtle", 1, around = peer.shared!!.layout.value.rectOf(peer.link.self)!!.center)
        advanceTimeBy(500)
        val turtle = host.session!!.scene.value.creatures.single { it.speciesId == "green_sea_turtle" }
        assertEquals(peer.link.self, host.shared!!.ownerOf(turtle.id))
        assertEquals("dev1", lagoon.monitor.simulator(turtle.id))
        host.session!!.net(turtle.id)
        advanceTimeBy(500)
        assertTrue(peer.session!!.scene.value.creatures.none { it.id == turtle.id })
        assertEquals(null, lagoon.monitor.simulator(turtle.id))
    }

    @Test fun `food drifting across screens and eaten on all of them is never lost or doubled`() = runTest(timeout = 2.minutes) {
        val lagoon = lagoon(listOf(phone, fold, phone), chromis(50), latencyMs = 20, jitterMs = 30)
        lagoon.start()
        advanceTimeBy(2_000)
        val rnd = Random(9)
        repeat(40) { i ->
            val d = lagoon.devices[i % 3]
            val r = d.shared!!.layout.value.rectOf(d.link.self)!!
            d.sim.drop(Vec2(r.x + rnd.nextFloat() * r.w, r.y + rnd.nextFloat() * r.h), 2f, Vec2((rnd.nextFloat() - 0.5f) * 40f, (rnd.nextFloat() - 0.5f) * 10f))
            advanceTimeBy(500)
        }
        advanceTimeBy(10_000)
        // Stop everything, let what is on the way arrive.
        lagoon.devices.forEach { it.sim.frozen = true }
        advanceTimeBy(2_000)
        val total = lagoon.totalLedger()
        lagoon.devices.forEach { assertTrue(it.sim.problems.joinToString(), it.sim.problems.isEmpty()) }
        assertEquals(80.0, total.dropped, 1e-3)
        assertEquals("sent and received", total.sent, total.received, 1e-3)
        assertEquals("dropped = eaten + in the water", total.dropped, total.eaten + total.inWater, 1e-3)
        assertTrue("food crossed screens", total.sent > 1.0)
        assertTrue("some was eaten", total.eaten > 1.0)
        // What the food's owners count as eaten is what the creatures were credited, to the bite in flight.
        val credited = lagoon.devices.sumOf { it.sim.eatenByCreatures() }
        assertEquals(total.eaten, credited, 0.5)
        assertTrue("bites across screens", lagoon.devices.any { d -> d.sim.exchanges > 0 } && lagoon.net!!.sent > 0)
        lagoon.assertNeverTwice()
    }

    @Test fun `a bite at food another device owns is decided by that device`() = runTest {
        val lagoon = lagoon(listOf(phone, phone), { size -> TankScene(size, listOf(CreatureSpec(EntityId(1), "green_chromis", 3, 20f, Vec2(0f, 0f), 20f))) })
        lagoon.start()
        val (host, peer) = lagoon.devices
        advanceTimeBy(500)
        val layout = host.shared!!.layout.value
        val edge = layout.rectOf(host.link.self)!!
        // The fish (the host's) hovers at the right edge of the host's screen; food lies just across, on the peer's.
        val fish = host.sim.bodies.getValue(EntityId(1))
        fish.x = edge.right - 1f; fish.y = edge.center.y; fish.speed = 0f
        val across = layout.rectOf(peer.link.self)!!
        // A gap lies between, so put the food within reach, owned by the peer (dropped there and drifted).
        peer.sim.drop(Vec2(edge.right + 2f, edge.center.y), 3f, Vec2.Zero)
        advanceTimeBy(3_000)
        assertTrue(across.x > edge.right)
        val peerLedger = peer.sim.ledgerNow()
        assertTrue("the peer counted it eaten: $peerLedger", peerLedger.eaten > 0.5)
        assertEquals(0.0, host.sim.ledgerNow().eaten, 1e-6)
        assertTrue("the fish was credited", fish.eaten > 0.5)
        assertEquals(peerLedger.eaten, fish.eaten.toDouble(), 0.3)
    }

    @Test fun `a screen near food sees the food, one further off only smells it`() = runTest {
        val lagoon = lagoon(listOf(phone, phone, phone), chromis(1))
        lagoon.start()
        advanceTimeBy(500)
        val (host, near, far) = lagoon.devices
        val r = host.shared!!.layout.value.rectOf(host.link.self)!!
        host.sim.drop(Vec2(r.right - 5f, r.center.y), 2f, Vec2.Zero)
        advanceTimeBy(1_500)
        assertTrue("the next screen has it as food", near.sim.food.values.any { !it.owned })
        assertTrue("the far screen does not", far.sim.food.isEmpty())
        assertTrue("the far screen smells it", far.sim.lastScents.orEmpty().any { it.waterMg > 1f })
        assertTrue("the next screen is not sent it twice", near.sim.lastScents.orEmpty().isEmpty())
    }

    @Test fun `exactly once, while the network keeps breaking and mending`() = runTest(timeout = 3.minutes) {
        for (seed in 1..4) {
            val net = FakeNetwork(backgroundScope, { testScheduler.currentTime }, latencyMs = 20, jitterMs = 80, seed = seed)
            val lagoon = FakeLagoon(backgroundScope, { testScheduler.currentTime }, net, listOf(phone, fold, phone), chromis(40, seed))
            lagoon.start()
            val rnd = Random(seed)
            val ids = lagoon.devices.map { it.link.self }
            repeat(30) {
                val a = ids.random(rnd)
                val b = (ids - a).random(rnd)
                net.setCut(a, b, isCut = rnd.nextInt(3) > 0, bothWays = rnd.nextBoolean())
                advanceTimeBy(300L + rnd.nextLong(2_700))
                lagoon.assertNeverTwice()
            }
            for (a in ids) for (b in ids) if (a != b) net.setCut(a, b, isCut = false)
            lagoon.devices.forEach { it.sim.frozen = true }
            advanceTimeBy(4_000)
            lagoon.assertNeverTwice()
            lagoon.assertEachSimulatedOnce()
            lagoon.devices.forEach { it.job?.cancel() }
        }
    }
}
