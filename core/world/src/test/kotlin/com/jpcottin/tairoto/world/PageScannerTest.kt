package com.jpcottin.tairoto.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class PageScannerTest {
    private val pageCorners = PagePhotos.pageCorners

    private fun photo(w: Int, h: Int, where: List<Vec2>, light: Float = 0.92f, cover: Int? = null, seed: Int = 1): Picture =
        PagePhotos.photo(w, h, where, light, cover, seed)

    private fun Skin.rgbAt(u: Float, v: Float): IntArray {
        val p = pixels()!!
        val x = (u * Skin.WIDTH).toInt().coerceIn(0, Skin.WIDTH - 1)
        val y = ((v + 1f) / 2f * Skin.HEIGHT).toInt().coerceIn(0, Skin.HEIGHT - 1)
        val i = (y * Skin.WIDTH + x) * 4
        return IntArray(4) { p[i + it].toInt() and 0xFF }
    }

    private fun assertColoured(skin: Skin) {
        val top = skin.rgbAt(0.4f, -0.35f)
        val bottom = skin.rgbAt(0.4f, 0.35f)
        assertEquals(255, top[3])
        assertTrue("red back ${top.toList()}", top[0] > 170 && top[2] < 90)
        assertTrue("blue belly ${bottom.toList()}", bottom[2] > 170 && bottom[0] < 90)
        assertEquals("clear outside the outline", 0, skin.rgbAt(0.05f, -0.9f)[3])
        assertEquals("clear in the tail's fork", 0, skin.rgbAt(0.98f, 0f)[3])
    }

    private fun assertCorners(expected: List<Vec2>, found: List<Vec2>, tolerance: Float) {
        val toPhoto = Homography.fromPoints(pageCorners, expected)!!
        for (i in 0 until 4) {
            val want = toPhoto.map(TemplatePage.markers[i])
            assertTrue("corner $i at ${found[i]}, want $want", (found[i] - want).length < tolerance)
        }
    }

    @Test fun `a page photographed from above is found and its fish cut out in its colours`() {
        val where = listOf(Vec2(60f, 50f), Vec2(1140f, 40f), Vec2(1150f, 800f), Vec2(50f, 790f))
        val r = PageScanner.scan(photo(1200, 850, where))
        assertTrue(r is ScanResult.Found)
        r as ScanResult.Found
        assertCorners(where, r.corners, 4f)
        assertColoured(r.skin)
    }

    @Test fun `a page seen at an angle, turned, and dimly lit is read the right way up`() {
        // The phone held upright over a page lying sideways and tilted away: the top left of the page is at the bottom left.
        val where = listOf(Vec2(180f, 1500f), Vec2(260f, 120f), Vec2(980f, 260f), Vec2(1040f, 1390f))
        val r = PageScanner.scan(photo(1100, 1600, where, light = 0.6f, seed = 3))
        assertTrue(r is ScanResult.Found)
        r as ScanResult.Found
        assertCorners(where, r.corners, 6f)
        assertColoured(r.skin)
    }

    @Test fun `a page upside down still comes out the right way up`() {
        val where = listOf(Vec2(1140f, 800f), Vec2(60f, 790f), Vec2(50f, 50f), Vec2(1150f, 40f))
        val r = PageScanner.scan(photo(1200, 850, where))
        r as ScanResult.Found
        assertColoured(r.skin)
    }

    @Test fun `a big photo is searched small and read in full`() {
        val where = listOf(Vec2(300f, 200f), Vec2(2700f, 260f), Vec2(2650f, 1850f), Vec2(260f, 1800f))
        val r = PageScanner.scan(photo(3000, 2000, where))
        r as ScanResult.Found
        assertCorners(where, r.corners, 8f)
        assertColoured(r.skin)
    }

    @Test fun `no page, or a corner square out of sight, is no page`() {
        val blank = Picture(400, 300, IntArray(400 * 300) { 0xFF808080.toInt() })
        assertEquals(ScanResult.NoPage, PageScanner.scan(blank))
        val where = listOf(Vec2(60f, 50f), Vec2(1140f, 40f), Vec2(1150f, 800f), Vec2(50f, 790f))
        assertEquals(ScanResult.NoPage, PageScanner.scan(photo(1200, 850, where, cover = 2)))
    }

    @Test fun `the paper's white comes out white, whatever the light`() {
        val where = listOf(Vec2(60f, 50f), Vec2(1140f, 40f), Vec2(1150f, 800f), Vec2(50f, 790f))
        val toPhoto = Homography.fromPoints(pageCorners, where)!!
        // An uncoloured page: the fish comes out paper white.
        val pic = photo(1200, 850, where, light = 0.55f).let { p ->
            val toPage = Homography.fromPoints(where, pageCorners)!!
            Picture(p.width, p.height, IntArray(p.argb.size) { i ->
                val q = toPage.map(Vec2(i % p.width + 0.5f, i / p.width + 0.5f))
                val u = (q.x - TemplatePage.FISH_LEFT_MM) / TemplatePage.FISH_WIDTH_MM
                val v = (q.y - TemplatePage.FISH_MIDDLE_MM) / TemplatePage.FISH_HALF_HEIGHT_MM
                if (FishTemplate.inside(u, v)) {
                    val s = 0.55f * (0.75f + 0.25f * (i % p.width) / p.width)
                    val c = (0.95f * s * 255).roundToInt()
                    (0xFF shl 24) or (c shl 16) or (c shl 8) or c
                } else p.argb[i]
            })
        }
        assertTrue(toPhoto.map(Vec2(0f, 0f)).x < 61f)
        val skin = (PageScanner.scan(pic) as ScanResult.Found).skin
        val mid = skin.rgbAt(0.4f, 0f)
        assertTrue("white ${mid.toList()}", mid.take(3).all { it > 200 } && abs(mid[0] - mid[2]) < 40)
    }

    @Test fun `a projection takes four points where they were asked to go`() {
        val from = listOf(Vec2(0f, 0f), Vec2(10f, 0f), Vec2(10f, 5f), Vec2(0f, 5f))
        val to = listOf(Vec2(3f, 4f), Vec2(40f, 2f), Vec2(38f, 30f), Vec2(1f, 25f))
        val h = Homography.fromPoints(from, to)!!
        for (i in 0 until 4) assertTrue((h.map(from[i]) - to[i]).length < 1e-3f)
        val line = listOf(Vec2(0f, 0f), Vec2(1f, 1f), Vec2(2f, 2f), Vec2(3f, 3f))
        assertEquals(null, Homography.fromPoints(line, to))
    }

    @Test fun `the outline is one closed fish with a forked tail`() {
        val pts = FishTemplate.outline()
        assertTrue(pts.size > 100)
        assertTrue(FishTemplate.inside(0.4f, 0f))
        assertTrue("dorsal fin", FishTemplate.inside(0.36f, -0.9f))
        assertTrue("no fin underneath there", !FishTemplate.inside(0.36f, 0.9f))
        assertTrue("tail lobes", FishTemplate.inside(0.97f, -0.8f) && FishTemplate.inside(0.97f, 0.8f))
        assertTrue("fork", !FishTemplate.inside(0.97f, 0f))
        assertEquals(1f, FishTemplate.coverage(0.4f, 0f, 0.01f, 0.01f), 0f)
        val edge = FishTemplate.coverage(0.4f, -FishTemplate.top(0.4f), 0.02f, 0.04f)
        assertTrue("an edge is partly covered: $edge", edge in 0.2f..0.8f)
    }
}
