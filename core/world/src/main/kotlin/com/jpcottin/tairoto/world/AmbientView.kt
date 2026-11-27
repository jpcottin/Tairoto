package com.jpcottin.tairoto.world

import kotlin.math.PI
import kotlin.math.sin

/**
 * Where the lagoon looks when nobody holds it: as a live wallpaper, across the
 * lagoon as the home screens are swiped (life size, the way the app shows
 * it); as a screensaver, roaming slowly on its own.
 */
object AmbientView {
    /** One lap of the drift across the lagoon, and one up and down: slow, and never in step. */
    const val DRIFT_ACROSS_S = 240.0
    const val DRIFT_ALONG_S = 370.0

    /**
     * The camera for the home screens swiped to [offsetX] (0 the first, 1 the
     * last), and [offsetY] likewise for launchers that scroll up and down.
     */
    fun forOffset(offsetX: Float, offsetY: Float, viewport: Viewport, tank: TankSize): Camera {
        val c = Camera.initial(viewport, tank)
        val half = c.floorHalfExtent(viewport, c.zoom, tank)
        fun along(o: Float, h: Float, side: Float): Float {
            if (2f * h >= side) return side / 2f
            return h + (side - 2f * h) * o.coerceIn(0f, 1f)
        }
        return c.copy(center = Vec2(along(offsetX, half.x, tank.widthMm), along(offsetY, half.y, tank.heightMm))).clamped(viewport, tank)
    }

    /** The camera [timeS] seconds into a drift round the lagoon. */
    fun drifting(timeS: Double, viewport: Viewport, tank: TankSize): Camera {
        val x = 0.5 + 0.5 * sin(2 * PI * timeS / DRIFT_ACROSS_S)
        val y = 0.5 + 0.35 * sin(2 * PI * timeS / DRIFT_ALONG_S + 1.0)
        return forOffset(x.toFloat(), y.toFloat(), viewport, tank)
    }
}
