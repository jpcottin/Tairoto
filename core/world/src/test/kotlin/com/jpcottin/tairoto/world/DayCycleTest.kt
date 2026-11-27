package com.jpcottin.tairoto.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DayCycleTest {
    @Test fun `full day at noon, the moon at midnight`() {
        assertEquals(Daylight(1f, 0f), DayCycle.at(12f))
        val midnight = DayCycle.at(0f)
        assertEquals(0f, midnight.light, 0f)
        assertEquals(0f, midnight.warmth, 0f)
        assertTrue(midnight.isNight)
        assertFalse(DayCycle.at(12f).isNight)
    }

    @Test fun `dawn and dusk are gold, and the light comes and goes smoothly`() {
        assertTrue(DayCycle.at(6f).warmth > 0.9f)
        assertTrue(DayCycle.at(18.1f).warmth > 0.9f)
        assertEquals(0.5f, DayCycle.at(6f).light, 0.01f)
        var last = 0f
        for (m in 0..24 * 60 step 5) {
            val l = DayCycle.at(m / 60f).light
            assertTrue("no jumps at ${m / 60f}", kotlin.math.abs(l - last) < 0.12f || m == 0)
            last = l
        }
    }

    @Test fun `hours wrap round the day`() {
        assertEquals(DayCycle.at(1f), DayCycle.at(25f))
        assertEquals(DayCycle.at(23f), DayCycle.at(-1f))
    }
}

class TimeOfDayTest {
    @Test fun `forced day or night ignores the clock, local follows it`() {
        assertEquals(Daylight.NOON, TimeOfDay.DAY.daylight(0f))
        assertEquals(0f, TimeOfDay.NIGHT.daylight(12f).light, 0f)
        assertEquals(DayCycle.at(19f), TimeOfDay.LOCAL.daylight(19f))
        assertEquals(TimeOfDay.NIGHT, TimeOfDay.parse("Night"))
        assertEquals(null, TimeOfDay.parse("dusk"))
    }
}
