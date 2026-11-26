package com.jpcottin.tairoto.world

import kotlinx.serialization.Serializable

/** A rectangle on the table, in mm. */
@Serializable
data class RectMm(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
    val center: Vec2 get() = Vec2(x + w / 2f, y + h / 2f)

    fun contains(p: Vec2): Boolean = p.x >= x && p.x <= right && p.y >= y && p.y <= bottom

    /** Grown by [d] on every side (shrunk when negative). */
    fun inflate(d: Float): RectMm = RectMm(x - d, y - d, w + 2 * d, h + 2 * d)

    fun offset(by: Vec2): RectMm = copy(x = x + by.x, y = y + by.y)
}

/** Where one device's screen lies on the table. */
@Serializable
data class Placement(val device: DeviceId, val screen: ScreenMm, val rect: RectMm)

/**
 * The shared lagoon on the table: every screen as a window onto one tank.
 *
 * Coordinates are the tank's, in mm, at the water surface. Something deeper
 * looks smaller and nearer [origin] (the one parallax point every device
 * shares), so which screen shows a creature depends on its depth: see
 * [visibleAt].
 */
@Serializable
data class Layout(
    val placements: List<Placement>,
    val tank: TankSize,
    val origin: Vec2 = tank.center,
) {
    fun placement(device: DeviceId): Placement? = placements.firstOrNull { it.device == device }
    fun rectOf(device: DeviceId): RectMm? = placement(device)?.rect

    /** Where a point at depth z appears on the plane of the glass. */
    fun visibleAt(p: Vec2, depthMm: Float, projection: DepthProjection = DepthProjection.DEFAULT): Vec2 {
        val s = projection.scaleAt(depthMm)
        return origin + (p - origin) * s
    }

    /** The device whose screen shows a point (shrunk by [insetMm] so an edge is not a coin toss), or null in a gap. */
    fun deviceShowing(p: Vec2, depthMm: Float, insetMm: Float = 0f): DeviceId? {
        val q = visibleAt(p, depthMm)
        return placements.firstOrNull { it.rect.inflate(-insetMm).contains(q) }?.device
    }

    /** True if [device]'s screen, widened by [marginMm], shows the point: it wants to hear about it often. */
    fun near(device: DeviceId, p: Vec2, depthMm: Float, marginMm: Float): Boolean {
        val r = rectOf(device) ?: return false
        return r.inflate(marginMm).contains(visibleAt(p, depthMm))
    }

    fun without(device: DeviceId): Layout = copy(placements = placements.filterNot { it.device == device })

    companion object {
        /** The gap left between two screens lying side by side, in mm: a bezel and a little water. */
        const val GAP_MM = 12f

        /**
         * Screens side by side in the order given, tops aligned, with the
         * tank around them. The tank never gets smaller than [base], and it
         * reaches past the outer screens by what they see of the sand at
         * depth (deeper looks smaller, so the edge of a screen shows sand
         * beyond the glass).
         */
        fun sideBySide(
            screens: List<Pair<DeviceId, ScreenMm>>,
            base: TankSize,
            gapMm: Float = GAP_MM,
            projection: DepthProjection = DepthProjection.DEFAULT,
        ): Layout {
            require(screens.isNotEmpty()) { "a layout needs a screen" }
            var x = 0f
            val rects = screens.map { (d, s) ->
                val r = RectMm(x, 0f, s.widthMm, s.heightMm)
                x += s.widthMm + gapMm
                Placement(d, s, r)
            }
            val bw = rects.last().rect.right
            val bh = rects.maxOf { it.rect.h }
            // What the outer screens see at sand depth reaches 1/s further out from the middle.
            val widen = 1f / projection.scaleAt(base.depthMm)
            val tank = base.grownTo(TankSize(bw * widen + 2f, bh * widen + 2f, base.depthMm))
            val shift = Vec2((tank.widthMm - bw) / 2f, (tank.heightMm - bh) / 2f)
            return Layout(rects.map { it.copy(rect = it.rect.offset(shift)) }, tank, tank.center)
        }
    }
}
