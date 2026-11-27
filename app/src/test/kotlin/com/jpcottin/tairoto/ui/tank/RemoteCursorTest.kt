package com.jpcottin.tairoto.ui.tank

import com.jpcottin.tairoto.world.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCursorTest {
    private val w = 1920f
    private val h = 1080f

    @Test fun `it starts in the middle and steps a little, then further while a key is held`() {
        val c = RemoteCursor()
        assertNull(c.at)
        assertEquals(Vec2(960f, 540f), c.show(w, h))
        c.move(1, 0, 0, w, h)
        val first = c.at!!.x - 960f
        assertEquals(h * RemoteCursor.STEP, first, 0.01f)
        val before = c.at!!.x
        c.move(1, 0, 5, w, h)
        assertTrue("held steps are longer", c.at!!.x - before > first * 2)
    }

    @Test fun `against an edge, the arrows pan the lagoon instead`() {
        val c = RemoteCursor()
        c.show(w, h)
        var panned = Vec2(0f, 0f)
        repeat(60) { panned += c.move(-1, 0, 0, w, h) }
        assertEquals(h * RemoteCursor.MARGIN, c.at!!.x, 0.01f)
        assertTrue("the water slides right under a cursor pushing left: $panned", panned.x > 0f && panned.y == 0f)
        assertEquals(Vec2(0f, 0f), c.move(1, 0, 0, w, h))
    }

    @Test fun `only at the top edge does up leave the water`() {
        val c = RemoteCursor()
        c.show(w, h)
        assertFalse(c.atTop(h, w))
        repeat(40) { c.move(0, -1, 0, w, h) }
        assertTrue(c.atTop(h, w))
        c.hide()
        assertNull(c.at)
        assertFalse(c.atTop(h, w))
    }
}
