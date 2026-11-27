package com.jpcottin.tairoto.ui.tank

import com.jpcottin.tairoto.world.Vec2

/**
 * A cursor on the water for a remote, a keyboard or a game controller: the
 * arrows move it, and at the edge of the screen they pan the lagoon instead.
 * Steps grow while a key is held, so crossing a TV takes a moment, not a
 * minute. Pure state: the screen draws it and turns its moves into taps.
 */
class RemoteCursor {
    var at: Vec2? = null
        private set

    /** Where it starts: the middle of a screen [w] by [h]. */
    fun show(w: Float, h: Float): Vec2 = at ?: Vec2(w / 2f, h / 2f).also { at = it }

    fun hide() {
        at = null
    }

    /**
     * One press (or repeat, [repeats] so far) of an arrow ([dx], [dy] each -1,
     * 0 or 1) on a screen [w] by [h]. Returns how far to pan the lagoon, in
     * pixels, when the cursor is already against that edge (zero otherwise).
     */
    fun move(dx: Int, dy: Int, repeats: Int, w: Float, h: Float): Vec2 {
        val start = show(w, h)
        val step = minOf(w, h) * (STEP + ACCEL * minOf(repeats, MAX_REPEATS))
        val margin = minOf(w, h) * MARGIN
        val want = Vec2(start.x + dx * step, start.y + dy * step)
        val next = Vec2(want.x.coerceIn(margin, w - margin), want.y.coerceIn(margin, h - margin))
        at = next
        // What could not be moved, the lagoon pans by (the water slides the other way under the cursor).
        return Vec2(next.x - want.x, next.y - want.y)
    }

    /** Against the top edge: an up from here leaves the water for the buttons above. */
    fun atTop(h: Float, w: Float): Boolean = (at?.y ?: Float.MAX_VALUE) <= minOf(w, h) * MARGIN + 0.5f

    companion object {
        /** A first step: 4% of the short side. */
        const val STEP = 0.04f
        const val ACCEL = 0.01f
        const val MAX_REPEATS = 6
        /** The cursor keeps this far from the edges (of the short side). */
        const val MARGIN = 0.06f
    }
}
