package com.jpcottin.tairoto.wear

import android.view.Surface
import com.jpcottin.tairoto.engine.CreatureState
import com.jpcottin.tairoto.engine.EngineStats
import com.jpcottin.tairoto.engine.FoodKind
import com.jpcottin.tairoto.engine.TankEngine
import com.jpcottin.tairoto.world.Camera
import com.jpcottin.tairoto.world.CreatureSpec
import com.jpcottin.tairoto.world.DecorKind
import com.jpcottin.tairoto.world.DecorSpec
import com.jpcottin.tairoto.world.EntityId
import com.jpcottin.tairoto.world.QualityTier
import com.jpcottin.tairoto.world.SimInput
import com.jpcottin.tairoto.world.SimOutput
import com.jpcottin.tairoto.world.Skin
import com.jpcottin.tairoto.world.Species
import com.jpcottin.tairoto.world.TankSize
import com.jpcottin.tairoto.world.Vec2
import com.jpcottin.tairoto.world.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WristLagoonTest {
    private class Engine : TankEngine {
        val creatures = LinkedHashMap<EntityId, CreatureSpec>()
        val decor = ArrayList<DecorSpec>()
        var lastTank: TankSize? = null
        var camera: Camera? = null
        var lastQuality: QualityTier? = null
        var paused: Boolean? = null
        val taps = ArrayList<Vec2>()
        val food = ArrayList<Pair<FoodKind, Int>>()
        override fun setSurface(surface: Surface?) = Unit
        override fun setTank(size: TankSize) { lastTank = size }
        override fun setCamera(camera: Camera, viewport: Viewport) { this.camera = camera }
        override fun setQuality(tier: QualityTier) { lastQuality = tier }
        override fun setDeterministic(on: Boolean, seed: Long) = Unit
        override fun setPaused(paused: Boolean) { this.paused = paused }
        override fun spawn(creature: CreatureSpec, species: Species, ghost: Boolean, skin: Skin?) { creatures[creature.id] = creature }
        override fun remove(id: EntityId) { creatures.remove(id) }
        override fun clear() = creatures.clear()
        override fun placeDecor(decor: DecorSpec, kind: DecorKind) { this.decor += decor }
        override fun moveDecor(decor: DecorSpec) = Unit
        override fun removeDecor(id: EntityId) = Unit
        override fun snapshot(): List<CreatureState> = creatures.keys.map { CreatureState(it, 7f, 9f, 11f, 0f, 0) }
        override fun tap(at: Vec2) { taps += at }
        override fun dropFood(at: Vec2, kind: FoodKind, count: Int) { food += kind to count }
        override fun stats() = EngineStats()
        override fun gpuName() = "fake"
        override fun setFoodSlot(slot: Int) = Unit
        override fun exchange(input: SimInput) = SimOutput()
        override fun close() = Unit
    }

    /** A round watch: 454 px across at 330 dpi, about 35 mm. */
    private fun watch(engine: Engine = Engine()) = WristLagoon(engine, random = Random(3)).also { it.onScreen(454, 454, 330f) } to engine

    @Test fun `a watch gets a small lagoon of its own, gently drawn`() {
        val (l, e) = watch()
        val sc = assertNotNull(l.scene).let { l.scene!! }
        assertEquals(11, sc.creatures.size)
        assertEquals(1, sc.decor.size)
        assertEquals(sc.creatures.map { it.id }.toSet(), e.creatures.keys)
        assertTrue("a small lagoon: ${sc.size}", sc.size.widthMm <= 120f)
        assertEquals(QualityTier.LOW, e.lastQuality)
        assertTrue("zoomed out to start: ${e.camera!!.zoom}", e.camera!!.zoom < 0.8f)
    }

    @Test fun `taps, a long press and a finger dragged feed and startle the fish`() {
        val (l, e) = watch()
        l.tap(Vec2(227f, 227f))
        assertEquals(1, e.taps.size)
        l.pellet(Vec2(100f, 100f))
        l.sprinkle(Vec2(200f, 200f), first = true)
        for (i in 1..20) l.sprinkle(Vec2(200f + i * 10, 200f), first = false)
        l.endSprinkle()
        assertEquals(FoodKind.PELLET to 1, e.food[0])
        assertEquals(FoodKind.FLAKE to WristLagoon.FIRST_PINCH, e.food[1])
        assertTrue("flakes along the way", e.food.size > 3)
    }

    @Test fun `the crown zooms in and back out`() {
        val (l, e) = watch()
        val z0 = e.camera!!.zoom
        l.crown(4f)
        assertTrue(e.camera!!.zoom > z0)
        l.crown(-4f)
        assertEquals(z0, e.camera!!.zoom, 0.05f)
    }

    @Test fun `paused, it keeps where everyone was, and comes back to the same lagoon`() {
        val (l, e) = watch()
        val kept = l.pause()!!
        assertEquals(true, e.paused)
        assertTrue(kept.creatures.all { it.spawnAt == Vec2(7f, 9f) && it.spawnDepthMm == 11f })
        val (again, e2) = WristLagoon(Engine(), kept).also { it.onScreen(454, 454, 330f) }.let { it to null }
        assertEquals(kept.creatures.map { it.id }, again.scene!!.creatures.map { it.id })
        assertEquals(null, e2)
    }
}
