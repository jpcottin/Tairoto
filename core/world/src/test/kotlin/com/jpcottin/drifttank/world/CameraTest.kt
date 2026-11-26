package com.jpcottin.drifttank.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTest {
    private val viewport = Viewport(1080f, 2400f, basePxPerMm = 420f / 25.4f)
    private val tank = TankSize.forScreen(ScreenMm.fromPixels(1080, 2400, 420f, 420f))

    private fun assertClose(expected: Vec2, actual: Vec2, eps: Float = 1e-2f) {
        assertEquals("x", expected.x, actual.x, eps)
        assertEquals("y", expected.y, actual.y, eps)
    }

    @Test fun `the camera centre is drawn at the middle of the screen at every depth`() {
        val cam = Camera.initial(viewport, tank)
        for (z in listOf(0f, 30f, 110f)) {
            assertClose(viewport.center, cam.worldToScreen(cam.center, z, viewport))
        }
    }

    @Test fun `screen to world undoes world to screen at any depth`() {
        val cam = Camera(Vec2(120f, 200f), zoom = 1.7f, parallaxOrigin = Vec2(90f, 210f))
        for (z in listOf(0f, 12f, 55f, 110f)) {
            val p = Vec2(101f, 187f)
            assertClose(p, cam.screenToWorld(cam.worldToScreen(p, z, viewport), z, viewport))
        }
    }

    @Test fun `at zoom one a millimetre at the surface is a millimetre on the glass`() {
        val cam = Camera.initial(viewport, tank)
        val a = cam.worldToScreen(cam.center, 0f, viewport)
        val b = cam.worldToScreen(cam.center + Vec2(10f, 0f), 0f, viewport)
        assertEquals(10f * viewport.basePxPerMm, b.x - a.x, 1e-2f)
    }

    @Test fun `the sand moves less than the fish when panning, which is parallax`() {
        val cam = Camera.initial(viewport, tank)
        val moved = cam.panned(Vec2(-200f, 0f), viewport, tank)
        val fish = Vec2(tank.center.x + 5f, tank.center.y)
        val dxSurface = cam.worldToScreen(fish, 0f, viewport).x - moved.worldToScreen(fish, 0f, viewport).x
        val dxSand = cam.worldToScreen(fish, tank.depthMm, viewport).x - moved.worldToScreen(fish, tank.depthMm, viewport).x
        assertTrue(dxSurface > dxSand)
        assertTrue(dxSand > 0f)
    }

    @Test fun `a shared parallax origin keeps a point still across devices`() {
        val origin = Vec2(150f, 150f)
        val left = Camera(Vec2(100f, 150f), parallaxOrigin = origin)
        val right = Camera(Vec2(200f, 150f), parallaxOrigin = origin)
        // A point on the sand under the origin projects to the origin's place on both.
        val zSand = 110f
        val l = left.screenToWorld(left.worldToScreen(origin, zSand, viewport), 0f, viewport)
        val r = right.screenToWorld(right.worldToScreen(origin, zSand, viewport), 0f, viewport)
        assertClose(origin, l)
        assertClose(origin, r)
    }

    @Test fun `panning follows the finger at the reference depth`() {
        val cam = Camera.initial(viewport, tank).copy(center = tank.center)
        val z = tank.depthMm * Camera.REFERENCE_DEPTH
        val p = cam.screenToWorld(Vec2(500f, 1000f), z, viewport)
        val moved = cam.panned(Vec2(30f, -40f), viewport, tank)
        assertClose(Vec2(530f, 960f), moved.worldToScreen(p, z, viewport), 0.05f)
    }

    @Test fun `zooming keeps the point under the fingers in place`() {
        val cam = Camera.initial(viewport, tank)
        val focus = Vec2(540f, 1200f)
        val z = tank.depthMm * Camera.REFERENCE_DEPTH
        val before = cam.screenToWorld(focus, z, viewport)
        val zoomed = cam.zoomedAbout(focus, 1.5f, viewport, tank)
        assertEquals(1.5f, zoomed.zoom, 1e-4f)
        assertClose(focus, zoomed.worldToScreen(before, z, viewport), 0.05f)
    }

    @Test fun `zoom is limited at both ends`() {
        val cam = Camera.initial(viewport, tank)
        assertEquals(Camera.MAX_ZOOM, cam.zoomedAbout(viewport.center, 100f, viewport, tank).zoom, 1e-4f)
        val minZoom = Camera.minZoom(viewport, tank)
        assertEquals(minZoom, cam.zoomedAbout(viewport.center, 0.001f, viewport, tank).zoom, 1e-4f)
        assertTrue(minZoom < 1f)
    }

    @Test fun `a nonsense zoom factor is ignored`() {
        val cam = Camera.initial(viewport, tank)
        assertEquals(cam, cam.zoomedAbout(viewport.center, Float.NaN, viewport, tank))
        assertEquals(cam, cam.zoomedAbout(viewport.center, -2f, viewport, tank))
    }

    @Test fun `the view never leaves the tank, even at the sand`() {
        var cam = Camera.initial(viewport, tank)
        cam = cam.panned(Vec2(1e6f, 1e6f), viewport, tank)
        val half = cam.floorHalfExtent(viewport, cam.zoom, tank)
        assertEquals(half.x, cam.center.x, 1e-3f)
        assertEquals(half.y, cam.center.y, 1e-3f)
        cam = cam.panned(Vec2(-1e6f, -1e6f), viewport, tank)
        assertEquals(tank.widthMm - half.x, cam.center.x, 1e-3f)
        assertEquals(tank.heightMm - half.y, cam.center.y, 1e-3f)
    }

    @Test fun `on an axis where the view is wider than the tank the camera is centred`() {
        val tiny = TankSize(40f, 40f, 110f)
        val cam = Camera(Vec2(5f, 33f)).clamped(viewport, tiny)
        val half = cam.floorHalfExtent(viewport, cam.zoom, tiny)
        assertTrue("the tall screen overflows the tank vertically", 2 * half.y > tiny.heightMm)
        assertEquals(tiny.center.y, cam.center.y, 1e-4f)
        assertEquals(half.x, cam.center.x, 1e-3f)
    }
}
