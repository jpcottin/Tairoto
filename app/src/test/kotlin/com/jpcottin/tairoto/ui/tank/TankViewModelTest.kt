package com.jpcottin.tairoto.ui.tank

import com.jpcottin.tairoto.LaunchOptions
import com.jpcottin.tairoto.data.AppSettings
import com.jpcottin.tairoto.data.InMemorySettings
import com.jpcottin.tairoto.engine.EngineStats
import com.jpcottin.tairoto.engine.VulkanState
import com.jpcottin.tairoto.world.Camera
import com.jpcottin.tairoto.world.QualityMode
import com.jpcottin.tairoto.world.QualityTier
import com.jpcottin.tairoto.world.SpeciesCatalog
import com.jpcottin.tairoto.world.TankScene
import com.jpcottin.tairoto.world.Vec2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TankViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private lateinit var signals: FakeSignals

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        engine = FakeEngine()
        signals = FakeSignals()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(
        options: LaunchOptions = LaunchOptions(seed = 7, hints = true),
        settings: AppSettings = AppSettings(),
    ): TankViewModel {
        val vm = TankViewModel(engine, InMemorySettings(settings), options, signals, dispatcher)
        runCurrent()
        return vm
    }

    /** The phone the tests run on: 1080 x 2400 at 420 dpi. */
    private fun TestScope.attach(vm: TankViewModel) {
        vm.onSurfaceSize(1080, 2400, 420f, 420f)
        runCurrent()
    }

    @Test fun `nothing exists until the surface tells the screen size`() = runTest(dispatcher) {
        val vm = vm()
        assertFalse(vm.ui.value.ready)
        assertNull(engine.lastTank)
        assertTrue(engine.creatures.isEmpty())
    }

    @Test fun `the first surface creates the default reef and hands it to the engine`() = runTest(dispatcher) {
        val vm = vm()
        attach(vm)
        val ui = vm.ui.value
        assertTrue(ui.ready)
        assertEquals(SpeciesCatalog.all.sumOf { it.defaultCount }, ui.total)
        assertEquals(ui.total, engine.creatures.size)
        assertEquals(1, ui.counts["green_sea_turtle"])
        assertNotNull(engine.lastTank)
        assertTrue("tank bigger than the screen", engine.lastTank!!.widthMm > 65f)
        assertEquals(1f, engine.camera!!.zoom, 1e-4f)
    }

    @Test fun `a second surface, after rotation, keeps the same tank`() = runTest(dispatcher) {
        val vm = vm()
        attach(vm)
        val tank = engine.lastTank
        val ids = engine.creatures.keys.toSet()
        vm.onSurfaceSize(2400, 1080, 420f, 420f)
        runCurrent()
        assertEquals(tank, engine.lastTank)
        assertEquals(ids, engine.creatures.keys)
        assertEquals(2400f, engine.viewport!!.widthPx, 0f)
    }

    @Test fun `releasing adds a batch of that species where the camera looks`() = runTest(dispatcher) {
        val vm = vm()
        attach(vm)
        val before = vm.ui.value.counts["green_chromis"]!!
        vm.release("green_chromis")
        runCurrent()
        assertEquals(before + SpeciesCatalog.GreenChromis.addBatch, vm.ui.value.counts["green_chromis"])
        val center = engine.camera!!.center
        val newest = engine.creatures.values.last()
        assertTrue((newest.spawnAt - center).length < 35f * 4f * 1.5f + 1f)
    }

    @Test fun `the room left shrinks to zero and no further`() = runTest(dispatcher) {
        val vm = vm()
        attach(vm)
        repeat(40) { vm.release("green_chromis"); runCurrent() }
        assertEquals(TankScene.MAX_CREATURES, vm.ui.value.total)
        assertEquals(0, vm.ui.value.roomLeft)
    }

    @Test fun `reset restores the default reef`() = runTest(dispatcher) {
        val vm = vm()
        attach(vm)
        vm.release("green_sea_turtle")
        runCurrent()
        assertEquals(2, vm.ui.value.counts["green_sea_turtle"])
        vm.resetTank()
        runCurrent()
        assertEquals(1, vm.ui.value.counts["green_sea_turtle"])
        assertEquals(vm.ui.value.total, engine.creatures.size)
    }

    @Test fun `a tap reaches the engine as a point on the water surface`() = runTest(dispatcher) {
        val vm = vm()
        attach(vm)
        vm.tap(Vec2(540f, 1200f))
        assertEquals(1, engine.taps.size)
        val center = engine.camera!!.center
        assertEquals(center.x, engine.taps[0].x, 0.01f)
        assertEquals(center.y, engine.taps[0].y, 0.01f)
    }

    @Test fun `two quick taps zoom in, two more zoom back out`() = runTest(dispatcher) {
        val vm = vm()
        attach(vm)
        vm.tap(Vec2(500f, 1000f)); signals.now += 150
        vm.tap(Vec2(505f, 1004f))
        assertEquals(2f, vm.ui.value.zoom, 1e-3f)
        signals.now += 1000
        vm.tap(Vec2(500f, 1000f)); signals.now += 150
        vm.tap(Vec2(500f, 1000f))
        assertEquals(1f, vm.ui.value.zoom, 1e-3f)
    }

    @Test fun `two slow taps do not zoom`() = runTest(dispatcher) {
        val vm = vm()
        attach(vm)
        vm.tap(Vec2(500f, 1000f)); signals.now += 800
        vm.tap(Vec2(500f, 1000f))
        assertEquals(1f, vm.ui.value.zoom, 1e-3f)
        assertEquals(2, engine.taps.size)
    }

    @Test fun `a drag sprinkles a pinch where it lands, then flakes along the way`() = runTest(dispatcher) {
        val vm = vm()
        attach(vm)
        vm.sprinkle(Vec2(300f, 1000f), first = true)
        assertEquals(TankViewModel.FIRST_PINCH, engine.drops.single().count)
        assertEquals(com.jpcottin.tairoto.engine.FoodKind.FLAKE, engine.drops.single().kind)
        // 1 mm further: not yet a flake.
        val pxPerMm = engine.viewport!!.basePxPerMm
        vm.sprinkle(Vec2(300f + pxPerMm, 1000f), first = false)
        assertEquals(1, engine.drops.size)
        // 10.5 mm further on: three more flakes at 3.5 mm spacing.
        vm.sprinkle(Vec2(300f + pxPerMm * 11.5f, 1000f), first = false)
        assertEquals(3, engine.drops.last().count)
        assertEquals(TankScene.MAX_CREATURES, TankScene.MAX_CREATURES) // the scene is untouched by food
        assertEquals(SpeciesCatalog.all.sumOf { it.defaultCount }, vm.ui.value.total)
    }

    @Test fun `food lands where the finger is, on the surface`() = runTest(dispatcher) {
        val vm = vm()
        attach(vm)
        vm.sprinkle(Vec2(540f, 1200f), first = true)
        val center = engine.camera!!.center
        assertEquals(center.x, engine.drops[0].at.x, 0.01f)
        assertEquals(center.y, engine.drops[0].at.y, 0.01f)
    }

    @Test fun `a very fast drag is capped per move`() = runTest(dispatcher) {
        val vm = vm()
        attach(vm)
        vm.sprinkle(Vec2(100f, 300f), first = true)
        vm.sprinkle(Vec2(1000f, 2300f), first = false)
        assertEquals(TankViewModel.MAX_FLAKES_PER_MOVE, engine.drops.last().count)
    }

    @Test fun `a long press drops one pellet`() = runTest(dispatcher) {
        val vm = vm()
        attach(vm)
        vm.dropPellet(Vec2(540f, 1200f))
        assertEquals(com.jpcottin.tairoto.engine.FoodKind.PELLET, engine.drops.single().kind)
        assertEquals(1, engine.drops.single().count)
    }

    @Test fun `a new drag starts with a new pinch`() = runTest(dispatcher) {
        val vm = vm()
        attach(vm)
        vm.sprinkle(Vec2(300f, 1000f), first = true)
        vm.endSprinkle()
        vm.sprinkle(Vec2(310f, 1000f), first = false) // no drag in progress: treated as a new one
        assertEquals(TankViewModel.FIRST_PINCH, engine.drops.last().count)
    }

    @Test fun `pinching zooms within limits and panning stays in the tank`() = runTest(dispatcher) {
        val vm = vm()
        attach(vm)
        vm.zoom(Vec2(540f, 1200f), 10f)
        assertEquals(Camera.MAX_ZOOM, vm.ui.value.zoom, 1e-4f)
        vm.pan(Vec2(-1e5f, -1e5f))
        val cam = engine.camera!!
        assertTrue(cam.center.x <= engine.lastTank!!.widthMm)
        assertTrue(cam.center.y <= engine.lastTank!!.heightMm)
    }

    @Test fun `a gesture hides the hint, which also goes by itself`() = runTest(dispatcher) {
        val vm = vm()
        attach(vm)
        assertTrue(vm.ui.value.hintVisible)
        vm.pan(Vec2(10f, 0f))
        assertFalse(vm.ui.value.hintVisible)
        val other = vm()
        advanceTimeBy(TankViewModel.HINT_MS + 1)
        runCurrent()
        assertFalse(other.ui.value.hintVisible)
    }

    @Test fun `a fixed quality goes straight to the engine`() = runTest(dispatcher) {
        val vm = vm(settings = AppSettings(quality = QualityMode.Fixed(QualityTier.ULTRA)))
        assertEquals(QualityTier.ULTRA, engine.lastQuality)
        vm.setQualityMode(QualityMode.Fixed(QualityTier.LOW))
        runCurrent()
        assertEquals(QualityTier.LOW, engine.lastQuality)
        assertEquals(QualityTier.LOW, vm.ui.value.tier)
    }

    @Test fun `in automatic mode slow frames lower the quality`() = runTest(dispatcher) {
        val vm = vm()
        assertEquals(QualityTier.HIGH, engine.lastQuality)
        val slow = EngineStats(vulkan = VulkanState.RENDERING, fps = 35f, workMs = 9f)
        vm.onPerfSample(slow, 0)
        vm.onPerfSample(slow, 2_000)
        assertEquals("slow start-up frames are forgiven", QualityTier.HIGH, engine.lastQuality)
        vm.onPerfSample(slow, 8_000)
        vm.onPerfSample(slow, 10_000)
        assertEquals(QualityTier.LOW, engine.lastQuality)
    }

    @Test fun `the overlay shows the thermal headroom the governor sees`() = runTest(dispatcher) {
        val vm = vm()
        signals.headroom = 0.42f
        vm.onPerfSample(EngineStats(vulkan = VulkanState.RENDERING, fps = 60f), 0)
        assertEquals(0.42f, vm.ui.value.thermalHeadroom!!, 1e-6f)
    }

    @Test fun `on an emulator the simulated thermal headroom does not steer quality`() = runTest(dispatcher) {
        signals.simulated = true
        signals.headroom = 0.99f // would mean "about to throttle" on a phone
        val vm = vm()
        val smooth = EngineStats(vulkan = VulkanState.RENDERING, fps = 60f, workMs = 3f)
        var t = 0L
        repeat(25) { vm.onPerfSample(smooth, t); t += 2_000 }
        assertEquals(QualityTier.ULTRA, engine.lastQuality)
        assertTrue(vm.ui.value.thermalIgnored)
        assertEquals(0.99f, vm.ui.value.thermalHeadroom!!, 1e-6f)
    }

    @Test fun `on a phone a hot forecast steps quality down`() = runTest(dispatcher) {
        signals.headroom = 0.99f
        val vm = vm()
        val smooth = EngineStats(vulkan = VulkanState.RENDERING, fps = 60f, workMs = 3f)
        vm.onPerfSample(smooth, 0)
        vm.onPerfSample(smooth, 8_000)
        assertEquals(QualityTier.LOW, engine.lastQuality)
        assertFalse(vm.ui.value.thermalIgnored)
    }

    @Test fun `a fixed quality ignores the governor`() = runTest(dispatcher) {
        val vm = vm(settings = AppSettings(quality = QualityMode.Fixed(QualityTier.ULTRA)))
        val slow = EngineStats(vulkan = VulkanState.RENDERING, fps = 20f)
        repeat(5) { vm.onPerfSample(slow, it * 2_000L) }
        assertEquals(QualityTier.ULTRA, engine.lastQuality)
    }

    @Test fun `a CI launch is deterministic, on the CI tier, without hints`() = runTest(dispatcher) {
        val vm = vm(options = LaunchOptions(seed = 7, ci = true, quality = QualityTier.CI, hints = false))
        assertEquals(true to 7L, engine.deterministic)
        assertEquals(QualityTier.CI, engine.lastQuality)
        assertFalse(vm.ui.value.hintVisible)
    }

    @Test fun `the same seed gives the same tank`() = runTest(dispatcher) {
        val a = vm()
        attach(a)
        val first = engine.creatures.values.map { it.spawnAt }
        engine = FakeEngine()
        val b = vm()
        attach(b)
        assertEquals(first, engine.creatures.values.map { it.spawnAt })
    }

    @Test fun `the performance overlay follows the setting`() = runTest(dispatcher) {
        val vm = vm()
        assertFalse(vm.ui.value.showStats)
        vm.setShowStats(true)
        runCurrent()
        assertTrue(vm.ui.value.showStats)
    }

    @Test fun `a launch extra forces the overlay on`() = runTest(dispatcher) {
        val vm = vm(options = LaunchOptions(stats = true))
        assertTrue(vm.ui.value.showStats)
    }

    @Test fun `a launch population replaces the default lagoon`() = runTest(dispatcher) {
        val vm = vm(options = LaunchOptions(seed = 7, population = mapOf("hermit_crab" to 3, "day_octopus" to 1)))
        attach(vm)
        assertEquals(4, vm.ui.value.total)
        assertEquals(3, vm.ui.value.counts["hermit_crab"])
        assertEquals(1, vm.ui.value.counts["day_octopus"])
        assertEquals(4, engine.creatures.size)
    }
}
