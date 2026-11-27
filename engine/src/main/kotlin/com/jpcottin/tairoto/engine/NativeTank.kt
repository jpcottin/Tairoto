package com.jpcottin.tairoto.engine

import android.view.Surface
import com.jpcottin.tairoto.world.Camera
import com.jpcottin.tairoto.world.CreatureSpec
import com.jpcottin.tairoto.world.DecorKind
import com.jpcottin.tairoto.world.DecorSpec
import com.jpcottin.tairoto.world.DepthProjection
import com.jpcottin.tairoto.world.EntityId
import com.jpcottin.tairoto.world.QualityTier
import com.jpcottin.tairoto.world.SimInput
import com.jpcottin.tairoto.world.SimOutput
import com.jpcottin.tairoto.world.Species
import com.jpcottin.tairoto.world.TankSize
import com.jpcottin.tairoto.world.Vec2
import com.jpcottin.tairoto.world.Viewport

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
    /** Pieces of food in the tank, their mass, and all that has been eaten (mg). */
    val food: Int = 0,
    val foodMg: Float = 0f,
    val eatenMg: Float = 0f,
    /** Mean hunger of the creatures: 0 full, 1 starving. */
    val hunger: Float = 0f,
)

/** What can be dropped in from above. */
enum class FoodKind(val nativeId: Int) { FLAKE(0), PELLET(1) }

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
            o.x, o.y, viewport.widthPx, viewport.heightPx, camera.turns,
        )
    }

    override fun setQuality(tier: QualityTier) = nativeSetQuality(h(), tier.nativeId)

    override fun setDeterministic(on: Boolean, seed: Long) = nativeSetDeterministic(h(), on, seed)

    override fun setPaused(paused: Boolean) = nativeSetPaused(h(), paused)

    override fun spawn(creature: CreatureSpec, species: Species, ghost: Boolean) = nativeSpawn(
        h(), creature.id.raw, species.visualId, species.locomotion.nativeId, species.nativeParams(),
        creature.lengthMm, creature.seed, creature.spawnAt.x, creature.spawnAt.y, creature.spawnDepthMm, ghost,
    )

    override fun setFoodSlot(slot: Int) = nativeSetFoodSlot(h(), slot)

    override fun exchange(input: SimInput): SimOutput {
        val p = NetPacking.pack(input)
        val r = nativeExchange(h(), p.header, p.longs, p.floats)
        return NetPacking.unpack(input, r[0] as IntArray, r[1] as LongArray, r[2] as FloatArray, r[3] as DoubleArray)
    }

    override fun remove(id: EntityId) = nativeRemove(h(), id.raw)

    override fun clear() = nativeClear(h())

    override fun placeDecor(decor: DecorSpec, kind: DecorKind) = nativePlaceDecor(
        h(), decor.id.raw, kind.nativeId, decor.seed, decor.at.x, decor.at.y, decor.angle,
        kind.radiusFor(decor), kind.heightFor(decor),
    )

    override fun moveDecor(decor: DecorSpec) = nativeMoveDecor(h(), decor.id.raw, decor.at.x, decor.at.y, decor.angle)

    override fun removeDecor(id: EntityId) = nativeRemoveDecor(h(), id.raw)

    /** A tap on the glass at a point of the water surface (mm). */
    override fun tap(at: Vec2) = nativeTap(h(), at.x, at.y)

    override fun dropFood(at: Vec2, kind: FoodKind, count: Int) = nativeDropFood(h(), at.x, at.y, kind.nativeId, count)

    fun setTilt(x: Float, y: Float) = nativeSetTilt(h(), x, y)

    override fun setMaxFps(fps: Int) = nativeSetMaxFps(h(), fps.coerceAtLeast(0))

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
            food = v[12].toInt(),
            foodMg = v[13],
            eatenMg = v[14],
            hunger = v[15],
        )
    }

    override fun describeDecor(): String {
        val v = nativeDecor(h())
        return (0 until v.size / 4).joinToString(",", "[", "]") { i ->
            val b = i * 4
            "[${v[b].toLong()},${v[b + 1].toInt()},${v[b + 2].toFloat()},${v[b + 3].toFloat()}]"
        }
    }

    override fun snapshot(): List<CreatureState> {
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
        const val STATS_SIZE = 16
        const val SNAPSHOT_STRIDE = 6

        init {
            System.loadLibrary("tairoto")
        }

        @JvmStatic external fun nativeCreate(): Long
        @JvmStatic external fun nativeDestroy(h: Long)
        @JvmStatic external fun nativeSetSurface(h: Long, surface: Surface?)
        @JvmStatic external fun nativeSetTank(h: Long, w: Float, ht: Float, d: Float)
        @JvmStatic external fun nativeSetCamera(h: Long, cx: Float, cy: Float, pxPerMm: Float, camHeight: Float, ox: Float, oy: Float, outW: Float, outH: Float, turns: Int)
        @JvmStatic external fun nativeSetQuality(h: Long, q: Int)
        @JvmStatic external fun nativeSetDeterministic(h: Long, on: Boolean, seed: Long)
        @JvmStatic external fun nativeSetPaused(h: Long, paused: Boolean)
        @JvmStatic external fun nativeSpawn(h: Long, id: Long, visual: Int, loco: Int, params: FloatArray, length: Float, seed: Int, x: Float, y: Float, z: Float, ghost: Boolean)
        @JvmStatic external fun nativeSetFoodSlot(h: Long, slot: Int)
        @JvmStatic external fun nativeExchange(h: Long, header: IntArray, longs: LongArray, floats: FloatArray): Array<Any>
        @JvmStatic external fun nativeRemove(h: Long, id: Long)
        @JvmStatic external fun nativeClear(h: Long)
        @JvmStatic external fun nativePlaceDecor(h: Long, id: Long, kind: Int, seed: Int, x: Float, y: Float, angle: Float, radius: Float, height: Float)
        @JvmStatic external fun nativeMoveDecor(h: Long, id: Long, x: Float, y: Float, angle: Float)
        @JvmStatic external fun nativeRemoveDecor(h: Long, id: Long)
        @JvmStatic external fun nativeTap(h: Long, x: Float, y: Float)
        @JvmStatic external fun nativeDropFood(h: Long, x: Float, y: Float, kind: Int, count: Int)
        @JvmStatic external fun nativeSetTilt(h: Long, x: Float, y: Float)
        @JvmStatic external fun nativeSetMaxFps(h: Long, fps: Int)
        @JvmStatic external fun nativeStepForTest(h: Long, seconds: Float)
        @JvmStatic external fun nativeStats(h: Long, out: FloatArray)
        @JvmStatic external fun nativeGpuName(h: Long): String
        @JvmStatic external fun nativeSnapshot(h: Long): LongArray
        @JvmStatic external fun nativeDecor(h: Long): DoubleArray
    }
}
