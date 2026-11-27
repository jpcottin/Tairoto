package com.jpcottin.tairoto.world

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Arranging a shared lagoon's screens from any device, and what guests bring of their own lagoons. */
class SharedArrangeTest {
    private val phone = ScreenMm(68f, 150f)

    private fun fish(n: Int): (TankSize) -> TankScene = { size ->
        TankScene(size, Population.release(SpeciesCatalog.GreenChromis, n, size, EntityIdAllocator(0), Random(3)))
    }

    private fun TestScope.lagoon(n: Int, brings: (Int) -> Visitors? = { null }): FakeLagoon {
        val net = FakeNetwork(backgroundScope, { testScheduler.currentTime }, 15, 10)
        return FakeLagoon(backgroundScope, { testScheduler.currentTime }, net, List(n) { phone }, fish(6), brings = brings)
    }

    private fun FakeLagoon.layouts() = devices.map { it.shared!!.layout.value }

    @Test fun `a guest drags a screen on its map, and every device sees the table the same new way`() = runTest {
        val lagoon = lagoon(3)
        lagoon.start()
        advanceTimeBy(800)
        val (host, a, b) = lagoon.devices
        val before = host.shared!!.layout.value
        val ra = before.rectOf(a.link.self)!!
        // b goes below a, turned a quarter.
        a.shared!!.arrange(b.link.self, Vec2(ra.center.x, ra.bottom + 6f + phone.widthMm / 2f), 1)
        advanceTimeBy(500)
        val after = lagoon.layouts()
        assertTrue("all agree", after.all { it == after[0] })
        val pb = after[0].placement(b.link.self)!!
        assertEquals(1, pb.turns)
        assertEquals(2 * ScreenMm.PHONE_BEZEL_MM, pb.rect.y - after[0].rectOf(a.link.self)!!.bottom, 1e-3f)
        // Every session's tank covers the new table.
        for (d in lagoon.devices) assertEquals(after[0].tank, d.session!!.scene.value.size)
    }

    @Test fun `a finger swiped off one guest's screen onto another's lays the second beside the first`() = runTest {
        val lagoon = lagoon(3)
        lagoon.start()
        advanceTimeBy(800)
        val (host, a, b) = lagoon.devices
        // Right of b's screen, onto a's top edge: a turns so its top faces b.
        b.shared!!.edgeSwipe(Edge.RIGHT, 50f, entering = false)
        advanceTimeBy(120)
        a.shared!!.edgeSwipe(Edge.TOP, 30f, entering = true)
        advanceTimeBy(500)
        val l = host.shared!!.layout.value
        assertTrue(lagoon.layouts().all { it == l })
        val pa = l.placement(a.link.self)!!
        val pb = l.placement(b.link.self)!!
        assertEquals(Edge.LEFT, Edge.TOP.turned(pa.turns))
        val out = pb.toTable(Vec2(68f, 50f))
        val into = pa.toTable(Vec2(30f, 0f))
        assertEquals(out.x + 2 * ScreenMm.PHONE_BEZEL_MM, into.x, 1e-2f)
        assertEquals(out.y, into.y, 1e-2f)
        assertTrue(host.events.any { it is ShareEvent.Stitched && it.from == b.link.self && it.to == a.link.self })
    }

    @Test fun `swipes too far apart in time, or both leaving, are no swipe across`() = runTest {
        val lagoon = lagoon(2)
        lagoon.start()
        advanceTimeBy(800)
        val (host, a) = lagoon.devices
        val before = host.shared!!.layout.value
        host.shared!!.edgeSwipe(Edge.RIGHT, 50f, entering = false)
        advanceTimeBy(2_000)
        a.shared!!.edgeSwipe(Edge.TOP, 30f, entering = true)
        advanceTimeBy(300)
        host.shared!!.edgeSwipe(Edge.LEFT, 50f, entering = false)
        a.shared!!.edgeSwipe(Edge.LEFT, 50f, entering = false)
        advanceTimeBy(500)
        assertEquals(before, host.shared!!.layout.value)
        assertFalse(host.events.any { it is ShareEvent.Stitched })
    }

    @Test fun `arranging is said to every device, and so is its end`() = runTest {
        val lagoon = lagoon(2)
        lagoon.start()
        advanceTimeBy(800)
        val (_, a) = lagoon.devices
        a.shared!!.setArranging(true)
        advanceTimeBy(300)
        assertTrue(lagoon.layouts().all { it.arranging })
        a.shared!!.setArranging(false)
        advanceTimeBy(300)
        assertTrue(lagoon.layouts().none { it.arranging })
    }

    @Test fun `a guest's own fish and coral come along under its screen, are simulated there, and go home with it`() = runTest {
        val home = TankSize(200f, 300f, TankSize.DEFAULT_DEPTH_MM)
        val ids = EntityIdAllocator(0) // the guest's own lagoon counts from 1 too
        val brought = Visitors(
            creatures = Population.release(SpeciesCatalog.GreenChromis, 3, home, ids, Random(9)).map { it.copy(spawnAt = Vec2(5f, -10f)) },
            decor = listOf(DecorSpec(ids.allocate(), "coral_head", 4, Vec2(-20f, 30f), 0.5f, 0.4f)),
        )
        val lagoon = lagoon(2) { i -> if (i == 1) brought else null }
        lagoon.start()
        advanceTimeBy(1_500)
        val (host, guest) = lagoon.devices
        val scene = host.session!!.scene.value
        val visitors = scene.creatures.filter { it.home == guest.link.self.slot }
        assertEquals(3, visitors.size)
        assertEquals(9, scene.creatures.size)
        assertEquals("ids all different", scene.creatures.size, scene.creatures.map { it.id }.toSet().size)
        val coral = scene.decor.single { it.home == guest.link.self.slot }
        // Where they were on the guest's own screen: under its screen now.
        val pg = host.shared!!.layout.value.placement(guest.link.self)!!
        assertEquals(pg.rect.center.x - 20f, coral.at.x, 1e-3f)
        assertEquals(pg.rect.center.y + 30f, coral.at.y, 1e-3f)
        for (v in visitors) assertTrue(pg.rect.contains(v.spawnAt))
        // The guest has them too, and simulates them.
        assertEquals(scene.creatures.map { it.id }.toSet(), guest.session!!.scene.value.creatures.map { it.id }.toSet())
        for (v in visitors) assertEquals(guest.link.self, host.shared!!.ownerOf(v.id))
        assertTrue(visitors.all { guest.sim.bodies[it.id]?.owned == true })
        // It leaves: they go with it, the host's own stay.
        guest.shared!!.leave()
        advanceTimeBy(1_000)
        val after = host.session!!.scene.value
        assertEquals(6, after.creatures.size)
        assertTrue(after.creatures.none { it.home != null } && after.decor.none { it.home != null })
        assertTrue(lagoon.monitor.violations.isEmpty())
    }

    @Test fun `a guest that brings more than there is room for brings what fits`() = runTest {
        val home = TankSize(200f, 300f, TankSize.DEFAULT_DEPTH_MM)
        val brought = Visitors(creatures = Population.release(SpeciesCatalog.GreenChromis, TankScene.MAX_CREATURES, home, EntityIdAllocator(0), Random(1)))
        val lagoon = lagoon(2) { i -> if (i == 1) brought else null }
        lagoon.start()
        advanceTimeBy(1_000)
        assertEquals(TankScene.MAX_CREATURES, lagoon.devices[0].session!!.scene.value.creatures.size)
        assertEquals(TankScene.MAX_CREATURES, lagoon.devices[1].session!!.scene.value.creatures.size)
    }
}
