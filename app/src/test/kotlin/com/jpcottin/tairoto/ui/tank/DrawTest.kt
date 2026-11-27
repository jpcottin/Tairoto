package com.jpcottin.tairoto.ui.tank

import com.jpcottin.tairoto.LaunchOptions
import com.jpcottin.tairoto.data.AppSettings
import com.jpcottin.tairoto.data.InMemorySettings
import com.jpcottin.tairoto.world.PagePhotos
import com.jpcottin.tairoto.world.Picture
import com.jpcottin.tairoto.world.SpeciesCatalog
import com.jpcottin.tairoto.world.TankScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
class DrawTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        engine = FakeEngine()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(): TankViewModel {
        val vm = TankViewModel(engine, InMemorySettings(AppSettings()), LaunchOptions(seed = 7, hints = false), FakeSignals(), dispatcher)
        runCurrent()
        vm.onSurfaceSize(1080, 2400, 420f, 420f)
        runCurrent()
        return vm
    }

    private val page: Picture by lazy { PagePhotos.photo(900, 640, paint = PagePhotos::colourful) }

    @Test fun `a photo of the page is scanned, shown, and released into the lagoon wearing its drawing`() = runTest(dispatcher) {
        val vm = vm()
        assertEquals(DrawPhase.IDLE, vm.ui.value.draw.phase)
        vm.scanDrawing { page }
        assertEquals(DrawPhase.SCANNING, vm.ui.value.draw.phase)
        advanceUntilIdle()
        val found = vm.ui.value.draw
        assertEquals(DrawPhase.FOUND, found.phase)
        val skin = assertNotNull(found.skin).let { found.skin!! }
        vm.releaseDrawing()
        advanceUntilIdle()
        assertEquals(DrawPhase.RELEASED, vm.ui.value.draw.phase)
        assertNull(vm.ui.value.draw.skin)
        assertEquals(1, vm.ui.value.draw.inLagoon)
        val fish = engine.creatures.values.single { it.speciesId == SpeciesCatalog.Drawn.id }
        assertEquals(skin, engine.skins[fish.id])
        assertEquals(SpeciesCatalog.all.sumOf { it.defaultCount } + 1, vm.ui.value.total)
    }

    @Test fun `a photo with no page in it, or one that cannot be opened, says so`() = runTest(dispatcher) {
        val vm = vm()
        vm.scanDrawing { Picture(300, 200, IntArray(300 * 200) { 0xFF777777.toInt() }) }
        advanceUntilIdle()
        assertEquals(DrawPhase.NO_PAGE, vm.ui.value.draw.phase)
        vm.scanDrawing { throw java.io.IOException("gone") }
        advanceUntilIdle()
        assertEquals(DrawPhase.UNREADABLE, vm.ui.value.draw.phase)
        vm.scanDrawing { null }
        advanceUntilIdle()
        assertEquals(DrawPhase.UNREADABLE, vm.ui.value.draw.phase)
        vm.cameraUnavailable()
        assertEquals(DrawPhase.NO_CAMERA, vm.ui.value.draw.phase)
    }

    @Test fun `closing the sheet keeps a fish waiting to be released, and forgets a message`() = runTest(dispatcher) {
        val vm = vm()
        vm.scanDrawing { page }
        advanceUntilIdle()
        vm.closeDrawing()
        assertEquals(DrawPhase.FOUND, vm.ui.value.draw.phase)
        vm.dismissDrawing()
        assertEquals(DrawPhase.IDLE, vm.ui.value.draw.phase)
        vm.scanDrawing { null }
        advanceUntilIdle()
        vm.closeDrawing()
        assertEquals(DrawPhase.IDLE, vm.ui.value.draw.phase)
    }

    @Test fun `when the lagoon holds as many drawn fish as it can, there is no room for another`() = runTest(dispatcher) {
        val vm = vm()
        repeat(TankScene.MAX_SKINS) { i ->
            vm.scanDrawing { PagePhotos.photo(600, 430, seed = i, paint = { u, v -> floatArrayOf(i / 16f, 1f - i / 16f, if (v < 0) 0.2f else 0.9f + u * 0f) }) }
            advanceUntilIdle()
            assertTrue("room for drawing $i", vm.ui.value.draw.room)
            vm.releaseDrawing()
            advanceUntilIdle()
        }
        assertEquals(TankScene.MAX_SKINS, vm.ui.value.draw.inLagoon)
        assertFalse(vm.ui.value.draw.room)
        vm.scanDrawing { page }
        advanceUntilIdle()
        vm.releaseDrawing()
        advanceUntilIdle()
        assertEquals(DrawPhase.FULL, vm.ui.value.draw.phase)
        assertNotNull("kept, to release later", vm.ui.value.draw.skin)
        assertEquals(TankScene.MAX_SKINS, vm.ui.value.draw.inLagoon)
    }
}
