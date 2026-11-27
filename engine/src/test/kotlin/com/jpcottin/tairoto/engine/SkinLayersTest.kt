package com.jpcottin.tairoto.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkinLayersTest {
    @Test fun `a drawing keeps its layer, sent once, and a second fish wearing it shares it`() {
        val l = SkinLayers(capacity = 3)
        assertEquals(0 to true, l.place("a"))
        l.wear(1, 0)
        assertEquals(0 to false, l.place("a"))
        l.wear(2, 0)
        assertEquals(1 to true, l.place("b"))
    }

    @Test fun `a layer no fish wears is reused, the longest unworn first, and none when all are worn`() {
        val l = SkinLayers(capacity = 2)
        l.place("a"); l.wear(1, 0)
        l.place("b"); l.wear(2, 1)
        assertNull(l.place("c"))
        l.unwear(2)
        l.unwear(1)
        // "b" went unworn first: its layer goes.
        assertEquals(1 to true, l.place("c"))
        assertEquals("a is still there", 0 to false, l.place("a"))
    }

    @Test fun `clearing the fish frees every layer but keeps the drawings`() {
        val l = SkinLayers(capacity = 1)
        l.place("a"); l.wear(1, 0)
        l.clearWearers()
        assertEquals(0 to false, l.place("a"))
        l.wear(5, 0)
        l.unwear(5); l.unwear(5)
        assertEquals(0 to true, l.place("z"))
    }
}
