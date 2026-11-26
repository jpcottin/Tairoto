package com.jpcottin.drifttank.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    @Test fun `a phone screen is measured in millimetres from its pixels and dpi`() {
        val s = ScreenMm.fromPixels(1080, 2400, 420f, 420f)
        assertEquals(65.3f, s.widthMm, 0.1f)
        assertEquals(145.1f, s.heightMm, 0.1f)
    }

    @Test fun `a nonsense dpi falls back to a phone density`() {
        val s = ScreenMm.fromPixels(1080, 2400, 0f, 0f)
        assertEquals(ScreenMm.fromPixels(1080, 2400, 420f, 420f), s)
    }

    @Test fun `deeper is smaller and the surface is life size`() {
        val p = DepthProjection.DEFAULT
        assertEquals(1f, p.scaleAt(0f), 1e-6f)
        assertTrue(p.scaleAt(50f) < 1f)
        assertTrue(p.scaleAt(100f) < p.scaleAt(50f))
        assertEquals(1f, p.scaleAt(-5f), 1e-6f) // above the surface is clamped to it
    }

    @Test fun `a solo tank is bigger than its screen and rounded to ten millimetres`() {
        val screen = ScreenMm(65f, 145f)
        val tank = TankSize.forScreen(screen)
        assertTrue(tank.widthMm > screen.widthMm * 1.7f)
        assertTrue(tank.heightMm > screen.heightMm * 1.7f)
        assertEquals(0f, tank.widthMm % 10f, 1e-3f)
        assertEquals(0f, tank.heightMm % 10f, 1e-3f)
        assertEquals(TankSize.DEFAULT_DEPTH_MM, tank.depthMm, 0f)
    }

    @Test fun `tank sides stay within sensible limits`() {
        assertEquals(160f, TankSize.forScreen(ScreenMm(10f, 10f)).widthMm, 0f)
        assertEquals(1200f, TankSize.forScreen(ScreenMm(2000f, 2000f)).widthMm, 0f)
    }

    @Test fun `contains is inclusive of the walls`() {
        val t = TankSize(100f, 50f, 20f)
        assertTrue(t.contains(Vec2(0f, 0f)))
        assertTrue(t.contains(Vec2(100f, 50f)))
        assertFalse(t.contains(Vec2(100.1f, 10f)))
        assertEquals(Vec2(50f, 25f), t.center)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a flat tank is refused`() {
        TankSize(100f, 100f, 0f)
    }

    @Test fun `vector arithmetic`() {
        assertEquals(Vec2(4f, 6f), Vec2(1f, 2f) + Vec2(3f, 4f))
        assertEquals(Vec2(-2f, -2f), Vec2(1f, 2f) - Vec2(3f, 4f))
        assertEquals(5f, Vec2(3f, 4f).length, 1e-6f)
        assertEquals(Vec2(1.5f, 2f), Vec2(3f, 4f) / 2f)
    }
}
