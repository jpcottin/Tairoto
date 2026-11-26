package com.jpcottin.drifttank.world

import kotlin.math.max
import kotlin.math.min

/**
 * The screen a camera draws into: its size in pixels and how many pixels make
 * a millimetre at zoom 1 (from the display's dpi, so zoom 1 is life size).
 */
data class Viewport(val widthPx: Float, val heightPx: Float, val basePxPerMm: Float) {
    init {
        require(widthPx > 0f && heightPx > 0f && basePxPerMm > 0f) { "empty viewport: $this" }
    }

    val center: Vec2 get() = Vec2(widthPx / 2f, heightPx / 2f)
}

/**
 * A top-down camera over the tank.
 *
 * Projection of a point p at depth z (the same formula as the shaders):
 *
 *     screen = viewportCenter + ((p - O) * s(z) + (O - center)) * pxPerMm
 *
 * where s(z) is [DepthProjection.scaleAt], pxPerMm = basePxPerMm * zoom, and O
 * is the parallax origin: the point that does not move between depths. On a
 * single device O is the camera centre, so panning slides the sand slower than
 * the fish above it. With several devices O becomes one point on the table,
 * shared by all of them, so a fish crossing a screen edge lines up.
 */
data class Camera(
    val center: Vec2,
    val zoom: Float = 1f,
    val parallaxOrigin: Vec2? = null,
) {
    val origin: Vec2 get() = parallaxOrigin ?: center

    fun pxPerMm(viewport: Viewport): Float = viewport.basePxPerMm * zoom

    fun worldToScreen(p: Vec2, depthMm: Float, viewport: Viewport, projection: DepthProjection = DepthProjection.DEFAULT): Vec2 {
        val s = projection.scaleAt(depthMm)
        val o = origin
        return viewport.center + ((p - o) * s + (o - center)) * pxPerMm(viewport)
    }

    fun screenToWorld(screen: Vec2, depthMm: Float, viewport: Viewport, projection: DepthProjection = DepthProjection.DEFAULT): Vec2 {
        val s = projection.scaleAt(depthMm)
        val o = origin
        return o + ((screen - viewport.center) / pxPerMm(viewport) - (o - center)) / s
    }

    /** Moves the camera so that content at [referenceDepthMm] follows a finger that moved by [deltaPx]. */
    fun panned(deltaPx: Vec2, viewport: Viewport, tank: TankSize, referenceDepthMm: Float = tank.depthMm * REFERENCE_DEPTH, projection: DepthProjection = DepthProjection.DEFAULT): Camera {
        val s = projection.scaleAt(referenceDepthMm)
        return copy(center = center - deltaPx / (pxPerMm(viewport) * s)).clamped(viewport, tank, projection)
    }

    /** Zooms by [factor] keeping the point under [focusPx] (at [referenceDepthMm]) where it is. */
    fun zoomedAbout(focusPx: Vec2, factor: Float, viewport: Viewport, tank: TankSize, referenceDepthMm: Float = tank.depthMm * REFERENCE_DEPTH, projection: DepthProjection = DepthProjection.DEFAULT): Camera {
        if (factor <= 0f || factor.isNaN()) return this
        val s = projection.scaleAt(referenceDepthMm)
        val offset = focusPx - viewport.center
        val anchor = center + offset / (pxPerMm(viewport) * s)
        val newZoom = (zoom * factor).coerceIn(minZoom(viewport, tank, projection), MAX_ZOOM)
        val newCenter = anchor - offset / (viewport.basePxPerMm * newZoom * s)
        return copy(center = newCenter, zoom = newZoom).clamped(viewport, tank, projection)
    }

    /**
     * Keeps the whole view inside the tank at sand depth, the widest view of
     * the scene (deeper is smaller, so more of it fits). On an axis where the
     * view is wider than the tank the camera is centred on that axis.
     */
    fun clamped(viewport: Viewport, tank: TankSize, projection: DepthProjection = DepthProjection.DEFAULT): Camera {
        val z = zoom.coerceIn(minZoom(viewport, tank, projection), MAX_ZOOM)
        val half = floorHalfExtent(viewport, z, tank, projection)
        fun axis(c: Float, h: Float, side: Float) = if (2f * h >= side) side / 2f else c.coerceIn(h, side - h)
        return copy(
            center = Vec2(axis(center.x, half.x, tank.widthMm), axis(center.y, half.y, tank.heightMm)),
            zoom = z,
        )
    }

    /** Half the size, in mm, of the sand the view shows. */
    fun floorHalfExtent(viewport: Viewport, zoomValue: Float, tank: TankSize, projection: DepthProjection = DepthProjection.DEFAULT): Vec2 {
        val k = viewport.basePxPerMm * zoomValue * projection.scaleAt(tank.depthMm)
        return Vec2(viewport.widthPx / 2f / k, viewport.heightPx / 2f / k)
    }

    companion object {
        const val MAX_ZOOM = 3f
        private const val ABSOLUTE_MIN_ZOOM = 0.35f

        /** Pan and zoom track the fish layer, a little under the surface. */
        const val REFERENCE_DEPTH = 0.4f

        /** The smallest zoom at which the sand still fills the screen. */
        fun minZoom(viewport: Viewport, tank: TankSize, projection: DepthProjection = DepthProjection.DEFAULT): Float {
            val s = projection.scaleAt(tank.depthMm)
            val fit = max(
                viewport.widthPx / (viewport.basePxPerMm * s * tank.widthMm),
                viewport.heightPx / (viewport.basePxPerMm * s * tank.heightMm),
            )
            return min(MAX_ZOOM, max(ABSOLUTE_MIN_ZOOM, fit))
        }

        /** Life size, centred on the tank. */
        fun initial(viewport: Viewport, tank: TankSize): Camera =
            Camera(center = tank.center, zoom = 1f).clamped(viewport, tank)
    }
}
