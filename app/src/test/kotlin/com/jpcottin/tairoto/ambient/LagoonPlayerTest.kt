package com.jpcottin.tairoto.ambient

import com.jpcottin.tairoto.data.InMemoryLagoonStore
import com.jpcottin.tairoto.ui.tank.FakeEngine
import com.jpcottin.tairoto.world.EntityIdAllocator
import com.jpcottin.tairoto.world.Population
import com.jpcottin.tairoto.world.QualityTier
import com.jpcottin.tairoto.world.TankSize
import com.jpcottin.tairoto.world.Vec2
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The lagoon as a wallpaper or a screensaver, on a fake engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class LagoonPlayerTest {
    private val saved = Population.defaultScene(TankSize(330f, 330f, 55f), EntityIdAllocator(0), Random(4))

    private fun TestScope.player(
        drift: Boolean,
        engine: FakeEngine,
        store: InMemoryLagoonStore = InMemoryLagoonStore(saved),
        daylight: com.jpcottin.tairoto.world.Daylight = com.jpcottin.tairoto.world.Daylight.NOON,
    ) = LagoonPlayer(engine, store, backgroundScope, drift, clockS = { testScheduler.currentTime / 1000.0 }, daylight = { daylight })

    @Test fun `seen, it takes the light the app's settings ask for`() = runTest {
        val engine = FakeEngine()
        val night = com.jpcottin.tairoto.world.Daylight(0f, 0f)
        val p = player(drift = false, engine, daylight = night)
        p.surface(null, 1080, 2400, 420f, 420f)
        p.visible(true)
        runCurrent()
        org.junit.Assert.assertEquals(night, engine.light)
    }

    @Test fun `it shows the lagoon the app saved, drawn gently and silent, and never saves over it`() = runTest {
        val engine = FakeEngine()
        val store = InMemoryLagoonStore(saved)
        val p = player(drift = false, engine, store)
        assertEquals(QualityTier.LOW, engine.lastQuality)
        assertEquals(0f, engine.soundVolume)
        p.surface(null, 1080, 2400, 420f, 420f)
        runCurrent()
        assertEquals(saved.creatures.map { it.id }.toSet(), engine.creatures.keys)
        assertEquals(saved.decor.size, engine.decor.size)
        assertEquals(saved, store.scene)
    }

    @Test fun `with nothing saved it makes a lagoon of its own for the screen`() = runTest {
        val engine = FakeEngine()
        player(drift = false, engine, InMemoryLagoonStore(null)).surface(null, 1080, 2400, 420f, 420f)
        runCurrent()
        assertTrue(engine.creatures.isNotEmpty())
    }

    @Test fun `it swims only while seen`() = runTest {
        val engine = FakeEngine()
        val p = player(drift = false, engine)
        assertEquals(true, engine.paused)
        p.visible(true)
        assertEquals(false, engine.paused)
        p.visible(false)
        assertEquals(true, engine.paused)
    }

    @Test fun `swiping the home screens pans across the lagoon`() = runTest {
        val engine = FakeEngine()
        val p = player(drift = false, engine)
        p.surface(null, 1080, 2400, 420f, 420f)
        runCurrent()
        p.offset(0f, 0.5f)
        val left = engine.camera!!.center.x
        p.offset(1f, 0.5f)
        val right = engine.camera!!.center.x
        assertTrue("$left < $right", left < right)
    }

    @Test fun `as a screensaver it roams on its own while seen`() = runTest {
        val engine = FakeEngine()
        val p = player(drift = true, engine)
        p.surface(null, 1080, 2400, 420f, 420f)
        runCurrent()
        p.visible(true)
        advanceTimeBy(1_000)
        val a = engine.camera!!.center
        advanceTimeBy(30_000)
        val b = engine.camera!!.center
        assertTrue("$a then $b", (b - a).length > 1f)
        // Unseen, it stops.
        p.visible(false)
        val c = engine.camera!!.center
        advanceTimeBy(30_000)
        assertEquals(c, engine.camera!!.center)
    }

    @Test fun `a tap ripples the water where the finger was, and closing lets the engine go`() = runTest {
        val engine = FakeEngine()
        val p = player(drift = false, engine)
        p.surface(null, 1080, 2400, 420f, 420f)
        runCurrent()
        p.tap(Vec2(540f, 1200f))
        val at = engine.taps.single()
        assertNotNull(at)
        assertEquals(engine.camera!!.center.x, at.x, 0.01f)
        p.close()
        assertTrue(engine.closed)
    }

    @Test fun `seen again, it shows what the app changed in the lagoon meanwhile`() = runTest {
        val engine = FakeEngine()
        val store = InMemoryLagoonStore(saved)
        val p = player(drift = false, engine, store)
        p.surface(null, 1080, 2400, 420f, 420f)
        runCurrent()
        p.visible(true)
        p.visible(false)
        // The app releases a turtle while the home screen is hidden.
        val turtle = saved.creatures.first().copy(id = com.jpcottin.tairoto.world.EntityId(9_999), speciesId = "green_sea_turtle")
        store.scene = saved.copy(creatures = saved.creatures + turtle)
        p.visible(true)
        runCurrent()
        assertTrue(turtle.id in engine.creatures.keys)
    }
}
