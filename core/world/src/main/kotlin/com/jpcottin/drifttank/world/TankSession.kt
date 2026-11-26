package com.jpcottin.drifttank.world

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * What changed between two scenes, by id. The renderer follows the scene with
 * this rather than with events, so it can never miss one: whatever it last
 * applied, the next diff brings it level.
 */
data class SceneDiff(val added: List<CreatureSpec>, val removed: List<EntityId>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()

    companion object {
        fun between(appliedIds: Set<EntityId>, scene: TankScene): SceneDiff {
            val now = scene.creatures.associateBy { it.id }
            return SceneDiff(
                added = scene.creatures.filter { it.id !in appliedIds },
                removed = appliedIds.filter { it !in now },
            )
        }
    }
}

/**
 * The tank as this device sees it. Every change goes out on the [link] first
 * and is applied when it comes back, so a device on its own runs exactly the
 * path that a shared tank will: one way in, whatever the transport.
 */
class TankSession(
    initial: TankScene,
    private val link: Link,
    private val random: Random = Random.Default,
) {
    val self: DeviceId get() = link.self

    private val ids = EntityIdAllocator(link.self.slot).apply { advancePast(initial.creatures.map { it.id }) }
    private val registry = OwnershipRegistry(host = DeviceId(0, "host")).apply {
        initial.creatures.forEach { register(it.id, link.self) }
    }

    private val sceneState = MutableStateFlow(initial)
    val scene: StateFlow<TankScene> = sceneState.asStateFlow()

    /** Spawns sent but not back yet, so two quick adds cannot both fill the last room. */
    private val pendingSpawns = HashSet<EntityId>()

    fun ownership(id: EntityId): Ownership? = registry[id]

    /** Releases up to [count] creatures of a species; returns how many fit under the cap. */
    suspend fun add(speciesId: String, count: Int, around: Vec2? = null): Int {
        val species = SpeciesCatalog.require(speciesId)
        val room = (TankScene.MAX_CREATURES - sceneState.value.creatures.size - pendingSpawns.size).coerceAtLeast(0)
        val n = count.coerceAtMost(room)
        if (n <= 0) return 0
        Population.release(species, n, sceneState.value.size, ids, random, around).forEach {
            pendingSpawns += it.id
            link.send(Msg.Spawn(it, owner = link.self))
        }
        return n
    }

    suspend fun remove(id: EntityId) {
        link.send(Msg.Remove(id))
    }

    /** Starts over with the default population. */
    suspend fun reset() {
        val fresh = Population.defaultScene(sceneState.value.size, ids, random)
        link.send(Msg.Reset(fresh))
    }

    /** Applies everything that arrives on the link until the caller is cancelled. */
    suspend fun run() {
        link.incoming.collect { handle(it) }
    }

    /** Applies one message. Public so tests can drive the session without a coroutine collecting. */
    fun handle(envelope: Envelope) {
        when (val m = envelope.msg) {
            is Msg.Spawn -> {
                pendingSpawns -= m.creature.id
                val cur = sceneState.value
                if (cur.creatures.any { it.id == m.creature.id }) return
                if (SpeciesCatalog[m.creature.speciesId] == null) return
                if (cur.creatures.size >= TankScene.MAX_CREATURES) return
                registry.register(m.creature.id, m.owner)
                sceneState.value = cur.copy(creatures = cur.creatures + m.creature)
            }
            is Msg.Remove -> {
                val cur = sceneState.value
                if (cur.creatures.none { it.id == m.id }) return
                registry.unregister(m.id)
                sceneState.value = cur.copy(creatures = cur.creatures.filterNot { it.id == m.id })
            }
            is Msg.Reset -> {
                sceneState.value.creatures.forEach { registry.unregister(it.id) }
                m.scene.creatures.forEach { registry.register(it.id, envelope.from) }
                ids.advancePast(m.scene.creatures.map { it.id })
                sceneState.value = m.scene
            }
            is Msg.Handoff -> registry.handoff(m.id, m.from, m.to, m.epoch)
            is Msg.Claim -> registry.claim(m.id, m.by, m.epoch)
            is Msg.Owner, is Msg.Hello -> Unit // multi-device sessions (milestone 5)
        }
    }
}
