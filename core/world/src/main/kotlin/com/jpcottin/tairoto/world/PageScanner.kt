package com.jpcottin.tairoto.world

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A photo as packed ARGB, row by row (Android's Bitmap.getPixels). */
class Picture(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height)
    }
}

/** A plane-to-plane projection (a page seen at an angle): 3x3, h[8] = 1. */
class Homography(private val h: DoubleArray) {
    fun map(p: Vec2): Vec2 {
        val x = p.x.toDouble()
        val y = p.y.toDouble()
        val w = h[6] * x + h[7] * y + 1.0
        return Vec2(((h[0] * x + h[1] * y + h[2]) / w).toFloat(), ((h[3] * x + h[4] * y + h[5]) / w).toFloat())
    }

    companion object {
        /** The projection taking each of four points [from] to [to]; null when they are degenerate (three in a line). */
        fun fromPoints(from: List<Vec2>, to: List<Vec2>): Homography? {
            require(from.size == 4 && to.size == 4)
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = from[i].x.toDouble()
                val y = from[i].y.toDouble()
                val u = to[i].x.toDouble()
                val v = to[i].y.toDouble()
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            // Gaussian elimination with partial pivoting.
            for (c in 0 until 8) {
                var piv = c
                for (r in c + 1 until 8) if (abs(a[r][c]) > abs(a[piv][c])) piv = r
                if (abs(a[piv][c]) < 1e-9) return null
                val t = a[c]; a[c] = a[piv]; a[piv] = t
                for (r in 0 until 8) {
                    if (r == c) continue
                    val f = a[r][c] / a[c][c]
                    if (f == 0.0) continue
                    for (k in c until 9) a[r][k] -= f * a[c][k]
                }
            }
            return Homography(DoubleArray(8) { a[it][8] / a[it][it] })
        }
    }
}

/** What came of scanning a photo of the page. */
sealed interface ScanResult {
    /** The drawing, and where the four corner squares were found in the photo (top left first, clockwise). */
    data class Found(val skin: Skin, val corners: List<Vec2>) : ScanResult

    /** No page: the four corner squares could not all be found. */
    data object NoPage : ScanResult
}

/**
 * Finds the printed page in a photo and cuts the coloured-in fish out of it.
 *
 * The four black squares are found as dark blobs, darker than their
 * surroundings (so shade across the page does not matter), square enough and
 * of a size; of those, the four that frame the biggest four-sided shape are
 * the page's corners, and the hollow one is its top left, whichever way the
 * phone was held. The page's frame then gives every point of the fish, read
 * with the paper's own white as white.
 */
object PageScanner {
    /** The photo is searched for squares at about this size: plenty for squares 5% of the page wide. */
    private const val SEARCH_SIDE = 900

    fun scan(picture: Picture): ScanResult {
        val corners = findCorners(picture) ?: return ScanResult.NoPage
        val h = Homography.fromPoints(TemplatePage.markers, corners) ?: return ScanResult.NoPage
        return ScanResult.Found(cutOut(picture, h), corners)
    }

    // ------------------------------------------------------------------ the corners

    private class Blob(
        var area: Int = 0,
        var minX: Int = Int.MAX_VALUE, var maxX: Int = -1,
        var minY: Int = Int.MAX_VALUE, var maxY: Int = -1,
        var sumX: Long = 0, var sumY: Long = 0,
        var border: Boolean = false,
    ) {
        val cx get() = sumX.toFloat() / area
        val cy get() = sumY.toFloat() / area
        val w get() = maxX - minX + 1
        val h get() = maxY - minY + 1
    }

    /** The marker centres in the photo's pixels, top left first and clockwise; null when there is no page. */
    fun findCorners(picture: Picture): List<Vec2>? {
        val scale = max(1, (max(picture.width, picture.height) + SEARCH_SIDE - 1) / SEARCH_SIDE)
        val w = picture.width / scale
        val h = picture.height / scale
        if (w < 40 || h < 40) return null
        val luma = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (dy in 0 until scale) for (dx in 0 until scale) s += lumaOf(picture.argb[(y * scale + dy) * picture.width + x * scale + dx])
            luma[y * w + x] = s / (scale * scale)
        }
        // Dark: well under the mean of the neighbourhood (a window a few squares wide).
        val integral = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += luma[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        val r = max(4, max(w, h) / 14)
        val dark = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
            val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            val sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
            val mean = sum / ((x1 - x0) * (y1 - y0))
            val l = luma[y * w + x]
            dark[y * w + x] = l < mean * 0.62 && l < mean - 0.08
        }
        val blobs = blobs(dark, w, h)
        val minSide = min(w, h)
        val candidates = blobs.filter { b ->
            !b.border && b.area >= max(12, (minSide * 0.012f).let { (it * it).toInt() }) &&
                b.w <= minSide * 0.2f && b.h <= minSide * 0.2f &&
                b.w.toFloat() / b.h in 0.4f..2.5f &&
                b.area.toFloat() / (b.w * b.h) >= 0.3f
        }.sortedByDescending { it.area }.take(12)
        if (candidates.size < 4) return null

        var best: List<Blob>? = null
        var bestArea = 0f
        val n = candidates.size
        for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) for (d in c + 1 until n) {
            val four = listOf(candidates[a], candidates[b], candidates[c], candidates[d])
            val areas = four.map { it.area }
            if (areas.max() > areas.min() * 5) continue
            val ordered = clockwise(four)
            val pts = ordered.map { Vec2(it.cx, it.cy) }
            if (!convex(pts)) continue
            val area = quadArea(pts)
            // A page, not four specks: its sides several squares long, its shape not too far from the page's.
            val side = sqrt(four.map { it.area }.average().toFloat())
            val sides = (0 until 4).map { (pts[(it + 1) % 4] - pts[it]).length }
            if (sides.min() < side * 4f) continue
            val longShort = max(sides[0] + sides[2], sides[1] + sides[3]) / min(sides[0] + sides[2], sides[1] + sides[3])
            if (longShort > 2.6f) continue
            if (area > bestArea) {
                bestArea = area
                best = ordered
            }
        }
        val four = best ?: return null
        // The hollow one is the top left: its middle is not dark.
        val hollow = four.indexOfFirst { b ->
            val x = b.cx.roundToInt().coerceIn(0, w - 1)
            val y = b.cy.roundToInt().coerceIn(0, h - 1)
            !dark[y * w + x]
        }
        val start = if (hollow >= 0) hollow else {
            // No hollow square seen (blurred): the page's long side runs from the top left.
            val pts = four.map { Vec2(it.cx, it.cy) }
            val firstLong = (pts[1] - pts[0]).length >= (pts[2] - pts[1]).length
            val tl = pts.indices.minBy { pts[it].x + pts[it].y }
            if (firstLong) tl else (tl + 3) % 4
        }
        val s = scale.toFloat()
        // Back in the photo's pixels (a pixel i spans i .. i + 1).
        return List(4) { four[(start + it) % 4] }.map { Vec2((it.cx + 0.5f) * s, (it.cy + 0.5f) * s) }
    }

    private fun blobs(dark: BooleanArray, w: Int, h: Int): List<Blob> {
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<Blob>()
        for (start in 0 until w * h) {
            if (!dark[start] || seen[start]) continue
            val b = Blob()
            var top = 0
            stack[top++] = start
            seen[start] = true
            while (top > 0) {
                val i = stack[--top]
                val x = i % w
                val y = i / w
                b.area++
                b.sumX += x; b.sumY += y
                if (x < b.minX) b.minX = x
                if (x > b.maxX) b.maxX = x
                if (y < b.minY) b.minY = y
                if (y > b.maxY) b.maxY = y
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) b.border = true
                if (x > 0 && dark[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[top++] = i - 1 }
                if (x < w - 1 && dark[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[top++] = i + 1 }
                if (y > 0 && dark[i - w] && !seen[i - w]) { seen[i - w] = true; stack[top++] = i - w }
                if (y < h - 1 && dark[i + w] && !seen[i + w]) { seen[i + w] = true; stack[top++] = i + w }
            }
            out += b
        }
        return out
    }

    /** Clockwise on the screen (y down), starting anywhere. */
    private fun clockwise(blobs: List<Blob>): List<Blob> {
        val mx = blobs.map { it.cx }.average().toFloat()
        val my = blobs.map { it.cy }.average().toFloat()
        return blobs.sortedBy { atan2(it.cy - my, it.cx - mx) }
    }

    private fun convex(p: List<Vec2>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun quadArea(p: List<Vec2>): Float {
        var s = 0f
        for (i in 0 until 4) s += p[i].x * p[(i + 1) % 4].y - p[(i + 1) % 4].x * p[i].y
        return abs(s) / 2f
    }

    // ------------------------------------------------------------------ the fish

    /** The fish's skin read through [h] (page mm to photo pixels), the paper's white made white. */
    fun cutOut(picture: Picture, h: Homography): Skin {
        val W = Skin.WIDTH
        val H = Skin.HEIGHT
        val du = 1f / W
        val dv = 2f / H
        // The paper's white: what is around the fish inside its box, the brightest of it.
        val paper = ArrayList<FloatArray>()
        for (j in 0 until 24) for (i in 0 until 48) {
            val u = (i + 0.5f) / 48
            val v = -1f + 2f * (j + 0.5f) / 24
            if (FishTemplate.coverage(u, v, 0.06f, 0.12f, 3) > 0f) continue
            paper += sample(picture, h.map(TemplatePage.fishToPage(u, v)))
        }
        val white = FloatArray(3) { c ->
            if (paper.size < 8) 1f else paper.map { it[c] }.sorted()[(paper.size * 0.8f).toInt().coerceAtMost(paper.size - 1)]
        }.map { it.coerceAtLeast(0.2f) }
        // The ink's black: inside the solid squares.
        val black = FloatArray(3)
        for (m in TemplatePage.markers.drop(1)) {
            val s = sample(picture, h.map(m + Vec2(TemplatePage.MARKER_MM * 0.3f, TemplatePage.MARKER_MM * 0.3f)))
            for (c in 0 until 3) black[c] += s[c] / 3f
        }
        val px = ByteArray(Skin.BYTES)
        for (y in 0 until H) for (x in 0 until W) {
            val u = (x + 0.5f) * du
            val v = -1f + (y + 0.5f) * dv
            val a = FishTemplate.coverage(u, v, du, dv)
            if (a <= 0f) continue
            // Two by two samples a texel, then the paper's white and the ink's black as 1 and 0.
            val rgb = FloatArray(3)
            for (sy in 0..1) for (sx in 0..1) {
                val s = sample(picture, h.map(TemplatePage.fishToPage(u + du * (sx - 0.5f) * 0.5f, v + dv * (sy - 0.5f) * 0.5f)))
                for (c in 0 until 3) rgb[c] += s[c] / 4f
            }
            for (c in 0 until 3) rgb[c] = ((rgb[c] - black[c]) / max(0.1f, white[c] - black[c])).coerceIn(0f, 1f)
            // Crayon photographs pale: a little more colour.
            val l = 0.3f * rgb[0] + 0.59f * rgb[1] + 0.11f * rgb[2]
            val i = (y * W + x) * 4
            for (c in 0 until 3) px[i + c] = ((l + (rgb[c] - l) * 1.35f).coerceIn(0f, 1f) * 255f).roundToInt().toByte()
            px[i + 3] = (a * 255f).roundToInt().toByte()
        }
        return Skin.of(px)
    }

    /** Bilinear, in 0..1 per channel (sRGB as stored). */
    private fun sample(p: Picture, at: Vec2): FloatArray {
        val x = (at.x - 0.5f).coerceIn(0f, p.width - 1.001f)
        val y = (at.y - 0.5f).coerceIn(0f, p.height - 1.001f)
        val x0 = x.toInt(); val y0 = y.toInt()
        val fx = x - x0; val fy = y - y0
        val out = FloatArray(3)
        fun add(xx: Int, yy: Int, wgt: Float) {
            val c = p.argb[yy * p.width + xx]
            out[0] += ((c shr 16) and 0xFF) / 255f * wgt
            out[1] += ((c shr 8) and 0xFF) / 255f * wgt
            out[2] += (c and 0xFF) / 255f * wgt
        }
        val x1 = min(x0 + 1, p.width - 1)
        val y1 = min(y0 + 1, p.height - 1)
        add(x0, y0, (1 - fx) * (1 - fy)); add(x1, y0, fx * (1 - fy))
        add(x0, y1, (1 - fx) * fy); add(x1, y1, fx * fy)
        return out
    }

    private fun lumaOf(c: Int): Float =
        (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
}
