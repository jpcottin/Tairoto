package com.jpcottin.tairoto.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientViewTest {
    private val vp = Viewport(1080f, 2400f, 16.5f)
    private val tank = TankSize(330f, 330f, 55f)

    @Test fun `the first home screen shows the left of the lagoon, the last its right, all life size`() {
        val first = AmbientView.forOffset(0f, 0.5f, vp, tank)
        val last = AmbientView.forOffset(1f, 0.5f, vp, tank)
        assertTrue(first.center.x < last.center.x)
        assertEquals(1f, first.zoom, 1e-4f)
        // Each one's view reaches the tank's edge and no further.
        val half = first.floorHalfExtent(vp, first.zoom, tank)
        assertEquals(half.x, first.center.x, 1e-3f)
        assertEquals(tank.widthMm - half.x, last.center.x, 1e-3f)
    }

    @Test fun `a lagoon no wider than the screen stays in the middle whatever the page`() {
        val narrow = TankSize(60f, 330f, 55f)
        val a = AmbientView.forOffset(0f, 0f, vp, narrow)
        val b = AmbientView.forOffset(1f, 0f, vp, narrow)
        assertEquals(a.center.x, b.center.x, 1e-3f)
    }

    @Test fun `the drift roams the whole lagoon, slowly, and never leaves it`() {
        val xs = ArrayList<Float>()
        var t = 0.0
        while (t < AmbientView.DRIFT_ACROSS_S) {
            val c = AmbientView.drifting(t, vp, tank)
            val half = c.floorHalfExtent(vp, c.zoom, tank)
            assertTrue(c.center.x >= half.x - 1e-3f && c.center.x <= tank.widthMm - half.x + 1e-3f)
            xs += c.center.x
            t += 1.0
        }
        assertTrue("it goes from side to side", xs.max() - xs.min() > 0.9f * (tank.widthMm - 2 * AmbientView.forOffset(0f, 0f, vp, tank).center.x))
        // Slow: never more than a few mm a second.
        assertTrue(xs.zipWithNext { a, b -> kotlin.math.abs(b - a) }.max() < 5f)
    }
}
