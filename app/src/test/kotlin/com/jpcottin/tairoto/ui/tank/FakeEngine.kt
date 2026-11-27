package com.jpcottin.tairoto.ui.tank

import android.view.Surface
import com.jpcottin.tairoto.engine.CreatureState
import com.jpcottin.tairoto.engine.EngineStats
import com.jpcottin.tairoto.engine.FoodKind
import com.jpcottin.tairoto.engine.TankEngine
import com.jpcottin.tairoto.engine.VulkanState
import com.jpcottin.tairoto.world.Camera
import com.jpcottin.tairoto.world.CreatureSpec
import com.jpcottin.tairoto.world.DecorKind
import com.jpcottin.tairoto.world.DecorSpec
import com.jpcottin.tairoto.world.EntityId
import com.jpcottin.tairoto.world.NetCreature
import com.jpcottin.tairoto.world.QualityTier
import com.jpcottin.tairoto.world.SimInput
import com.jpcottin.tairoto.world.SimOutput
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
    /** The last volume the engine was asked to play at, or null if never. */
    var soundVolume: Float? = null
    override fun setSound(volume: Float) { soundVolume = volume }
    override fun setQuality(tier: QualityTier) { lastQuality = tier }
    override fun setDeterministic(on: Boolean, seed: Long) { deterministic = on to seed }
    var paused: Boolean? = null
    override fun setPaused(paused: Boolean) { this.paused = paused }
    override fun spawn(creature: CreatureSpec, species: Species, ghost: Boolean) {
        creatures[creature.id] = creature
        if (ghost) ghosts += creature.id else ghosts -= creature.id
    }
    override fun remove(id: EntityId) { creatures.remove(id); ghosts -= id }
    override fun clear() { creatures.clear(); ghosts.clear() }

    // A shared lagoon: which creatures are ghosts of another device's, and what came in.
    val ghosts = HashSet<EntityId>()
    /** The slot food made here carries. */
    var slotOfFood = 0
        private set
    val remoteTaps = mutableListOf<Vec2>()
    var exchanges = 0
    override fun setFoodSlot(slot: Int) { slotOfFood = slot }
    override fun exchange(input: SimInput): SimOutput {
        exchanges++
        val released = input.release.filter { it in creatures && it !in ghosts }.map { id -> ghosts += id; state(id) }
        input.take.forEach { ghosts -= it.id }
        input.ghosts.forEach { g -> if (g.state.id in ghosts) places[g.state.id] = CreatureState(g.state.id, g.state.x, g.state.y, g.state.z, g.state.speed, g.state.behavior) }
        remoteTaps += input.taps
        return SimOutput(owned = creatures.keys.filter { it !in ghosts }.map { state(it) }, released = released)
    }
    private fun state(id: EntityId): NetCreature {
        val p = snapshot().first { it.id == id }
        return NetCreature(id, p.x, p.y, p.z, 0f, p.speed, behavior = p.behavior)
    }
    val decor = LinkedHashMap<EntityId, DecorSpec>()
    /** Where creatures are now, if a test moved them; otherwise where they were released. */
    val places = HashMap<EntityId, CreatureState>()
    override fun placeDecor(decor: DecorSpec, kind: DecorKind) { this.decor[decor.id] = decor }
    // Like the native engine: a move changes where it stands and which way it faces, nothing else.
    override fun moveDecor(decor: DecorSpec) { this.decor[decor.id]?.let { this.decor[decor.id] = it.copy(at = decor.at, angle = decor.angle) } }
    override fun removeDecor(id: EntityId) { decor.remove(id) }
    override fun snapshot(): List<CreatureState> = creatures.values.map {
        places[it.id] ?: CreatureState(it.id, it.spawnAt.x, it.spawnAt.y, it.spawnDepthMm, 0f, 0)
    }
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
