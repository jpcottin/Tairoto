package com.jpcottin.tairoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchOptionsTest {
    @Test fun `a population lists species and counts`() {
        assertEquals(mapOf("hermit_crab" to 3, "day_octopus" to 1), LaunchOptions.parsePopulation("hermit_crab:3, day_octopus:1"))
    }

    @Test fun `unknown species and bad counts are skipped`() {
        assertEquals(mapOf("hermit_crab" to 2), LaunchOptions.parsePopulation("kraken:1,hermit_crab:2,day_octopus:x,green_chromis:-4,honu"))
    }

    @Test fun `nothing usable means the default lagoon`() {
        assertNull(LaunchOptions.parsePopulation(null))
        assertNull(LaunchOptions.parsePopulation(""))
        assertNull(LaunchOptions.parsePopulation("kraken:3"))
    }

    @Test fun `decorations list kinds and counts`() {
        assertEquals(mapOf("giant_clam" to 2, "stone" to 1), LaunchOptions.parseDecor("giant_clam:2, sunken_ship:1, stone:1, coral_head:99"))
        assertNull(LaunchOptions.parseDecor(""))
    }

    @Test fun `only a plain launch keeps the lagoon`() {
        assertTrue(LaunchOptions().persist)
        assertTrue(LaunchOptions(seed = 3, hints = false, zoom = 2f).persist)
        assertFalse(LaunchOptions(ci = true).persist)
        assertFalse(LaunchOptions(fresh = true).persist)
        assertFalse(LaunchOptions(population = mapOf("hermit_crab" to 1)).persist)
        assertFalse(LaunchOptions(decor = mapOf("stone" to 1)).persist)
    }
}
