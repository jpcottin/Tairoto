package com.jpcottin.tairoto.engine

import com.jpcottin.tairoto.world.CreatureSpec
import com.jpcottin.tairoto.world.DecorCatalog
import com.jpcottin.tairoto.world.DecorSpec
import com.jpcottin.tairoto.world.EntityId
import com.jpcottin.tairoto.world.SceneDiff
import com.jpcottin.tairoto.world.SpeciesCatalog
import com.jpcottin.tairoto.world.TankScene
import com.jpcottin.tairoto.world.TankSize

/**
 * Keeps the native tank level with the session's scene. Holds the ids it has
 * already handed over, so every new scene becomes one small diff.
 */
class SceneSync(
    private val tank: TankEngine,
    /** In a shared lagoon, whether this device simulates a creature (or shows a ghost of another's). */
    var simulatesHere: (EntityId) -> Boolean = { true },
) {
    private val applied = HashMap<EntityId, CreatureSpec>()
    private val appliedDecor = HashMap<EntityId, DecorSpec>()
    private var size: TankSize? = null

    val appliedCount: Int get() = applied.size
    val appliedDecorCount: Int get() = appliedDecor.size

    /** What was handed over, for logs: id, kind and place of each decoration. */
    fun describeDecor(): String = appliedDecor.values.sortedBy { it.id.raw }
        .joinToString(",", "[", "]") { "[${it.id.raw},\"${it.kindId}\",${it.at.x},${it.at.y}]" }

    /** Forgets what was handed over, and empties the tank: the next [apply] starts from nothing. */
    fun reset() {
        tank.clear()
        appliedDecor.keys.forEach { tank.removeDecor(it) }
        applied.clear()
        appliedDecor.clear()
        size = null
    }

    fun apply(scene: TankScene) {
        if (scene.size != size) {
            tank.setTank(scene.size)
            size = scene.size
        }
        val diff = SceneDiff.between(applied.keys, scene, appliedDecor, applied)
        diff.decorRemoved.forEach {
            tank.removeDecor(it)
            appliedDecor -= it
        }
        diff.decorMoved.forEach {
            tank.moveDecor(it)
            appliedDecor[it.id] = it
        }
        diff.decorAdded.forEach { d ->
            val kind = DecorCatalog[d.kindId] ?: return@forEach
            tank.placeDecor(d, kind)
            appliedDecor[d.id] = d
        }
        diff.removed.forEach {
            tank.remove(it)
            applied -= it
        }
        diff.added.forEach { spec ->
            val species = SpeciesCatalog[spec.speciesId] ?: return@forEach
            tank.spawn(spec, species, ghost = !simulatesHere(spec.id), skin = scene.skin(spec.skin))
            applied[spec.id] = spec
        }
    }
}
