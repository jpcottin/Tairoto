package com.jpcottin.drifttank.engine

import com.jpcottin.drifttank.world.EntityId
import com.jpcottin.drifttank.world.SceneDiff
import com.jpcottin.drifttank.world.SpeciesCatalog
import com.jpcottin.drifttank.world.TankScene
import com.jpcottin.drifttank.world.TankSize

/**
 * Keeps the native tank level with the session's scene. Holds the ids it has
 * already handed over, so every new scene becomes one small diff.
 */
class SceneSync(private val tank: TankEngine) {
    private val applied = HashSet<EntityId>()
    private var size: TankSize? = null

    val appliedCount: Int get() = applied.size

    fun apply(scene: TankScene) {
        if (scene.size != size) {
            tank.setTank(scene.size)
            size = scene.size
        }
        val diff = SceneDiff.between(applied, scene)
        diff.removed.forEach {
            tank.remove(it)
            applied -= it
        }
        diff.added.forEach { spec ->
            val species = SpeciesCatalog[spec.speciesId] ?: return@forEach
            tank.spawn(spec, species)
            applied += spec.id
        }
    }
}
