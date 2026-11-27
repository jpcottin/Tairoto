package com.jpcottin.tairoto.world

import kotlin.math.roundToInt
import kotlin.random.Random

/** Photos of the printed page, made up for tests: no camera needed. */
object PagePhotos {
    val pageCorners = listOf(
        Vec2(0f, 0f), Vec2(TemplatePage.WIDTH_MM, 0f), Vec2(TemplatePage.WIDTH_MM, TemplatePage.HEIGHT_MM), Vec2(0f, TemplatePage.HEIGHT_MM),
    )

    /** A page lying square in a photo [w] by [h], with a margin of table round it. */
    fun square(w: Int, h: Int): List<Vec2> {
        val mx = w * 0.05f
        val my = h * 0.06f
        return listOf(Vec2(mx, my), Vec2(w - mx, my * 0.8f), Vec2(w - mx * 0.9f, h - my), Vec2(mx * 1.1f, h - my * 0.9f))
    }

    /**
     * A photo of the page coloured in by [paint] (u, v to RGB in 0..1, or null
     * where it is left white), its corners where [where] says in the photo,
     * lit unevenly, on a wooden table. [cover] hides one corner square.
     */
    fun photo(
        w: Int,
        h: Int,
        where: List<Vec2> = square(w, h),
        light: Float = 0.92f,
        cover: Int? = null,
        seed: Int = 1,
        paint: (Float, Float) -> FloatArray? = ::stripes,
    ): Picture {
        val toPage = Homography.fromPoints(where, pageCorners)!!
        val rnd = Random(seed)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = toPage.map(Vec2(x + 0.5f, y + 0.5f))
            val shade = light * (0.75f + 0.25f * x / w)
            var rgb: FloatArray
            if (p.x < 0 || p.y < 0 || p.x > TemplatePage.WIDTH_MM || p.y > TemplatePage.HEIGHT_MM) {
                rgb = floatArrayOf(0.32f, 0.27f, 0.22f)
            } else if (TemplatePage.inkAt(p) && (cover == null || (p - TemplatePage.markers[cover]).length > TemplatePage.MARKER_MM)) {
                rgb = floatArrayOf(0.08f, 0.08f, 0.09f)
            } else {
                rgb = floatArrayOf(0.96f, 0.94f, 0.9f)
                val u = (p.x - TemplatePage.FISH_LEFT_MM) / TemplatePage.FISH_WIDTH_MM
                val v = (p.y - TemplatePage.FISH_MIDDLE_MM) / TemplatePage.FISH_HALF_HEIGHT_MM
                if (FishTemplate.inside(u, v)) {
                    paint(u, v)?.let { c ->
                        val grain = 0.08f * (rnd.nextFloat() - 0.5f)
                        rgb = FloatArray(3) { c[it] + grain }
                    }
                }
            }
            fun c(f: Float) = ((f * shade).coerceIn(0f, 1f) * 255f).roundToInt()
            px[y * w + x] = (0xFF shl 24) or (c(rgb[0]) shl 16) or (c(rgb[1]) shl 8) or c(rgb[2])
        }
        return Picture(w, h, px)
    }

    /** Red above the middle line, blue below. */
    fun stripes(u: Float, v: Float): FloatArray = if (v < 0) floatArrayOf(0.85f, 0.2f, 0.15f) else floatArrayOf(0.15f, 0.3f, 0.8f)

    /** How a child might colour it: an orange body with purple bands, a yellow tail and fins, a green eye. */
    fun colourful(u: Float, v: Float): FloatArray {
        if ((Vec2(u, v) - FishTemplate.eye).length < 0.045f) return floatArrayOf(0.1f, 0.6f, 0.2f)
        if (u > 0.72f || kotlin.math.abs(v) > 0.68f) return floatArrayOf(0.98f, 0.82f, 0.12f)
        val band = ((u * 9f).toInt() % 2 == 1) && u > 0.2f
        return if (band) floatArrayOf(0.5f, 0.2f, 0.7f) else floatArrayOf(0.98f, 0.5f, 0.1f)
    }
}
