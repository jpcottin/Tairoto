package com.jpcottin.tairoto.ui.tank

import com.jpcottin.tairoto.LaunchOptions
import com.jpcottin.tairoto.data.AppSettings
import com.jpcottin.tairoto.data.InMemorySettings
import com.jpcottin.tairoto.engine.EngineStats
import com.jpcottin.tairoto.engine.VulkanState
import com.jpcottin.tairoto.world.DayCycle
import com.jpcottin.tairoto.world.Daylight
import com.jpcottin.tairoto.world.TimeOfDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DaylightTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private var hours = 12f

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        engine = FakeEngine()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(settings: AppSettings = AppSettings(), options: LaunchOptions = LaunchOptions(seed = 7, hints = false)): Pair<TankViewModel, InMemorySettings> {
        val store = InMemorySettings(settings)
        val vm = TankViewModel(engine, store, options, FakeSignals(), dispatcher, localHours = { hours })
        runCurrent()
        return vm to store
    }

    private val frames = EngineStats(vulkan = VulkanState.RENDERING, fps = 60f)

    @Test fun `by default the lagoon follows the local time, and moves on with it`() = runTest(dispatcher) {
        hours = 21f
        val (vm, _) = vm()
        assertEquals(TimeOfDay.LOCAL, vm.ui.value.timeOfDay)
        assertEquals(DayCycle.at(21f), engine.light)
        // Half a minute later, dawn has come (the clock jumped, as a test clock may).
        hours = 6f
        vm.onPerfSample(frames, 10_000)
        assertEquals("not yet", DayCycle.at(21f), engine.light)
        vm.onPerfSample(frames, 40_000)
        assertEquals(DayCycle.at(6f), engine.light)
    }

    @Test fun `the settings can force day or night, whatever the hour`() = runTest(dispatcher) {
        hours = 2f
        val (vm, _) = vm()
        vm.setTimeOfDay(TimeOfDay.DAY)
        runCurrent()
        assertEquals(Daylight.NOON, engine.light)
        assertEquals(TimeOfDay.DAY, vm.ui.value.timeOfDay)
        vm.setTimeOfDay(TimeOfDay.NIGHT)
        runCurrent()
        assertEquals(0f, engine.light!!.light, 0f)
        // Forced, the clock no longer moves it.
        hours = 12f
        vm.onPerfSample(frames, 100_000)
        assertEquals(0f, engine.light!!.light, 0f)
    }

    @Test fun `a launch can fix the light, and a CI run is at noon`() = runTest(dispatcher) {
        hours = 23f
        vm(settings = AppSettings(timeOfDay = TimeOfDay.LOCAL), options = LaunchOptions(seed = 7, hints = false, timeOfDay = TimeOfDay.DAY))
        assertEquals(Daylight.NOON, engine.light)
    }
}
