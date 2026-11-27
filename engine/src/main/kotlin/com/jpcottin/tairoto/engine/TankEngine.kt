package com.jpcottin.tairoto.engine

import android.view.Surface
import com.jpcottin.tairoto.world.Camera
import com.jpcottin.tairoto.world.CreatureSpec
import com.jpcottin.tairoto.world.DecorKind
import com.jpcottin.tairoto.world.DecorSpec
import com.jpcottin.tairoto.world.EntityId
import com.jpcottin.tairoto.world.QualityTier
import com.jpcottin.tairoto.world.Species
import com.jpcottin.tairoto.world.TankSize
import com.jpcottin.tairoto.world.Vec2
import com.jpcottin.tairoto.world.Viewport
import com.jpcottin.tairoto.world.SharedSim
import com.jpcottin.tairoto.world.SimInput
import com.jpcottin.tairoto.world.SimOutput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/** What the app needs from a tank engine; [NativeTank] is the real one, tests use a fake. */
interface TankEngine : Closeable {
    fun setSurface(surface: Surface?)
    fun setTank(size: TankSize)
    fun setCamera(camera: Camera, viewport: Viewport)
    fun setQuality(tier: QualityTier)
    fun setDeterministic(on: Boolean, seed: Long)
    fun setPaused(paused: Boolean)
    /** Adds a creature; a [ghost] is simulated by another device and only shown here. */
    fun spawn(creature: CreatureSpec, species: Species, ghost: Boolean = false)
    fun remove(id: EntityId)
    fun clear()
    fun placeDecor(decor: DecorSpec, kind: DecorKind)
    fun moveDecor(decor: DecorSpec)
    fun removeDecor(id: EntityId)

    /** The decorations the engine itself holds, as JSON, for logs: [[id, kind, x, y], ...]. */
    fun describeDecor(): String = "[]"
    /** Where every creature is now: for saving the lagoon and for netting one. */
    fun snapshot(): List<CreatureState>
    fun tap(at: Vec2)
    fun dropFood(at: Vec2, kind: FoodKind, count: Int)
    fun stats(): EngineStats
    fun gpuName(): String

    /** Food made here carries this device's slot in its id, so two devices never make the same one. */
    fun setFoodSlot(slot: Int)

    /** One tick of a shared lagoon (see [SharedSim]). Blocks until the render thread has done it. */
    fun exchange(input: SimInput): SimOutput
}

/** The engine as the shared lagoon's simulation: the blocking exchange runs off the caller's thread. */
class EngineSim(private val engine: TankEngine, private val dispatcher: CoroutineDispatcher = Dispatchers.Default) : SharedSim {
    override suspend fun exchange(input: SimInput): SimOutput = withContext(dispatcher) { engine.exchange(input) }
}
