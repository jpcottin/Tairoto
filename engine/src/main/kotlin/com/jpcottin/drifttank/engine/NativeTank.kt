package com.jpcottin.drifttank.engine

import android.view.Surface
import com.jpcottin.drifttank.world.Camera
import com.jpcottin.drifttank.world.CreatureSpec
import com.jpcottin.drifttank.world.DepthProjection
import com.jpcottin.drifttank.world.EntityId
import com.jpcottin.drifttank.world.QualityTier
import com.jpcottin.drifttank.world.Species
import com.jpcottin.drifttank.world.TankSize
import com.jpcottin.drifttank.world.Vec2
import com.jpcottin.drifttank.world.Viewport

/** What the engine reports, a couple of times a second. */
data class EngineStats(
    val fps: Float = 0f,
    val workMs: Float = 0f,
    val creatures: Int = 0,
    val drawn: Int = 0,
    val frames: Long = 0,
    val quality: QualityTier = QualityTier.HIGH,
    val vulkan: VulkanState = VulkanState.WAITING,
    val sceneWidth: Int = 0,
    val sceneHeight: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val simTimeS: Float = 0f,
)

enum class VulkanState { WAITING, RENDERING, UNSUPPORTED }

/** Where a creature is right now in the native simulation. */
data class CreatureState(val id: EntityId, val x: Float, val y: Float, val z: Float, val speed: Float, val behavior: Int)

/**
 * The native tank: a render thread that owns the simulation and the Vulkan
 * renderer. Every call is a message to that thread, safe from any thread;
 * [setSurface] blocks until the window is attached or released.
 */
class NativeTank : TankEngine {
    private var handle: Long = nativeCreate()

    private fun h(): Long {
        check(handle != 0L) { "the tank was closed" }
        return handle
    }

    override fun setSurface(surface: Surface?) = nativeSetSurface(h(), surface)

    override fun setTank(size: TankSize) = nativeSetTank(h(), size.widthMm, size.heightMm, size.depthMm)

    override fun setCamera(camera: Camera, viewport: Viewport) = setCamera(camera, viewport, DepthProjection.DEFAULT)

    fun setCamera(camera: Camera, viewport: Viewport, projection: DepthProjection) {
        val o = camera.origin
        nativeSetCamera(
            h(), camera.center.x, camera.center.y, camera.pxPerMm(viewport), projection.cameraHeightMm,
            o.x, o.y, viewport.widthPx, viewport.heightPx,
        )
    }

    override fun setQuality(tier: QualityTier) = nativeSetQuality(h(), tier.nativeId)

    override fun setDeterministic(on: Boolean, seed: Long) = nativeSetDeterministic(h(), on, seed)

    override fun setPaused(paused: Boolean) = nativeSetPaused(h(), paused)

    override fun spawn(creature: CreatureSpec, species: Species) = nativeSpawn(
        h(), creature.id.raw, species.visualId, species.locomotion.nativeId, species.nativeParams(),
        creature.lengthMm, creature.seed, creature.spawnAt.x, creature.spawnAt.y, creature.spawnDepthMm,
    )

    override fun remove(id: EntityId) = nativeRemove(h(), id.raw)

    override fun clear() = nativeClear(h())

    /** A tap on the glass at a point of the water surface (mm). */
    override fun tap(at: Vec2) = nativeTap(h(), at.x, at.y)

    fun setTilt(x: Float, y: Float) = nativeSetTilt(h(), x, y)

    /** Runs the simulation for [seconds] without drawing. For tests. */
    fun stepForTest(seconds: Float) = nativeStepForTest(h(), seconds)

    override fun gpuName(): String = nativeGpuName(h())

    override fun stats(): EngineStats {
        val v = FloatArray(STATS_SIZE)
        nativeStats(h(), v)
        return EngineStats(
            fps = v[0],
            workMs = v[1],
            creatures = v[2].toInt(),
            drawn = v[3].toInt(),
            frames = v[4].toLong(),
            quality = QualityTier.fromNativeId(v[5].toInt()),
            vulkan = VulkanState.entries[v[6].toInt().coerceIn(0, 2)],
            sceneWidth = v[7].toInt(),
            sceneHeight = v[8].toInt(),
            width = v[9].toInt(),
            height = v[10].toInt(),
            simTimeS = v[11],
        )
    }

    fun snapshot(): List<CreatureState> {
        val raw = nativeSnapshot(h())
        return List(raw.size / SNAPSHOT_STRIDE) { i ->
            val b = i * SNAPSHOT_STRIDE
            fun f(k: Int) = Float.fromBits(raw[b + k].toInt())
            CreatureState(EntityId(raw[b]), f(1), f(2), f(3), f(4), raw[b + 5].toInt())
        }
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private companion object {
        const val STATS_SIZE = 12
        const val SNAPSHOT_STRIDE = 6

        init {
            System.loadLibrary("drifttank")
        }

        @JvmStatic external fun nativeCreate(): Long
        @JvmStatic external fun nativeDestroy(h: Long)
        @JvmStatic external fun nativeSetSurface(h: Long, surface: Surface?)
        @JvmStatic external fun nativeSetTank(h: Long, w: Float, ht: Float, d: Float)
        @JvmStatic external fun nativeSetCamera(h: Long, cx: Float, cy: Float, pxPerMm: Float, camHeight: Float, ox: Float, oy: Float, outW: Float, outH: Float)
        @JvmStatic external fun nativeSetQuality(h: Long, q: Int)
        @JvmStatic external fun nativeSetDeterministic(h: Long, on: Boolean, seed: Long)
        @JvmStatic external fun nativeSetPaused(h: Long, paused: Boolean)
        @JvmStatic external fun nativeSpawn(h: Long, id: Long, visual: Int, loco: Int, params: FloatArray, length: Float, seed: Int, x: Float, y: Float, z: Float)
        @JvmStatic external fun nativeRemove(h: Long, id: Long)
        @JvmStatic external fun nativeClear(h: Long)
        @JvmStatic external fun nativeTap(h: Long, x: Float, y: Float)
        @JvmStatic external fun nativeSetTilt(h: Long, x: Float, y: Float)
        @JvmStatic external fun nativeStepForTest(h: Long, seconds: Float)
        @JvmStatic external fun nativeStats(h: Long, out: FloatArray)
        @JvmStatic external fun nativeGpuName(h: Long): String
        @JvmStatic external fun nativeSnapshot(h: Long): LongArray
    }
}
