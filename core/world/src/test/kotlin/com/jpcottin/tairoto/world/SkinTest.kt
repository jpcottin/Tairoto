package com.jpcottin.tairoto.world

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** A drawing as it might come off a page: a red back, a blue belly, clear around the outline. */
internal fun drawing(tint: Int = 0): Skin {
    val px = ByteArray(Skin.BYTES)
    for (y in 0 until Skin.HEIGHT) for (x in 0 until Skin.WIDTH) {
        val v = (y + 0.5f) / Skin.HEIGHT * 2f - 1f
        val u = (x + 0.5f) / Skin.WIDTH
        val i = (y * Skin.WIDTH + x) * 4
        if (FishTemplate.coverage(u, v) < 0.5f) continue
        px[i] = (if (v < 0) 220 else 30 + tint).toByte()
        px[i + 1] = (40 + (x * 3 + y) % 7).toByte()
        px[i + 2] = (if (v < 0) 40 else 200).toByte()
        px[i + 3] = 255.toByte()
    }
    return Skin.of(px)
}

class SkinTest {
    private val size = TankSize(220f, 330f, 110f)

    @Test fun `a drawing packs small and comes back the same`() {
        val skin = drawing()
        assertTrue("${skin.data.length} characters", skin.data.length < 12_000)
        val px = skin.pixels()!!
        assertEquals(Skin.BYTES, px.size)
        assertArrayEquals(px, Skin.of(px).pixels())
        assertTrue(skin.isIntact)
    }

    @Test fun `the same drawing has the same id, another drawing another`() {
        assertEquals(drawing().id, drawing().id)
        assertNotEquals(drawing().id, drawing(tint = 9).id)
    }

    @Test fun `damaged data reads as no drawing, and a swapped one is not intact`() {
        val skin = drawing()
        assertNull(skin.copy(data = "not base64!").pixels())
        assertNull(skin.copy(data = "AAAA").pixels())
        assertFalse(skin.copy(data = drawing(tint = 9).data).isIntact)
    }

    @Test fun `a lagoon keeps each drawing once, and forgets the ones no fish wears to make room`() {
        val base = TankScene(size)
        val skins = List(TankScene.MAX_SKINS + 1) { drawing(tint = it) }
        var scene = base
        for (s in skins.take(TankScene.MAX_SKINS)) {
            scene = scene.withSkin(s)!!
            scene = scene.copy(creatures = scene.creatures + CreatureSpec(EntityId(scene.creatures.size + 1L), "drawn_fish", 1, 40f, Vec2(1f, 1f), 10f, skin = s.id))
        }
        assertEquals(scene, scene.withSkin(skins[0]))
        assertNull("all worn", scene.withSkin(skins.last()))
        val oneNetted = scene.copy(creatures = scene.creatures.drop(1))
        val after = oneNetted.withSkin(skins.last())!!
        assertEquals(TankScene.MAX_SKINS, after.skins.size)
        assertNull(after.skin(skins[0].id))
        assertEquals(TankScene.MAX_SKINS - 1, oneNetted.withoutUnusedSkins().skins.size)
    }

    @Test fun `a drawn fish is released wearing its drawing, travels the wire, and can be netted and put back`() = runTest {
        val skin = drawing()
        val link = LoopbackLink()
        val s = TankSession(TankScene(size), link, Random(2))
        val job = backgroundScope.launch { s.run() }
        runCurrent()
        assertTrue(s.addDrawn(skin, Vec2(100f, 100f)))
        advanceTimeBy(10)
        val fish = s.scene.value.creatures.single()
        assertEquals("drawn_fish", fish.speciesId)
        assertEquals(skin.id, fish.skin)
        assertEquals(listOf(skin), s.scene.value.skins)
        assertEquals(SpeciesCatalog.Drawn, SpeciesCatalog[fish.speciesId])
        assertTrue(SpeciesCatalog.all.none { it.id == fish.speciesId })
        // Netted, another drawing fills the lagoon's last room, then undo puts it back in its colours.
        s.net(fish.id)
        advanceTimeBy(10)
        assertTrue(s.scene.value.creatures.isEmpty())
        s.undo()
        advanceTimeBy(10)
        assertEquals(skin.id, s.scene.value.creatures.single().skin)
        assertNotNull(s.scene.value.skin(skin.id))
        job.cancel()
    }

    @Test fun `a spawn of a drawn fish without its drawing is refused`() {
        val s = TankSession(TankScene(size), LoopbackLink(), Random(2))
        val fish = CreatureSpec(EntityId(1), "drawn_fish", 1, 40f, Vec2(10f, 10f), 10f, skin = drawing().id)
        s.handle(Envelope(DeviceId.Solo, null, Msg.Spawn(fish, DeviceId.Solo)))
        assertTrue(s.scene.value.creatures.isEmpty())
        // Nor with a drawing that is not the one named.
        s.handle(Envelope(DeviceId.Solo, null, Msg.Spawn(fish, DeviceId.Solo, drawing(tint = 4))))
        assertTrue(s.scene.value.creatures.isEmpty())
        s.handle(Envelope(DeviceId.Solo, null, Msg.Spawn(fish, DeviceId.Solo, drawing())))
        assertEquals(1, s.scene.value.creatures.size)
    }

    @Test fun `a saved lagoon keeps its drawn fish, and drops one whose drawing is damaged`() {
        val good = drawing()
        val bad = drawing(tint = 3).let { it.copy(data = drawing(tint = 5).data) }
        val scene = TankScene(
            size,
            creatures = listOf(
                CreatureSpec(EntityId(1), "drawn_fish", 1, 40f, Vec2(10f, 10f), 10f, skin = good.id),
                CreatureSpec(EntityId(2), "drawn_fish", 2, 40f, Vec2(10f, 10f), 10f, skin = bad.id),
                CreatureSpec(EntityId(3), "green_chromis", 3, 15f, Vec2(10f, 10f), 10f),
            ),
            skins = listOf(good, bad, drawing(tint = 7)),
        )
        val back = SavedLagoon.decode(SavedLagoon.encode(scene))!!
        assertEquals(listOf(1L, 3L), back.creatures.map { it.id.raw })
        assertEquals(listOf(good), back.skins)
    }

    @Test fun `a guest's drawn fish comes along with its drawing, and every screen gets it`() = runTest {
        val home = TankSize(200f, 300f, TankSize.DEFAULT_DEPTH_MM)
        val skin = drawing()
        val ids = EntityIdAllocator(0)
        val fish = Population.release(SpeciesCatalog.Drawn, 1, home, ids, Random(4)).map { it.copy(skin = skin.id, spawnAt = Vec2(0f, 0f)) }
        // One without its drawing is left at home.
        val lost = Population.release(SpeciesCatalog.Drawn, 1, home, ids, Random(5)).map { it.copy(skin = drawing(tint = 2).id) }
        val brought = Visitors(creatures = fish + lost, skins = listOf(skin))
        val net = FakeNetwork(backgroundScope, { testScheduler.currentTime })
        val lagoon = FakeLagoon(backgroundScope, { testScheduler.currentTime }, net, List(3) { ScreenMm(65f, 140f) },
            { size -> Population.sceneOf(mapOf("green_chromis" to 4), size, EntityIdAllocator(0), Random(1)) },
            brings = { i -> if (i == 1) brought else null })
        lagoon.start()
        advanceTimeBy(2_000)
        for (d in lagoon.devices) {
            val scene = d.session!!.scene.value
            assertEquals(skin.id, scene.creatures.single { it.speciesId == "drawn_fish" }.skin)
            assertEquals(listOf(skin), scene.skins)
        }
        assertTrue(lagoon.monitor.violations.isEmpty())
    }
}

