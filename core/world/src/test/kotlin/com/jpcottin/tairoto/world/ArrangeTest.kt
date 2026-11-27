package com.jpcottin.tairoto.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Laying the screens out on the table: turned, dragged, snapped, swiped together. */
class ArrangeTest {
    private val a = DeviceId(0, "a")
    private val b = DeviceId(1, "b")
    private val c = DeviceId(2, "c")
    private val phone = ScreenMm(68f, 150f)
    private val fold = ScreenMm(118f, 150f)
    private val base = TankSize(200f, 200f, TankSize.DEFAULT_DEPTH_MM)

    private fun two(): Layout = Layout(emptyList(), base).withScreen(a, phone, base).withScreen(b, phone, base)

    private fun assertNear(expected: Vec2, actual: Vec2, eps: Float = 1e-3f) {
        assertEquals("x of $actual", expected.x, actual.x, eps)
        assertEquals("y of $actual", expected.y, actual.y, eps)
    }

    private fun overlaps(p: RectMm, q: RectMm) = p.x < q.right - 0.01f && q.x < p.right - 0.01f && p.y < q.bottom - 0.01f && q.y < p.bottom - 0.01f

    private fun Layout.assertNoneOverlap() {
        for (i in placements.indices) for (j in i + 1 until placements.size) {
            assertFalse("${placements[i].device.name} lies on ${placements[j].device.name}", overlaps(placements[i].body, placements[j].body))
        }
    }

    private fun Layout.assertInsideTank() {
        val widen = 1f / DepthProjection.DEFAULT.scaleAt(tank.depthMm)
        val bb = bounds()
        // Even the sand the outer screens see at depth is in the tank.
        val half = Vec2(bb.w * widen / 2f, bb.h * widen / 2f)
        assertTrue("$bb in $tank", bb.center.x - half.x >= -0.01f && bb.center.x + half.x <= tank.widthMm + 0.01f)
        assertTrue("$bb in $tank", bb.center.y - half.y >= -0.01f && bb.center.y + half.y <= tank.heightMm + 0.01f)
    }

    // ------------------------------------------------------------ turns

    @Test fun `a quarter turn clockwise puts the glass's right on the table's down, and back`() {
        assertNear(Vec2(0f, 1f), Turns.toTable(Vec2(1f, 0f), 1))
        assertNear(Vec2(1f, 0f), Turns.toTable(Vec2(0f, -1f), 1))
        for (t in -4..7) {
            val v = Vec2(3f, -7f)
            assertNear(v, Turns.toScreen(Turns.toTable(v, t), t))
        }
        assertEquals(Edge.BOTTOM, Edge.RIGHT.turned(1))
        assertEquals(Edge.RIGHT, Edge.TOP.turned(1))
        assertEquals(Edge.LEFT, Edge.TOP.turned(-1))
        assertEquals(Edge.LEFT, Edge.RIGHT.opposite)
    }

    @Test fun `a screen lying across covers the table the other way, and its corners land where they should`() {
        val p = Placement.centeredAt(a, phone, Vec2(100f, 100f), turns = 1)
        assertEquals(150f, p.rect.w, 1e-3f)
        assertEquals(68f, p.rect.h, 1e-3f)
        // Turned clockwise, the glass's top left corner is at the table's top right.
        assertNear(Vec2(p.rect.right, p.rect.y), p.toTable(Vec2(0f, 0f)))
        assertNear(Vec2(p.rect.x, p.rect.bottom), p.toTable(Vec2(68f, 150f)))
        for (t in 0..3) {
            val q = Placement.centeredAt(a, phone, Vec2(40f, 70f), t)
            assertNear(Vec2(12f, 34f), q.toLocal(q.toTable(Vec2(12f, 34f))))
            assertNear(q.rect.center, q.toTable(Vec2(34f, 75f)))
        }
    }

    @Test fun `a camera over a turned screen draws the table turned back, and touches come back where they were`() {
        val vp = Viewport(1080f, 2400f, 16f)
        for (t in 0..3) {
            val cam = Camera(Vec2(120f, 90f), zoom = 1f, parallaxOrigin = Vec2(110f, 95f), turns = t)
            for (z in listOf(0f, 30f)) {
                val p = Vec2(101f, 87f)
                assertNear(p, cam.screenToWorld(cam.worldToScreen(p, z, vp), z, vp), 1e-2f)
            }
        }
        // A quarter clockwise: one mm to the table's right is 16 px up the glass.
        val cam = Camera(Vec2(120f, 90f), turns = 1)
        val d = cam.worldToScreen(Vec2(121f, 90f), 0f, vp) - cam.worldToScreen(Vec2(120f, 90f), 0f, vp)
        assertNear(Vec2(0f, -16f), d)
    }

    // ------------------------------------------------------------ coming in

    @Test fun `a device coming in lies right of the others, tops aligned, frames touching`() {
        val l = two().withScreen(c, fold, base)
        val (ra, rb, rc) = l.placements.map { it.rect }
        assertEquals(2 * ScreenMm.PHONE_BEZEL_MM, rb.x - ra.right, 1e-3f)
        assertEquals(2 * ScreenMm.PHONE_BEZEL_MM, rc.x - rb.right, 1e-3f)
        assertEquals(ra.y, rc.y, 1e-3f)
        l.assertNoneOverlap()
        l.assertInsideTank()
    }

    @Test fun `the screens already on the table stay over the same water when another comes in, if the tank has room`() {
        val big = TankSize(900f, 500f, TankSize.DEFAULT_DEPTH_MM)
        val one = Layout(emptyList(), big).withScreen(a, phone, big)
        val before = one.rectOf(a)!!
        val after = one.withScreen(b, phone, big).rectOf(a)!!
        assertEquals(before, after)
    }

    @Test fun `a foldable opening keeps its corner and its turn`() {
        val l = two().moved(b, Vec2(300f, 300f), 1, base)
        val closed = l.placement(b)!!
        val open = l.withScreen(b, fold, base).placement(b)!!
        assertEquals(1, open.turns)
        // Where it lies against the others (the whole table may move over the water as the tank grows).
        val opened = l.withScreen(b, fold, base)
        assertEquals(closed.rect.x - l.rectOf(a)!!.x, open.rect.x - opened.rectOf(a)!!.x, 1e-3f)
        assertEquals(closed.rect.y - l.rectOf(a)!!.y, open.rect.y - opened.rectOf(a)!!.y, 1e-3f)
        assertEquals(fold.heightMm, open.rect.w, 1e-3f)
    }

    // ------------------------------------------------------------ dragging

    @Test fun `dragged near a side, a screen snaps against it, tops in line`() {
        val l = two()
        val ra = l.rectOf(a)!!
        // Dropped 5 mm too far right and 4 mm low.
        val target = Vec2(ra.right + 6f + 5f + phone.widthMm / 2f, ra.center.y + 4f)
        val rb = l.moved(b, target, 0, base).let { it.rectOf(b)!! to it.rectOf(a)!! }
        assertEquals(2 * ScreenMm.PHONE_BEZEL_MM, rb.first.x - rb.second.right, 1e-3f)
        assertEquals(rb.second.y, rb.first.y, 1e-3f)
    }

    @Test fun `dragged far from anything, a screen stays where it was dropped`() {
        val l = two()
        val ra = l.rectOf(a)!!
        val target = Vec2(ra.center.x, ra.bottom + 6f + 40f + phone.heightMm / 2f)
        val m = l.moved(b, target, 0, base)
        val gap = m.rectOf(b)!!.y - m.rectOf(a)!!.bottom
        assertEquals(46f, gap, 1e-2f)
    }

    @Test fun `dropped on another device, a screen is pushed off it the shortest way`() {
        val l = two()
        val ra = l.rectOf(a)!!
        // Almost on top of a, a little to its right.
        val m = l.moved(b, ra.center + Vec2(20f, 0f), 0, base)
        m.assertNoneOverlap()
        assertEquals(2 * ScreenMm.PHONE_BEZEL_MM, m.rectOf(b)!!.x - m.rectOf(a)!!.right, 1e-3f)
    }

    @Test fun `turning a screen on the map lays it across`() {
        val l = two()
        val m = l.moved(b, l.rectOf(b)!!.center, 1, base)
        assertEquals(1, m.placement(b)!!.turns)
        assertEquals(phone.heightMm, m.rectOf(b)!!.w, 1e-3f)
        m.assertNoneOverlap()
        m.assertInsideTank()
    }

    @Test fun `screens dragged far apart grow the tank, which keeps them in`() {
        val l = two()
        val m = l.moved(b, Vec2(-400f, 900f), 0, base)
        m.assertInsideTank()
        assertTrue(m.tank.widthMm > l.tank.widthMm && m.tank.heightMm > l.tank.heightMm)
        // The shared parallax point sits in the middle of the screens.
        assertNear(m.bounds().center, m.origin)
    }

    // ------------------------------------------------------------ swiping

    @Test fun `a swipe from the right edge of one phone onto the left edge of another lays them side by side, the path straight`() {
        val l = two().moved(b, Vec2(30f, 500f), 0, base) // b somewhere else entirely
        val s = l.stitched(a, Edge.RIGHT, 40f, b, Edge.LEFT, 70f, base)!!
        val pa = s.placement(a)!!
        val pb = s.placement(b)!!
        assertEquals(0, pb.turns)
        assertEquals(2 * ScreenMm.PHONE_BEZEL_MM, pb.rect.x - pa.rect.right, 1e-3f)
        // Where the finger left a is level with where it came onto b.
        assertEquals(pa.toTable(Vec2(68f, 40f)).y, pb.toTable(Vec2(0f, 70f)).y, 1e-3f)
        s.assertNoneOverlap()
        s.assertInsideTank()
    }

    @Test fun `a swipe onto the top edge of the other phone turns it a quarter, so its top faces the first`() {
        val s = two().stitched(a, Edge.RIGHT, 75f, b, Edge.TOP, 34f, base)!!
        val pa = s.placement(a)!!
        val pb = s.placement(b)!!
        // b's top now faces left, toward a: a quarter turn clockwise... the other way.
        assertEquals(Edge.LEFT, Edge.TOP.turned(pb.turns))
        val exit = pa.toTable(Vec2(68f, 75f))
        val entry = pb.toTable(Vec2(34f, 0f))
        assertNear(exit + Vec2(2 * ScreenMm.PHONE_BEZEL_MM, 0f), entry)
        s.assertNoneOverlap()
    }

    @Test fun `every pair of edges and turns lines the finger's path up across the frames`() {
        for (ta in 0..3) for (exit in Edge.entries) for (entry in Edge.entries) {
            val l = two().moved(a, Vec2(400f, 400f), ta, base)
            val s = l.stitched(a, exit, 30f, b, entry, 50f, base)!!
            val pa = s.placement(a)!!
            val pb = s.placement(b)!!
            val side = exit.turned(pa.turns)
            assertEquals("entry faces back ($ta $exit $entry)", side.opposite, entry.turned(pb.turns))
            val out = pa.toTable(pa.edgePoint(exit, 30f))
            val into = pb.toTable(pb.edgePoint(entry, 50f))
            assertNear(out + side.outward * (2 * ScreenMm.PHONE_BEZEL_MM), into, 1e-2f)
            s.assertNoneOverlap()
            s.assertInsideTank()
        }
    }

    @Test fun `a swipe that would lay a screen on a third slides it along the side it joined`() {
        // a and c stacked; a swipe from a's right onto b's left at a's bottom would put b where c's corner is? No: put c right of a, low.
        val l0 = two().withScreen(c, phone, base)
        val ra = l0.rectOf(a)!!
        val l = l0.moved(c, Vec2(ra.right + 6f + 34f, ra.bottom + 6f + 75f - 40f), 0, base)
        val s = l.stitched(a, Edge.RIGHT, 140f, b, Edge.LEFT, 10f, base)!!
        s.assertNoneOverlap()
        // Still against a's side.
        assertEquals(2 * ScreenMm.PHONE_BEZEL_MM, s.rectOf(b)!!.x - s.rectOf(a)!!.right, 1e-3f)
    }

    @Test fun `a swipe within one screen, or to a device not on the table, changes nothing`() {
        val l = two()
        assertNull(l.stitched(a, Edge.RIGHT, 10f, a, Edge.LEFT, 10f, base))
        assertNull(l.stitched(a, Edge.RIGHT, 10f, c, Edge.LEFT, 10f, base))
        assertNotNull(l.stitched(a, Edge.RIGHT, 10f, b, Edge.LEFT, 10f, base))
    }

    @Test fun `along an edge past its end counts as its end`() {
        val p = Placement.centeredAt(a, phone, Vec2(100f, 100f))
        assertNear(Vec2(68f, 150f), p.edgePoint(Edge.RIGHT, 999f))
        assertNear(Vec2(0f, 0f), p.edgePoint(Edge.TOP, -5f))
        assertTrue(abs(p.edgePoint(Edge.BOTTOM, 20f).y - 150f) < 1e-3f)
    }
}

class EdgeSwipesTest {
    private val w = 1080f
    private val h = 2400f
    private val m = 40f

    @Test fun `a finger lifted at the right edge moving right left over it, so far down it`() {
        val s = EdgeSwipes.leaving(Vec2(900f, 1000f), Vec2(1078f, 1010f), w, h, m)
        assertEquals(EdgeSwipes.AtEdge(Edge.RIGHT, 1010f), s)
    }

    @Test fun `a finger put down at the left edge moving in came on over it`() {
        assertEquals(EdgeSwipes.AtEdge(Edge.LEFT, 500f), EdgeSwipes.entering(Vec2(2f, 500f), Vec2(60f, 520f), w, h, m))
        assertEquals(Edge.TOP, EdgeSwipes.entering(Vec2(300f, 0f), Vec2(310f, 80f), w, h, m)?.edge)
    }

    @Test fun `moving along an edge, or back off the way it came, or far from any edge, is no swipe across`() {
        assertNull(EdgeSwipes.leaving(Vec2(1070f, 900f), Vec2(1075f, 1100f), w, h, m))
        assertNull(EdgeSwipes.entering(Vec2(2f, 500f), Vec2(-40f, 500f), w, h, m))
        assertNull(EdgeSwipes.leaving(Vec2(500f, 1000f), Vec2(700f, 1000f), w, h, m))
        assertNull(EdgeSwipes.entering(Vec2(2f, 500f), Vec2(2f, 500f), w, h, m))
    }

    @Test fun `in a corner the nearer edge counts`() {
        assertEquals(Edge.BOTTOM, EdgeSwipes.edgeAt(Vec2(1060f, 2399f), w, h, m))
        assertEquals(Edge.RIGHT, EdgeSwipes.edgeAt(Vec2(1079f, 2380f), w, h, m))
    }
}
