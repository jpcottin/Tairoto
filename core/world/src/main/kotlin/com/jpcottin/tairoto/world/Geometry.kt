package com.jpcottin.tairoto.world

import kotlinx.serialization.Serializable
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A point or offset on the table plane, in millimetres. x grows right, y grows down. */
@Serializable
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Float) = Vec2(x * k, y * k)
    operator fun div(k: Float) = Vec2(x / k, y / k)
    val length: Float get() = hypot(x, y)

    companion object {
        val Zero = Vec2(0f, 0f)
    }
}

/**
 * The size of the tank in millimetres. [depthMm] runs from the water surface
 * (z = 0) down to the sand (z = depthMm).
 */
@Serializable
data class TankSize(val widthMm: Float, val heightMm: Float, val depthMm: Float) {
    init {
        require(widthMm > 0f && heightMm > 0f && depthMm > 0f) { "tank must have a positive size: $this" }
    }

    val center: Vec2 get() = Vec2(widthMm / 2f, heightMm / 2f)

    fun contains(p: Vec2): Boolean = p.x in 0f..widthMm && p.y in 0f..heightMm

    /** At least as wide and as long as [other]: a lagoon only ever grows. */
    fun grownTo(other: TankSize): TankSize =
        TankSize(maxOf(widthMm, other.widthMm), maxOf(heightMm, other.heightMm), depthMm)

    companion object {
        /** The sand is this far below the surface on every device, so fish keep one size everywhere. */
        const val DEFAULT_DEPTH_MM = 55f

        /** Tank sides are rounded to this so a saved tank reads as a sensible size. */
        private const val ROUND_TO_MM = 10f
        private const val MIN_SIDE_MM = 80f
        private const val MAX_SIDE_MM = 1200f

        /**
         * A tank for a device on its own: [areaFactor] times the screen's area,
         * with the screen's aspect ratio, so the phone is a window you pan
         * across. The camera clamps its view at sand depth, which is wider than
         * at the surface, so every side also gets room for that.
         */
        fun forScreen(screen: ScreenMm, areaFactor: Float = 3f, depthMm: Float = DEFAULT_DEPTH_MM): TankSize {
            require(areaFactor >= 1f) { "a tank smaller than its screen cannot be panned" }
            val side = sqrt(areaFactor)
            val floorWiden = 1f / DepthProjection.DEFAULT.scaleAt(depthMm)
            fun round(v: Float) = ((v / ROUND_TO_MM).roundToInt() * ROUND_TO_MM)
                .coerceIn(MIN_SIDE_MM, MAX_SIDE_MM)
            return TankSize(
                widthMm = round(screen.widthMm * side * floorWiden),
                heightMm = round(screen.heightMm * side * floorWiden),
                depthMm = depthMm,
            )
        }
    }
}

/**
 * A screen's physical size, from its pixels and its dots per inch, and the
 * width of the frame around it ([bezelMm], from the edge of the picture to the
 * edge of the device): two devices lying edge to edge leave both frames
 * between their pictures. An emulator window has none.
 */
@Serializable
data class ScreenMm(val widthMm: Float, val heightMm: Float, val bezelMm: Float = PHONE_BEZEL_MM) {
    companion object {
        private const val MM_PER_INCH = 25.4f

        /** A modern phone's frame: the glass edge plus the band around the picture. */
        const val PHONE_BEZEL_MM = 3f

        fun fromPixels(widthPx: Int, heightPx: Int, xdpi: Float, ydpi: Float, bezelMm: Float = PHONE_BEZEL_MM): ScreenMm {
            require(widthPx > 0 && heightPx > 0) { "screen has no pixels" }
            // Some devices and emulators report 0 or nonsense dpi; fall back to
            // a typical phone density instead of a tank kilometres wide.
            val dx = if (xdpi in 72f..1200f) xdpi else 420f
            val dy = if (ydpi in 72f..1200f) ydpi else dx
            return ScreenMm(widthPx / dx * MM_PER_INCH, heightPx / dy * MM_PER_INCH, bezelMm.coerceIn(0f, 20f))
        }

        /** Pixels per millimetre at a given dpi (with the same fallback). */
        fun pxPerMm(dpi: Float): Float = (if (dpi in 72f..1200f) dpi else 420f) / MM_PER_INCH
    }
}

/**
 * The top-down camera sits [cameraHeightMm] above the surface, so something
 * deeper looks smaller: scale(z) = H / (H + z). The native renderer uses the
 * exact same formula (engine/src/main/cpp/render/camera.h).
 */
data class DepthProjection(val cameraHeightMm: Float) {
    init {
        require(cameraHeightMm > 0f)
    }

    fun scaleAt(depthMm: Float): Float = cameraHeightMm / (cameraHeightMm + depthMm.coerceAtLeast(0f))

    companion object {
        val DEFAULT = DepthProjection(cameraHeightMm = 180f)
    }
}

/**
 * A device lying on the table turned clockwise by a number of quarter turns
 * (0 upright, 1 its top to the right, 2 upside down, 3 its top to the left).
 * Its glass has its own x (right) and y (down) as the device sees them.
 */
object Turns {
    fun norm(turns: Int): Int = ((turns % 4) + 4) % 4

    /** A vector on the glass of a device turned [turns], as it lies on the table. */
    fun toTable(v: Vec2, turns: Int): Vec2 = when (norm(turns)) {
        0 -> v
        1 -> Vec2(-v.y, v.x)
        2 -> Vec2(-v.x, -v.y)
        else -> Vec2(v.y, -v.x)
    }

    /** A vector on the table, as the glass of a device turned [turns] sees it. */
    fun toScreen(v: Vec2, turns: Int): Vec2 = toTable(v, -turns)

    /** The quarter turns in radians, clockwise on the table (y grows down). */
    fun radians(turns: Int): Float = norm(turns) * (Math.PI.toFloat() / 2f)
}

/** An edge of a screen, as the device sees it, in clockwise order. */
enum class Edge(val outward: Vec2) {
    TOP(Vec2(0f, -1f)),
    RIGHT(Vec2(1f, 0f)),
    BOTTOM(Vec2(0f, 1f)),
    LEFT(Vec2(-1f, 0f));

    /** The edge it becomes on the table when its device is turned [turns]. */
    fun turned(turns: Int): Edge = entries[Turns.norm(ordinal + turns)]

    val opposite: Edge get() = entries[(ordinal + 2) % 4]
}
