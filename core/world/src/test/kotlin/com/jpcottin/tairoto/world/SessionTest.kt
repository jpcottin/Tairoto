package com.jpcottin.tairoto.world

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SessionTest {
    private val size = TankSize(220f, 330f, 110f)
    private fun emptySession(link: Link = LoopbackLink()) = TankSession(TankScene(size), link, Random(3))

    @Test fun `messages survive the wire format`() {
        val spec = CreatureSpec(EntityId(5), "green_chromis", 77, 22f, Vec2(1f, 2f), 30f)
        val msgs = listOf(
            Msg.Hello(DeviceId(1, "a"), ScreenMm(65f, 145f)),
            Msg.Spawn(spec, DeviceId.Solo),
            Msg.Remove(EntityId(5)),
            Msg.Handoff(EntityId(5), DeviceId(1, "a"), DeviceId(2, "b"), 3),
            Msg.Claim(EntityId(5), DeviceId(2, "b"), 3),
            Msg.Owner(EntityId(5), DeviceId(2, "b"), 3),
            Msg.Reset(TankScene(size, listOf(spec))),
        )
        msgs.forEach { assertEquals(it, Wire.decode(Wire.encode(it))) }
    }

    @Test fun `the wire format ignores fields from a newer version`() {
        val text = Wire.encode(Msg.Remove(EntityId(9))).dropLast(1) + ",\"future\":1}"
        assertEquals(Msg.Remove(EntityId(9)), Wire.decode(text))
    }

    @Test fun `loopback delivers to this device and drops messages for others`() = runTest {
        val link = LoopbackLink()
        val got = mutableListOf<Envelope>()
        val job = launch { link.incoming.collect { got += it } }
        yield()
        link.send(Msg.Remove(EntityId(1)))
        link.send(Msg.Remove(EntityId(2)), to = DeviceId(4, "nobody"))
        link.send(Msg.Remove(EntityId(3)), to = link.self)
        yield()
        assertEquals(listOf(EntityId(1), EntityId(3)), got.map { (it.msg as Msg.Remove).id })
        assertEquals(2, link.sent)
        assertEquals(listOf(link.self), link.peers.value)
        job.cancel()
    }

    @Test fun `adding goes out on the link and is applied when it comes back`() = runTest {
        val link = LoopbackLink()
        val session = emptySession(link)
        val job = launch { session.run() }
        yield()
        assertEquals(6, session.add("green_chromis", 6))
        session.scene.first { it.creatures.size == 6 }
        assertEquals(6, link.sent)
        session.scene.value.creatures.forEach { assertEquals(link.self, session.ownership(it.id)!!.owner) }
        job.cancel()
    }

    @Test fun `removing takes a creature and its ownership out`() = runTest {
        val session = emptySession()
        val job = launch { session.run() }
        yield()
        session.add("green_sea_turtle", 1)
        val turtle = session.scene.first { it.creatures.isNotEmpty() }.creatures.single()
        session.remove(turtle.id)
        session.scene.first { it.creatures.isEmpty() }
        assertNull(session.ownership(turtle.id))
        job.cancel()
    }

    @Test fun `the creature cap is respected`() = runTest {
        val session = emptySession()
        val job = launch { session.run() }
        yield()
        var total = 0
        repeat(40) { total += session.add("green_chromis", 6) }
        assertEquals(TankScene.MAX_CREATURES, total)
        assertEquals(0, session.add("green_chromis", 1))
        job.cancel()
    }

    @Test fun `reset brings back the default reef`() = runTest {
        val session = emptySession()
        val job = launch { session.run() }
        yield()
        session.reset()
        val scene = session.scene.first { it.creatures.isNotEmpty() }
        assertEquals(1, scene.count("green_sea_turtle"))
        assertEquals(SpeciesCatalog.GreenChromis.defaultCount, scene.count("green_chromis"))
        job.cancel()
    }

    @Test fun `a duplicate or unknown spawn is ignored`() {
        val session = emptySession()
        val spec = CreatureSpec(EntityId(5), "green_chromis", 1, 22f, Vec2(10f, 10f), 30f)
        session.handle(Envelope(DeviceId.Solo, null, Msg.Spawn(spec, DeviceId.Solo)))
        session.handle(Envelope(DeviceId.Solo, null, Msg.Spawn(spec, DeviceId.Solo)))
        session.handle(Envelope(DeviceId.Solo, null, Msg.Spawn(spec.copy(id = EntityId(6), speciesId = "kraken"), DeviceId.Solo)))
        assertEquals(1, session.scene.value.creatures.size)
    }

    @Test fun `new ids never collide with a restored tank`() = runTest {
        val restored = Population.defaultScene(size, EntityIdAllocator(0), Random(1))
        val session = TankSession(restored, LoopbackLink(), Random(2))
        val job = launch { session.run() }
        yield()
        session.add("green_chromis", 3)
        val scene = session.scene.first { it.creatures.size == restored.creatures.size + 3 }
        assertEquals(scene.creatures.size, scene.creatures.map { it.id }.toSet().size)
        job.cancel()
    }

    @Test fun `a scene diff brings the renderer level whatever it missed`() {
        val a = CreatureSpec(EntityId(1), "green_chromis", 1, 22f, Vec2.Zero, 30f)
        val b = a.copy(id = EntityId(2))
        val c = a.copy(id = EntityId(3))
        val diff = SceneDiff.between(setOf(EntityId(1), EntityId(2)), TankScene(size, listOf(b, c)))
        assertEquals(listOf(c), diff.added)
        assertEquals(listOf(EntityId(1)), diff.removed)
        assertTrue(SceneDiff.between(setOf(EntityId(2), EntityId(3)), TankScene(size, listOf(b, c))).isEmpty)
    }

    @Test fun `the lagoon grows for a bigger window, keeps its creatures and never shrinks`() = runTest {
        val start = TankScene(TankSize(150f, 320f, 55f), Population.release(SpeciesCatalog.HermitCrab, 3, TankSize(150f, 320f, 55f), EntityIdAllocator(0), Random(1)))
        val session = TankSession(start, LoopbackLink(), Random(3))
        assertTrue(session.growTo(TankSize(300f, 280f, 55f)))
        assertEquals(TankSize(300f, 320f, 55f), session.scene.value.size)
        assertEquals(start.creatures, session.scene.value.creatures)
        assertTrue(!session.growTo(TankSize(100f, 100f, 55f)))
        assertEquals(TankSize(300f, 320f, 55f), session.scene.value.size)
    }
}
