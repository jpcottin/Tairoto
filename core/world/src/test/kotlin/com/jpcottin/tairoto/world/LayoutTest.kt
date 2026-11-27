package com.jpcottin.tairoto.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private val a = DeviceId(0, "phone")
    private val b = DeviceId(1, "fold")
    private val c = DeviceId(2, "phone 2")
    private val base = TankSize(100f, 100f, 55f)
    private val layout = Layout.sideBySide(
        listOf(a to ScreenMm(68f, 150f), b to ScreenMm(118f, 150f), c to ScreenMm(68f, 140f)), base,
    )

    @Test fun `screens lie side by side with both frames between them, tops aligned`() {
        val (ra, rb, rc) = layout.placements.map { it.rect }
        assertEquals(2 * ScreenMm.PHONE_BEZEL_MM, rb.x - ra.right, 1e-3f)
        assertEquals(2 * ScreenMm.PHONE_BEZEL_MM, rc.x - rb.right, 1e-3f)
        assertEquals(ra.y, rb.y, 1e-3f)
        assertEquals(ra.y, rc.y, 1e-3f)
        assertEquals(118f, rb.w, 1e-3f)
    }

    @Test fun `emulator windows join with no gap`() {
        val l = Layout.sideBySide(listOf(a to ScreenMm(65f, 146f, bezelMm = 0f), b to ScreenMm(70f, 158f, bezelMm = 0f)), base)
        val (ra, rb) = l.placements.map { it.rect }
        assertEquals(ra.right, rb.x, 1e-3f)
    }

    @Test fun `the gap is each device's own frame`() {
        val l = Layout.sideBySide(
            listOf(a to ScreenMm(68f, 150f, bezelMm = 2f), b to ScreenMm(250f, 160f, bezelMm = 9f), c to ScreenMm(68f, 150f, bezelMm = 0f)),
            base,
        )
        val (ra, rb, rc) = l.placements.map { it.rect }
        assertEquals(11f, rb.x - ra.right, 1e-3f)
        assertEquals(9f, rc.x - rb.right, 1e-3f)
        // Unless the caller sets one.
        val fixed = Layout.sideBySide(listOf(a to ScreenMm(68f, 150f), b to ScreenMm(68f, 150f)), base, gapMm = 1.5f)
        assertEquals(1.5f, fixed.placements[1].rect.x - fixed.placements[0].rect.right, 1e-3f)
    }

    @Test fun `the frame travels with the screen`() {
        val s = ScreenMm.fromPixels(1080, 2424, 420f, 420f, bezelMm = 0f)
        assertEquals(0f, s.bezelMm, 0f)
        assertEquals(ScreenMm.PHONE_BEZEL_MM, ScreenMm.fromPixels(1080, 2424, 420f, 420f).bezelMm, 0f)
        val wire = Wire.decode(Wire.encode(Msg.Hello(DeviceId(1, "emu"), s))) as Msg.Hello
        assertEquals(s, wire.screen)
    }

    @Test fun `the tank reaches past the outer screens as far as they see the sand`() {
        val s = DepthProjection.DEFAULT.scaleAt(base.depthMm)
        val left = layout.placements.first().rect
        val right = layout.placements.last().rect
        // The corner of a screen shows the sand at origin + (corner - origin) / s: inside the tank.
        for (corner in listOf(Vec2(left.x, left.y), Vec2(right.right, right.bottom))) {
            val sand = layout.origin + (corner - layout.origin) / s
            assertTrue("$sand outside ${layout.tank}", layout.tank.contains(sand))
        }
        assertEquals(layout.tank.center, layout.origin)
    }

    @Test fun `the tank never shrinks below what the host had`() {
        val big = TankSize(600f, 500f, 55f)
        val l = Layout.sideBySide(listOf(a to ScreenMm(68f, 150f)), big)
        assertEquals(big, l.tank)
        assertEquals(big.center, l.placements.single().rect.center)
    }

    @Test fun `which screen shows a creature depends on its depth`() {
        val rb = layout.rectOf(b)!!
        val rc = layout.rectOf(c)!!
        // Just right of the fold's screen, at the surface: in the gap.
        val p = Vec2(rb.right + 3f, rb.center.y)
        assertNull(layout.deviceShowing(p, 0f))
        // On the sand the same point looks nearer the middle: on the fold.
        assertEquals(b, layout.deviceShowing(p, 55f))
        assertEquals(c, layout.deviceShowing(rc.center, 0f))
    }

    @Test fun `an edge is not a coin toss`() {
        val rb = layout.rectOf(b)!!
        val justIn = Vec2(rb.x + 1f, rb.center.y)
        assertEquals(b, layout.deviceShowing(justIn, 0f))
        assertNull(layout.deviceShowing(justIn, 0f, insetMm = 3f))
    }

    @Test fun `a screen hears about what is near it`() {
        val ra = layout.rectOf(a)!!
        val nearEdge = Vec2(ra.right + 20f, ra.center.y)
        assertTrue(layout.near(a, nearEdge, 0f, 45f))
        assertTrue(!layout.near(c, nearEdge, 0f, 45f))
        assertTrue(!layout.near(DeviceId(9, "gone"), nearEdge, 0f, 45f))
    }

    @Test fun `a device leaving takes its screen out but not the water`() {
        val l = layout.without(b)
        assertEquals(2, l.placements.size)
        assertEquals(layout.tank, l.tank)
        assertNull(l.rectOf(b))
    }
}
