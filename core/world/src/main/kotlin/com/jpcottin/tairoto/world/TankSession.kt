package com.jpcottin.tairoto.world

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * What changed between two scenes, by id. The renderer follows the scene with
 * this rather than with events, so it can never miss one: whatever it last
 * applied, the next diff brings it level.
 */
data class SceneDiff(
    val added: List<CreatureSpec>,
    val removed: List<EntityId>,
    val decorAdded: List<DecorSpec> = emptyList(),
    val decorMoved: List<DecorSpec> = emptyList(),
    val decorRemoved: List<EntityId> = emptyList(),
) {
    val isEmpty: Boolean
        get() = added.isEmpty() && removed.isEmpty() && decorAdded.isEmpty() && decorMoved.isEmpty() && decorRemoved.isEmpty()

    companion object {
        fun between(appliedIds: Set<EntityId>, scene: TankScene, appliedDecor: Map<EntityId, DecorSpec> = emptyMap()): SceneDiff {
            val now = scene.creatures.associateBy { it.id }
            val decorNow = scene.decor.associateBy { it.id }
            return SceneDiff(
                added = scene.creatures.filter { it.id !in appliedIds },
                removed = appliedIds.filter { it !in now },
                decorAdded = scene.decor.filter { it.id !in appliedDecor },
                decorMoved = scene.decor.filter { d -> appliedDecor[d.id]?.let { it.at != d.at || it.angle != d.angle } == true },
                decorRemoved = appliedDecor.keys.filter { it !in decorNow },
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

    private val ids = EntityIdAllocator(link.self.slot).apply { advancePast(initial.allIds) }
    private val registry = OwnershipRegistry(host = DeviceId(0, "host")).apply {
        initial.creatures.forEach { register(it.id, link.self) }
    }

    private val sceneState = MutableStateFlow(initial)
    val scene: StateFlow<TankScene> = sceneState.asStateFlow()

    /** Spawns sent but not back yet, so two quick adds cannot both fill the last room. */
    private val pendingSpawns = HashSet<EntityId>()

    private val history = UndoHistory()
    private val undoState = MutableStateFlow(UndoState())
    /** What can be undone and redone, for the edit bar. */
    val undo: StateFlow<UndoState> = undoState.asStateFlow()

    private fun record(edit: Edit) {
        history.record(edit)
        undoState.value = history.state
    }

    fun ownership(id: EntityId): Ownership? = registry[id]

    /** Releases up to [count] creatures of a species; returns how many fit under the cap. */
    suspend fun add(speciesId: String, count: Int, around: Vec2? = null): Int {
        val species = SpeciesCatalog.require(speciesId)
        val room = (TankScene.MAX_CREATURES - sceneState.value.creatures.size - pendingSpawns.size).coerceAtLeast(0)
        val n = count.coerceAtMost(room)
        if (n <= 0) return 0
        val released = Population.release(species, n, sceneState.value.size, ids, random, around)
        spawnAll(released)
        record(Edit.Released(released))
        return n
    }

    private suspend fun spawnAll(creatures: List<CreatureSpec>) {
        val room = (TankScene.MAX_CREATURES - sceneState.value.creatures.size - pendingSpawns.size).coerceAtLeast(0)
        creatures.take(room).forEach {
            if (sceneState.value.creature(it.id) != null) return@forEach
            pendingSpawns += it.id
            link.send(Msg.Spawn(it, owner = link.self))
        }
    }

    suspend fun remove(id: EntityId) {
        link.send(Msg.Remove(id))
    }

    /**
     * Scoops a creature out with the net. [at] and [depthMm] are where it was
     * when it was caught, so undoing puts it back there. False if it is gone.
     */
    suspend fun net(id: EntityId, at: Vec2? = null, depthMm: Float? = null): Boolean {
        val spec = sceneState.value.creature(id) ?: return false
        val caught = spec.copy(spawnAt = at ?: spec.spawnAt, spawnDepthMm = depthMm ?: spec.spawnDepthMm)
        link.send(Msg.Remove(id))
        record(Edit.Netted(caught))
        return true
    }

    // ------------------------------------------------------------ decorations

    /** Puts a new decoration of a kind at [at]; null when the floor is full. */
    suspend fun placeDecor(kindId: String, at: Vec2): DecorSpec? {
        val kind = DecorCatalog.require(kindId)
        if (sceneState.value.decor.size >= TankScene.MAX_DECOR) return null
        val size = sceneState.value.size
        val spec = DecorCatalog.make(kind, clampToSand(at, size), ids, random)
        link.send(Msg.PlaceDecor(spec))
        record(Edit.Placed(spec))
        return spec
    }

    /**
     * Moves a decoration while a finger drags it: not an edit of its own (a
     * drag would fill the history); [endDrag] records the whole move.
     */
    suspend fun dragDecor(id: EntityId, at: Vec2, angle: Float) {
        if (sceneState.value.decor(id) == null) return
        link.send(Msg.MoveDecor(id, clampToSand(at, sceneState.value.size), angle))
    }

    /** The drag is over: one undoable move from where it was when the drag began. */
    fun endDrag(before: DecorSpec) {
        val now = sceneState.value.decor(before.id) ?: return
        if (now.at != before.at || now.angle != before.angle) record(Edit.Moved(before, now))
    }

    suspend fun removeDecor(id: EntityId): Boolean {
        val spec = sceneState.value.decor(id) ?: return false
        link.send(Msg.RemoveDecor(id))
        record(Edit.Removed(spec))
        return true
    }

    private fun clampToSand(p: Vec2, size: TankSize) = Vec2(p.x.coerceIn(0f, size.widthMm), p.y.coerceIn(0f, size.heightMm))

    // ------------------------------------------------------------ undo

    /** Takes back the last edit. False if there was none. */
    suspend fun undo(): Boolean {
        val edit = history.popUndo() ?: return false
        undoState.value = history.state
        when (edit) {
            is Edit.Released -> edit.creatures.forEach { link.send(Msg.Remove(it.id)) }
            is Edit.Netted -> spawnAll(listOf(edit.creature))
            is Edit.Placed -> link.send(Msg.RemoveDecor(edit.decor.id))
            is Edit.Moved -> link.send(Msg.MoveDecor(edit.from.id, edit.from.at, edit.from.angle))
            is Edit.Removed -> link.send(Msg.PlaceDecor(edit.decor))
        }
        return true
    }

    /** Does again the last edit undone. False if there was none. */
    suspend fun redo(): Boolean {
        val edit = history.popRedo() ?: return false
        undoState.value = history.state
        when (edit) {
            is Edit.Released -> spawnAll(edit.creatures)
            is Edit.Netted -> link.send(Msg.Remove(edit.creature.id))
            is Edit.Placed -> link.send(Msg.PlaceDecor(edit.decor))
            is Edit.Moved -> link.send(Msg.MoveDecor(edit.to.id, edit.to.at, edit.to.angle))
            is Edit.Removed -> link.send(Msg.RemoveDecor(edit.decor.id))
        }
        return true
    }

    /**
     * Widens the lagoon to cover a bigger window (a foldable opened, a window
     * stretched): every creature stays where it is. It never shrinks; on a
     * smaller window the camera pans over it instead. True if it grew.
     */
    fun growTo(size: TankSize): Boolean {
        val before = sceneState.value.size
        val grown = before.grownTo(size)
        if (grown == before) return false
        sceneState.value = sceneState.value.copy(size = grown)
        return true
    }

    /** Starts over with the default population and floor. Nothing before it can be undone. */
    suspend fun reset() {
        val fresh = Population.defaultScene(sceneState.value.size, ids, random)
        history.clear()
        undoState.value = history.state
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
                ids.advancePast(m.scene.allIds)
                sceneState.value = m.scene
            }
            is Msg.PlaceDecor -> {
                val cur = sceneState.value
                if (cur.decor(m.decor.id) != null || DecorCatalog[m.decor.kindId] == null) return
                if (cur.decor.size >= TankScene.MAX_DECOR) return
                ids.advancePast(listOf(m.decor.id))
                sceneState.value = cur.copy(decor = cur.decor + m.decor)
            }
            is Msg.MoveDecor -> {
                val cur = sceneState.value
                if (cur.decor(m.id) == null) return
                sceneState.value = cur.copy(decor = cur.decor.map { if (it.id == m.id) it.copy(at = m.at, angle = m.angle) else it })
            }
            is Msg.RemoveDecor -> {
                val cur = sceneState.value
                if (cur.decor(m.id) == null) return
                sceneState.value = cur.copy(decor = cur.decor.filterNot { it.id == m.id })
            }
            is Msg.Handoff -> registry.handoff(m.id, m.from, m.to, m.epoch)
            is Msg.Claim -> registry.claim(m.id, m.by, m.epoch)
            else -> Unit // the shared lagoon's own messages: [SharedTank] handles them
        }
    }
}
