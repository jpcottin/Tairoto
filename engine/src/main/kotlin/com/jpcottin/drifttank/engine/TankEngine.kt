package com.jpcottin.drifttank.engine

import android.view.Surface
import com.jpcottin.drifttank.world.Camera
import com.jpcottin.drifttank.world.CreatureSpec
import com.jpcottin.drifttank.world.EntityId
import com.jpcottin.drifttank.world.QualityTier
import com.jpcottin.drifttank.world.Species
import com.jpcottin.drifttank.world.TankSize
import com.jpcottin.drifttank.world.Vec2
import com.jpcottin.drifttank.world.Viewport
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
    fun stats(): EngineStats
    fun gpuName(): String
}
