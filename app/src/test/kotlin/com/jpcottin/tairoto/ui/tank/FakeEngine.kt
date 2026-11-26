package com.jpcottin.tairoto.ui.tank

import android.view.Surface
import com.jpcottin.tairoto.engine.EngineStats
import com.jpcottin.tairoto.engine.FoodKind
import com.jpcottin.tairoto.engine.TankEngine
import com.jpcottin.tairoto.engine.VulkanState
import com.jpcottin.tairoto.world.Camera
import com.jpcottin.tairoto.world.CreatureSpec
import com.jpcottin.tairoto.world.EntityId
import com.jpcottin.tairoto.world.QualityTier
import com.jpcottin.tairoto.world.Species
import com.jpcottin.tairoto.world.TankSize
import com.jpcottin.tairoto.world.Vec2
import com.jpcottin.tairoto.world.Viewport

/** Records what the view model asks of the engine. */
class FakeEngine : TankEngine {
    val creatures = LinkedHashMap<EntityId, CreatureSpec>()
    var lastTank: TankSize? = null
    var camera: Camera? = null
    var viewport: Viewport? = null
    var lastQuality: QualityTier? = null
    var deterministic: Pair<Boolean, Long>? = null
    val taps = mutableListOf<Vec2>()
    data class Drop(val at: Vec2, val kind: FoodKind, val count: Int)
    val drops = mutableListOf<Drop>()
    var surfaces = 0
    var closed = false
    var stats = EngineStats(vulkan = VulkanState.RENDERING, fps = 60f)

    override fun setSurface(surface: Surface?) { surfaces++ }
    override fun setTank(size: TankSize) { lastTank = size }
    override fun setCamera(camera: Camera, viewport: Viewport) { this.camera = camera; this.viewport = viewport }
    override fun setQuality(tier: QualityTier) { lastQuality = tier }
    override fun setDeterministic(on: Boolean, seed: Long) { deterministic = on to seed }
    override fun setPaused(paused: Boolean) = Unit
    override fun spawn(creature: CreatureSpec, species: Species) { creatures[creature.id] = creature }
    override fun remove(id: EntityId) { creatures.remove(id) }
    override fun clear() = creatures.clear()
    override fun tap(at: Vec2) { taps += at }
    override fun dropFood(at: Vec2, kind: FoodKind, count: Int) { drops += Drop(at, kind, count) }
    override fun stats(): EngineStats = stats
    override fun gpuName(): String = "Fake GPU"
    override fun close() { closed = true }
}

class FakeSignals(
    var headroom: Float? = 0.3f,
    var refresh: Float = 60f,
    var now: Long = 0L,
    var simulated: Boolean = false,
) : DeviceSignals {
    override val thermalIsSimulated: Boolean get() = simulated
    override fun thermalHeadroom(): Float? = headroom
    override fun refreshRateHz(): Float = refresh
    override fun nowMs(): Long = now
}
