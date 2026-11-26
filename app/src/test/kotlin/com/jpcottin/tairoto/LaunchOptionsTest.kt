package com.jpcottin.tairoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
