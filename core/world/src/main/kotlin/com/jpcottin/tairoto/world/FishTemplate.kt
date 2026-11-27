package com.jpcottin.tairoto.world

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The fish outline printed for colouring in, in its own coordinates: u from
 * the snout (0) to the tip of the tail (1), v across from the top of the page
 * (-1) to the bottom (1). The drawn fish's skin covers exactly that box, so
 * the same function cuts the drawing out of the photo and the fish out of its
 * skin in the shader's texture.
 *
 * A round body, a forked tail, a dorsal fin on the top edge and an anal fin
 * underneath: a fish a child knows how to colour.
 */
object FishTemplate {
    private fun body(u: Float): Float {
        val t = (u - 0.37f) / 0.37f
        return if (t * t < 1f) 0.66f * sqrt(1f - t * t) else 0f
    }

    private fun tail(u: Float): Float = if (u in 0.64f..1f) 0.1f + 0.85f * ((u - 0.64f) / 0.36f).pow(1.3f) else 0f

    private fun dorsal(u: Float): Float = when {
        u < 0.26f || u > 0.62f -> 0f
        u < 0.36f -> lerp(0.6f, 0.96f, (u - 0.26f) / 0.1f)
        else -> lerp(0.96f, 0.4f, (u - 0.36f) / 0.26f)
    }

    private fun anal(u: Float): Float = when {
        u < 0.44f || u > 0.64f -> 0f
        u < 0.5f -> lerp(0.55f, 0.84f, (u - 0.44f) / 0.06f)
        else -> lerp(0.84f, 0.3f, (u - 0.5f) / 0.14f)
    }

    /** How far the outline reaches above the middle line at u (towards v = -1). */
    fun top(u: Float): Float = max(max(body(u), tail(u)), dorsal(u))

    /** How far it reaches below (towards v = 1). */
    fun bottom(u: Float): Float = max(max(body(u), tail(u)), anal(u))

    /** The fork of the tail: the notch cut into its end. */
    private fun inFork(u: Float, v: Float): Boolean = u > FORK_U && kotlin.math.abs(v) < 0.5f * (u - FORK_U) / (1f - FORK_U)

    private const val FORK_U = 0.86f

    /** 1 inside the outline, 0 outside. */
    fun inside(u: Float, v: Float): Boolean {
        if (u < 0f || u > 1f) return false
        if (v < -top(u) || v > bottom(u)) return false
        return !inFork(u, v)
    }

    /** The share of a small square around (u, v), du by dv, inside the outline: an antialiased edge. */
    fun coverage(u: Float, v: Float, du: Float = 0f, dv: Float = 0f, samples: Int = 4): Float {
        if (du == 0f && dv == 0f) return if (inside(u, v)) 1f else 0f
        var n = 0
        for (j in 0 until samples) for (i in 0 until samples) {
            val su = u + du * ((i + 0.5f) / samples - 0.5f)
            val sv = v + dv * ((j + 0.5f) / samples - 0.5f)
            if (inside(su, sv)) n++
        }
        return n.toFloat() / (samples * samples)
    }

    /** The outline as one closed polygon in (u, v), for printing: along the top, round the tail, back underneath. */
    fun outline(steps: Int = 160): List<Vec2> {
        val pts = ArrayList<Vec2>()
        for (i in 0..steps) {
            val u = i.toFloat() / steps
            pts += Vec2(u, -top(u))
        }
        pts += Vec2(FORK_U, 0f)
        for (i in steps downTo 0) {
            val u = i.toFloat() / steps
            pts += Vec2(u, bottom(u))
        }
        return pts
    }

    /** Where an eye is drawn on the page, as a hint (it is not part of the cut-out). */
    val eye = Vec2(0.11f, -0.12f)
    const val EYE_RADIUS = 0.035f

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
}

/**
 * The page that is printed, in mm on A4 turned sideways: a square in each
 * corner for the camera to find (the top-left one hollow, so a page
 * photographed upside down still reads the right way up), and the fish
 * outline between them. Printed on Letter or scaled to fit, only the
 * proportions matter: the squares give the page's frame.
 */
object TemplatePage {
    const val WIDTH_MM = 297f
    const val HEIGHT_MM = 210f
    const val MARKER_MM = 16f
    const val HOLE_MM = 8f

    /** Marker centres: top left (hollow), top right, bottom right, bottom left. */
    val markers: List<Vec2> = listOf(Vec2(20f, 20f), Vec2(277f, 20f), Vec2(277f, 190f), Vec2(20f, 190f))

    /** The fish's box on the page: u along x, v along y. */
    const val FISH_LEFT_MM = 30f
    const val FISH_WIDTH_MM = 237f
    const val FISH_MIDDLE_MM = 108f
    const val FISH_HALF_HEIGHT_MM = FISH_WIDTH_MM * Skin.HEIGHT / Skin.WIDTH / 2f

    fun fishToPage(u: Float, v: Float) = Vec2(FISH_LEFT_MM + u * FISH_WIDTH_MM, FISH_MIDDLE_MM + v * FISH_HALF_HEIGHT_MM)

    /** True where the page is printed black (a marker), for drawing it and for tests. */
    fun inkAt(p: Vec2): Boolean {
        for ((i, m) in markers.withIndex()) {
            val dx = kotlin.math.abs(p.x - m.x)
            val dy = kotlin.math.abs(p.y - m.y)
            if (dx <= MARKER_MM / 2 && dy <= MARKER_MM / 2) return !(i == 0 && dx < HOLE_MM / 2 && dy < HOLE_MM / 2)
        }
        return false
    }
}
