package com.jpcottin.tairoto.engine

import android.view.Surface
import com.jpcottin.tairoto.world.Camera
import com.jpcottin.tairoto.world.CreatureSpec
import com.jpcottin.tairoto.world.EntityId
import com.jpcottin.tairoto.world.QualityTier
import com.jpcottin.tairoto.world.Species
import com.jpcottin.tairoto.world.TankSize
import com.jpcottin.tairoto.world.Vec2
import com.jpcottin.tairoto.world.Viewport
import java.io.Closeable

/** What the app needs from a tank engine; [NativeTank] is the real one, tests use a fake. */
interface TankEngine : Closeable {
    fun setSurface(surface: Surface?)
    fun setTank(size: TankSize)
    fun setCamera(camera: Camera, viewport: Viewport)
    fun setQuality(tier: QualityTier)
    fun setDeterministic(on: Boolean, seed: Long)
    fun setPaused(paused: Boolean)
    fun spawn(creature: CreatureSpec, species: Species)
    fun remove(id: EntityId)
    fun clear()
    fun tap(at: Vec2)
    fun dropFood(at: Vec2, kind: FoodKind, count: Int)
    fun stats(): EngineStats
    fun gpuName(): String
}
