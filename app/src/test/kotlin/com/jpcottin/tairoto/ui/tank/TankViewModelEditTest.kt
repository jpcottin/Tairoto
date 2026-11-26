package com.jpcottin.tairoto.ui.tank

import com.jpcottin.tairoto.LaunchOptions
import com.jpcottin.tairoto.data.InMemorySettings
import com.jpcottin.tairoto.data.InMemoryLagoonStore
import com.jpcottin.tairoto.data.LagoonStore
import com.jpcottin.tairoto.engine.CreatureState
import com.jpcottin.tairoto.engine.FoodKind
import com.jpcottin.tairoto.world.DecorCatalog
import com.jpcottin.tairoto.world.DecorSpec
import com.jpcottin.tairoto.world.EntityId
import com.jpcottin.tairoto.world.EntityIdAllocator
import com.jpcottin.tairoto.world.Population
import com.jpcottin.tairoto.world.ScreenMm
import com.jpcottin.tairoto.world.TankScene
import com.jpcottin.tairoto.world.TankSize
import com.jpcottin.tairoto.world.Vec2
import com.jpcottin.tairoto.world.Viewport
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.random.Random

/** Arranging the lagoon, the net, undo, and keeping the lagoon between launches. */
@OptIn(ExperimentalCoroutinesApi::class)
class TankViewModelEditTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private lateinit var store: InMemoryLagoonStore
    private val viewport = Viewport(1080f, 2400f, ScreenMm.pxPerMm(420f))

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        engine = FakeEngine()
        store = InMemoryLagoonStore()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(options: LaunchOptions = LaunchOptions(seed = 7, hints = false), lagoon: LagoonStore = store): TankViewModel {
        val vm = TankViewModel(engine, InMemorySettings(), options, FakeSignals(), dispatcher, lagoon)
        runCurrent()
        vm.onSurfaceSize(1080, 2400, 420f, 420f)
        runCurrent()
        return vm
    }

    private fun TankViewModel.screenOf(at: Vec2, depthMm: Float): Vec2 = camera!!.worldToScreen(at, depthMm, viewport)

    private fun TankViewModel.decorOnScreen(d: DecorSpec): Vec2 = screenOf(d.at, ui.value.tankSize!!.depthMm)

    /** A lagoon with one stone, in the middle of the view, and one turtle elsewhere. */
    private fun savedLagoon(): TankScene {
        val size = TankSize(190f, 420f, 55f)
        val stone = DecorSpec(EntityId(500), DecorCatalog.Stone.id, 3, Vec2(95f, 210f), 0f, 0.5f)
        val turtle = Population.sceneOf(mapOf("green_sea_turtle" to 1), size, EntityIdAllocator(0), Random(1)).creatures
        return TankScene(size, turtle, listOf(stone))
    }

    // ------------------------------------------------------------ keeping the lagoon

    @Test fun `a new lagoon has a floor of decorations, handed to the engine`() = runTest(dispatcher) {
        val vm = vm()
        assertTrue(engine.decor.isNotEmpty())
        assertEquals(engine.decor.size, vm.ui.value.decorCounts.values.sum())
        assertEquals(TankScene.MAX_DECOR - engine.decor.size, vm.ui.value.decorRoomLeft)
    }

    @Test fun `the saved lagoon comes back instead of a new one`() = runTest(dispatcher) {
        store.scene = savedLagoon()
        val vm = vm()
        assertEquals(1, vm.ui.value.total)
        assertEquals(setOf(EntityId(500)), engine.decor.keys)
        assertEquals(TankSize(190f, 420f, 55f).widthMm, engine.lastTank!!.widthMm, 0.01f)
    }

    @Test fun `the tank waits for the saved lagoon to be read`() = runTest(dispatcher) {
        val gate = CompletableDeferred<TankScene?>()
        val slow = object : LagoonStore {
            override suspend fun load(): TankScene? = gate.await()
            override suspend fun save(scene: TankScene) = Unit
            override suspend fun clear() = Unit
        }
        val vm = vm(lagoon = slow)
        assertFalse(vm.ui.value.ready)
        assertTrue(engine.creatures.isEmpty())
        gate.complete(savedLagoon())
        runCurrent()
        assertTrue(vm.ui.value.ready)
        assertEquals(1, engine.creatures.size)
    }

    @Test fun `a test run neither restores the saved lagoon nor overwrites it`() = runTest(dispatcher) {
        val saved = savedLagoon()
        store.scene = saved
        listOf(LaunchOptions(ci = true), LaunchOptions(fresh = true), LaunchOptions(population = mapOf("hermit_crab" to 2))).forEach { o ->
            engine = FakeEngine()
            val vm = vm(o)
            assertNotEquals(1, vm.ui.value.total)
            vm.release("green_chromis")
            advanceTimeBy(5_000)
            vm.saveNow()
            runCurrent()
            assertEquals(saved, store.scene)
        }
        assertEquals(0, store.saves)
    }

    @Test fun `a change is saved a moment later`() = runTest(dispatcher) {
        val vm = vm()
        assertEquals(0, store.saves)
        vm.release("hermit_crab")
        runCurrent()
        advanceTimeBy(TankViewModel.SAVE_DEBOUNCE_MS + 100)
        runCurrent()
        assertEquals(1, store.saves)
        assertEquals(vm.ui.value.total, store.scene!!.creatures.size)
        assertEquals(engine.decor.size, store.scene!!.decor.size)
    }

    @Test fun `leaving saves every creature where it is now`() = runTest(dispatcher) {
        val vm = vm()
        val id = engine.creatures.keys.first()
        engine.places[id] = CreatureState(id, 12f, 34f, 21f, 0f, 0)
        vm.saveNow()
        runCurrent()
        val back = store.scene!!.creature(id)!!
        assertEquals(Vec2(12f, 34f), back.spawnAt)
        assertEquals(21f, back.spawnDepthMm)
    }

    // ------------------------------------------------------------ arranging

    @Test fun `arranging turns taps into selection, drags into panning, and drops no food`() = runTest(dispatcher) {
        store.scene = savedLagoon()
        val vm = vm()
        vm.setEditing(true)
        runCurrent()
        assertTrue(vm.ui.value.edit.active)
        val stone = vm.ui.value.let { engine.decor.values.single() }
        vm.tap(vm.decorOnScreen(stone))
        runCurrent()
        val sel = vm.ui.value.edit.selection
        assertNotNull(sel)
        assertEquals(stone.id, sel!!.id)
        assertEquals("ʻŌfaʻi", sel.name)
        assertTrue((sel.centerPx - vm.decorOnScreen(stone)).length < 40f)
        assertTrue(engine.taps.isEmpty())
        // Open water: nothing selected any more.
        vm.tap(Vec2(60f, 2300f))
        runCurrent()
        assertNull(vm.ui.value.edit.selection)
        // A drag over open water pans; a long press drops nothing.
        val before = vm.camera!!.center
        vm.zoom(Vec2(540f, 1200f), 2f)
        val zoomed = vm.camera!!.center
        vm.sprinkle(Vec2(500f, 1200f), first = true)
        vm.sprinkle(Vec2(560f, 1300f), first = false)
        vm.endSprinkle()
        vm.dropPellet(Vec2(500f, 1200f))
        assertTrue(engine.drops.isEmpty())
        assertNotEquals(zoomed, vm.camera!!.center)
        assertNotNull(before)
    }

    @Test fun `dragging a decoration moves it, and one undo puts it back`() = runTest(dispatcher) {
        store.scene = savedLagoon()
        val vm = vm()
        vm.setEditing(true)
        val stone = engine.decor.values.single()
        val from = vm.decorOnScreen(stone)
        assertTrue(vm.beginDrag(from))
        for (i in 1..10) {
            vm.drag(from + Vec2(i * 20f, i * 10f))
            runCurrent()
        }
        vm.finishGesture()
        runCurrent()
        val moved = engine.decor.getValue(stone.id)
        assertTrue((moved.at - stone.at).length > 5f)
        assertEquals("moving ʻŌfaʻi", vm.ui.value.edit.undo.undo)
        vm.undo()
        runCurrent()
        assertEquals(stone.at, engine.decor.getValue(stone.id).at)
        assertTrue(vm.ui.value.edit.undo.canRedo)
    }

    @Test fun `a drag that starts off every decoration is not a move`() = runTest(dispatcher) {
        store.scene = savedLagoon()
        val vm = vm()
        vm.setEditing(true)
        assertFalse(vm.beginDrag(Vec2(30f, 2350f)))
        vm.setEditing(false)
        assertFalse("not arranging", vm.beginDrag(vm.decorOnScreen(engine.decor.values.single())))
    }

    @Test fun `placing a decoration puts it where the camera looks, selected, ready to arrange`() = runTest(dispatcher) {
        store.scene = savedLagoon()
        val vm = vm()
        vm.placeDecor(DecorCatalog.GiantClam.id)
        runCurrent()
        val clam = engine.decor.values.single { it.kindId == DecorCatalog.GiantClam.id }
        assertEquals(vm.camera!!.center, clam.at)
        assertTrue(vm.ui.value.edit.active)
        assertEquals(clam.id, vm.ui.value.edit.selection?.id)
        assertEquals(1, vm.ui.value.decorCounts[DecorCatalog.GiantClam.id])
        assertEquals("placing Pahua", vm.ui.value.edit.undo.undo)
    }

    @Test fun `turning and removing the selected decoration are undoable`() = runTest(dispatcher) {
        store.scene = savedLagoon()
        val vm = vm()
        vm.setEditing(true)
        val stone = engine.decor.values.single()
        vm.tap(vm.decorOnScreen(stone))
        vm.turnSelected()
        runCurrent()
        assertEquals(stone.angle + (PI / 6).toFloat(), engine.decor.getValue(stone.id).angle, 1e-5f)
        assertEquals("moving ʻŌfaʻi", vm.ui.value.edit.undo.undo)
        vm.removeSelected()
        runCurrent()
        assertTrue(engine.decor.isEmpty())
        assertNull(vm.ui.value.edit.selection)
        vm.undo()
        runCurrent()
        assertEquals(1, engine.decor.size)
        vm.undo()
        runCurrent()
        assertEquals(stone.angle, engine.decor.getValue(stone.id).angle, 1e-5f)
    }

    @Test fun `twisting with two fingers turns the selection, as one edit`() = runTest(dispatcher) {
        store.scene = savedLagoon()
        val vm = vm()
        vm.setEditing(true)
        val stone = engine.decor.values.single()
        vm.tap(vm.decorOnScreen(stone))
        repeat(5) {
            vm.rotateSelected(0.1f)
            runCurrent()
        }
        vm.finishGesture()
        runCurrent()
        assertEquals(stone.angle + 0.5f, engine.decor.getValue(stone.id).angle, 1e-4f)
        vm.undo()
        runCurrent()
        assertEquals(stone.angle, engine.decor.getValue(stone.id).angle, 1e-5f)
    }

    @Test fun `the net takes the creature tapped, and undo puts it back where it was caught`() = runTest(dispatcher) {
        store.scene = savedLagoon()
        val vm = vm()
        val turtle = engine.creatures.values.single()
        // It has swum somewhere since it was released.
        val now = CreatureState(turtle.id, 80f, 200f, 30f, 0f, 0)
        engine.places[turtle.id] = now
        vm.setEditing(true)
        vm.setTool(EditTool.NET)
        vm.tap(vm.screenOf(Vec2(now.x, now.y), now.z) + Vec2(12f, -8f))
        runCurrent()
        assertTrue(engine.creatures.isEmpty())
        val sweep = vm.ui.value.edit.sweep!!
        assertEquals("Honu", sweep.caught)
        assertEquals(1, sweep.seq)
        assertEquals("netting the Honu", vm.ui.value.edit.undo.undo)
        vm.undo()
        runCurrent()
        val back = engine.creatures.getValue(turtle.id)
        assertEquals(Vec2(80f, 200f), back.spawnAt)
        assertEquals(30f, back.spawnDepthMm)
    }

    @Test fun `the net comes up empty over open water`() = runTest(dispatcher) {
        store.scene = savedLagoon()
        val vm = vm()
        val turtle = engine.creatures.values.single()
        engine.places[turtle.id] = CreatureState(turtle.id, 30f, 30f, 30f, 0f, 0)
        vm.setEditing(true)
        vm.setTool(EditTool.NET)
        vm.tap(vm.screenOf(Vec2(160f, 400f), 30f))
        runCurrent()
        assertEquals(1, engine.creatures.size)
        assertNull(vm.ui.value.edit.sweep!!.caught)
    }

    @Test fun `finishing arranging clears the selection and goes back to feeding`() = runTest(dispatcher) {
        store.scene = savedLagoon()
        val vm = vm()
        vm.setEditing(true)
        vm.setTool(EditTool.NET)
        vm.setTool(EditTool.MOVE)
        vm.tap(vm.decorOnScreen(engine.decor.values.single()))
        vm.setEditing(false)
        runCurrent()
        assertNull(vm.ui.value.edit.selection)
        assertFalse(vm.ui.value.edit.active)
        vm.dropPellet(Vec2(500f, 1200f))
        assertEquals(FoodKind.PELLET, engine.drops.single().kind)
    }

    @Test fun `the selection ring follows the camera`() = runTest(dispatcher) {
        store.scene = savedLagoon()
        val vm = vm()
        vm.setEditing(true)
        vm.tap(vm.decorOnScreen(engine.decor.values.single()))
        val before = vm.ui.value.edit.selection!!
        vm.zoom(Vec2(100f, 100f), 2f)
        val after = vm.ui.value.edit.selection!!
        assertTrue(after.radiusPx > before.radiusPx * 1.5f)
        assertNotEquals(before.centerPx, after.centerPx)
    }
}
