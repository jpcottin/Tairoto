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

/**
 * Where one device's screen lies on the table: [rect] is the picture as it
 * covers the table (its width and height swap when the device lies across),
 * [turns] how far the device is turned (see [Turns]).
 */
@Serializable
data class Placement(val device: DeviceId, val screen: ScreenMm, val rect: RectMm, val turns: Int = 0) {
    private val half: Vec2 get() = Vec2(screen.widthMm / 2f, screen.heightMm / 2f)

    /** A point on this glass, in mm from its top left corner as the device sees it, on the table. */
    fun toTable(local: Vec2): Vec2 = rect.center + Turns.toTable(local - half, turns)

    /** A point on the table, in mm from the top left corner of this glass as the device sees it. */
    fun toLocal(p: Vec2): Vec2 = Turns.toScreen(p - rect.center, turns) + half

    /** The whole device, frame and all: two of them can touch, never overlap. */
    val body: RectMm get() = rect.inflate(screen.bezelMm)

    /** The point of [edge] (as the device sees it) [alongMm] from its start (its left or top end). */
    fun edgePoint(edge: Edge, alongMm: Float): Vec2 {
        val x = alongMm.coerceIn(0f, screen.widthMm)
        val y = alongMm.coerceIn(0f, screen.heightMm)
        return when (edge) {
            Edge.TOP -> Vec2(x, 0f)
            Edge.RIGHT -> Vec2(screen.widthMm, y)
            Edge.BOTTOM -> Vec2(x, screen.heightMm)
            Edge.LEFT -> Vec2(0f, y)
        }
    }

    fun movedTo(center: Vec2, turns: Int = this.turns): Placement = centeredAt(device, screen, center, turns)

    companion object {
        /** A screen turned [turns], its picture centred on [center]. */
        fun centeredAt(device: DeviceId, screen: ScreenMm, center: Vec2, turns: Int = 0): Placement {
            val t = Turns.norm(turns)
            val w = if (t % 2 == 0) screen.widthMm else screen.heightMm
            val h = if (t % 2 == 0) screen.heightMm else screen.widthMm
            return Placement(device, screen, RectMm(center.x - w / 2f, center.y - h / 2f, w, h), t)
        }
    }
}

/**
 * The shared lagoon on the table: every screen as a window onto one tank.
 *
 * Coordinates are the tank's, in mm, at the water surface. Something deeper
 * looks smaller and nearer [origin] (the one parallax point every device
 * shares), so which screen shows a creature depends on its depth: see
 * [visibleAt]. While [arranging], every screen shows who it is, and a finger
 * swiped from one screen onto another lays the second beside the first.
 *
 * The screens are moved by [withScreen], [moved] and [stitched]; each keeps
 * the others where they are over the water when it can, so their fish do not
 * jump, and grows the tank when the screens reach past it.
 */
@Serializable
data class Layout(
    val placements: List<Placement>,
    val tank: TankSize,
    val origin: Vec2 = tank.center,
    val arranging: Boolean = false,
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

    /** The smallest rectangle around every screen. */
    fun bounds(): RectMm {
        require(placements.isNotEmpty()) { "an empty table has no bounds" }
        val x = placements.minOf { it.rect.x }
        val y = placements.minOf { it.rect.y }
        return RectMm(x, y, placements.maxOf { it.rect.right } - x, placements.maxOf { it.rect.bottom } - y)
    }

    /**
     * A device comes in, or its screen changed size (a foldable opened). One
     * already on the table keeps its corner and its turn; a new one lies to
     * the right of the others, tops aligned, the two frames between.
     */
    fun withScreen(device: DeviceId, screen: ScreenMm, base: TankSize, projection: DepthProjection = DepthProjection.DEFAULT): Layout {
        val cur = placement(device)
        val p = when {
            cur != null -> {
                val sized = Placement.centeredAt(device, screen, Vec2.Zero, cur.turns)
                sized.copy(rect = sized.rect.copy(x = cur.rect.x, y = cur.rect.y))
            }
            placements.isEmpty() -> Placement(device, screen, RectMm(0f, 0f, screen.widthMm, screen.heightMm))
            else -> {
                val b = bounds()
                val last = placements.maxBy { it.rect.right }
                Placement(device, screen, RectMm(b.right + last.screen.bezelMm + screen.bezelMm, b.y, screen.widthMm, screen.heightMm))
            }
        }
        return replaced(p).separated(device).refit(base, projection)
    }

    /**
     * Someone dragged [device]'s screen on the map to [center], turned
     * [turns]: it snaps to a neighbour's side or line when close, and is
     * pushed off any device it would lie on.
     */
    fun moved(device: DeviceId, center: Vec2, turns: Int, base: TankSize, projection: DepthProjection = DepthProjection.DEFAULT): Layout {
        val p = placement(device) ?: return this
        val snapped = snapped(p.movedTo(center, turns), placements.filter { it.device != device })
        return replaced(snapped).separated(device).refit(base, projection)
    }

    /**
     * A finger left [from]'s screen through its [exit] edge, [exitAlongMm]
     * along it, and came onto [to]'s through [entry], [entryAlongMm] along:
     * the two lie edge to edge, frames touching, the finger's path running
     * straight across. [to] moves (and turns) to make it so. Null if either
     * is not on the table.
     */
    fun stitched(
        from: DeviceId, exit: Edge, exitAlongMm: Float,
        to: DeviceId, entry: Edge, entryAlongMm: Float,
        base: TankSize, projection: DepthProjection = DepthProjection.DEFAULT,
    ): Layout? {
        if (from == to) return null
        val a = placement(from) ?: return null
        val b = placement(to) ?: return null
        val side = exit.turned(a.turns)
        // The entry edge, once turned, faces back at the exit.
        val turns = Turns.norm(side.opposite.ordinal - entry.ordinal)
        val across = a.toTable(a.edgePoint(exit, exitAlongMm)) + side.outward * (a.screen.bezelMm + b.screen.bezelMm)
        val local = b.edgePoint(entry, entryAlongMm)
        val center = across - Turns.toTable(local - Vec2(b.screen.widthMm / 2f, b.screen.heightMm / 2f), turns)
        val slideY = side == Edge.LEFT || side == Edge.RIGHT
        return replaced(b.movedTo(center, turns)).separated(to, slideY).refit(base, projection)
    }

    private fun replaced(p: Placement): Layout {
        val i = placements.indexOfFirst { it.device == p.device }
        return copy(placements = if (i < 0) placements + p else placements.toMutableList().also { it[i] = p })
    }

    /**
     * Pushes [device] off every device it lies on, the shortest way each
     * time; only up and down, or only sideways, if [slideY] says so (a screen
     * stitched to a side keeps touching it).
     */
    private fun separated(device: DeviceId, slideY: Boolean? = null): Layout {
        var p = placement(device) ?: return this
        val others = placements.filter { it.device != device }
        repeat(placements.size * 4) {
            val hit = others.firstOrNull { overlaps(p.body, it.body) } ?: return replaced(p)
            val a = p.body
            val b = hit.body
            val dx = if (a.center.x >= b.center.x) b.right - a.x else b.x - a.right
            val dy = if (a.center.y >= b.center.y) b.bottom - a.y else b.y - a.bottom
            val alongY = slideY ?: (kotlin.math.abs(dy) < kotlin.math.abs(dx))
            p = p.copy(rect = p.rect.offset(if (alongY) Vec2(0f, dy) else Vec2(dx, 0f)))
        }
        return replaced(p)
    }

    /**
     * The tank around the screens: as big as [base] at least, and reaching
     * past the outer screens by what they see of the sand at depth (deeper
     * looks smaller, so the edge of a screen shows sand beyond its glass).
     * The screens move over the water only if they would reach past it, and
     * then to the middle of it along that way.
     */
    fun refit(base: TankSize, projection: DepthProjection = DepthProjection.DEFAULT): Layout {
        if (placements.isEmpty()) return this
        val b = bounds()
        val widen = 1f / projection.scaleAt(base.depthMm)
        val needW = b.w * widen + 2f
        val needH = b.h * widen + 2f
        val size = base.grownTo(tank).grownTo(TankSize(needW, needH, base.depthMm))
        val need = RectMm(b.center.x - needW / 2f, b.center.y - needH / 2f, needW, needH)
        // On an axis where they reach past the water, the screens move to its middle, leaving room for the next.
        fun shift(lo: Float, hi: Float, side: Float) = if (lo < 0f || hi > side) (side - (hi - lo)) / 2f - lo else 0f
        val d = Vec2(shift(need.x, need.right, size.widthMm), shift(need.y, need.bottom, size.heightMm))
        return copy(placements = placements.map { it.copy(rect = it.rect.offset(d)) }, tank = size, origin = b.center + d)
    }

    companion object {
        /** A screen dragged this close to a neighbour's side, or in line with it, snaps to it. */
        const val SNAP_MM = 8f

        private fun overlaps(a: RectMm, b: RectMm, eps: Float = 0.01f) =
            a.x < b.right - eps && b.x < a.right - eps && a.y < b.bottom - eps && b.y < a.bottom - eps

        /** [p] moved by at most [SNAP_MM] on each axis to lie against a neighbour or in line with it. */
        internal fun snapped(p: Placement, others: List<Placement>, snapMm: Float = SNAP_MM): Placement {
            val r = p.rect
            var bestX: Float? = null
            var bestY: Float? = null
            fun consider(cur: Float?, d: Float) = if (kotlin.math.abs(d) <= snapMm && (cur == null || kotlin.math.abs(d) < kotlin.math.abs(cur))) d else cur
            for (o in others) {
                val gap = o.screen.bezelMm + p.screen.bezelMm
                val q = o.rect
                if (!overlaps(r.inflate(snapMm + gap), q)) continue
                val besideX = r.y < q.bottom + gap && q.y < r.bottom + gap
                val besideY = r.x < q.right + gap && q.x < r.right + gap
                if (besideX) {
                    // Side against side, frames touching.
                    bestX = consider(bestX, q.right + gap - r.x)
                    bestX = consider(bestX, q.x - gap - r.right)
                    // Tops, bottoms or middles in line.
                    bestY = consider(bestY, q.y - r.y)
                    bestY = consider(bestY, q.bottom - r.bottom)
                    bestY = consider(bestY, q.center.y - r.center.y)
                }
                if (besideY) {
                    bestY = consider(bestY, q.bottom + gap - r.y)
                    bestY = consider(bestY, q.y - gap - r.bottom)
                    bestX = consider(bestX, q.x - r.x)
                    bestX = consider(bestX, q.right - r.right)
                    bestX = consider(bestX, q.center.x - r.center.x)
                }
            }
            return p.copy(rect = r.offset(Vec2(bestX ?: 0f, bestY ?: 0f)))
        }

        /**
         * Screens side by side in the order given, tops aligned, edge to edge:
         * between two pictures lie both devices' frames (or [gapMm] if given). With the
         * tank around them. The tank never gets smaller than [base], and it
         * reaches past the outer screens by what they see of the sand at
         * depth (deeper looks smaller, so the edge of a screen shows sand
         * beyond the glass).
         */
        fun sideBySide(
            screens: List<Pair<DeviceId, ScreenMm>>,
            base: TankSize,
            gapMm: Float? = null,
            projection: DepthProjection = DepthProjection.DEFAULT,
        ): Layout {
            require(screens.isNotEmpty()) { "a layout needs a screen" }
            var x = 0f
            val rects = screens.mapIndexed { i, (d, s) ->
                if (i > 0) x += gapMm ?: (screens[i - 1].second.bezelMm + s.bezelMm)
                val r = RectMm(x, 0f, s.widthMm, s.heightMm)
                x += s.widthMm
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
