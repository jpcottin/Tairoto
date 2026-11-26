package com.jpcottin.tairoto.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeciesTest {
    @Test fun `species ids and visuals are unique`() {
        val all = SpeciesCatalog.all
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.visualId }.toSet().size)
    }

    @Test fun `the lagoon creatures go by their Tahitian names`() {
        assertEquals("ʻAtoti", SpeciesCatalog.GreenChromis.displayName)
        assertEquals("Honu", SpeciesCatalog.GreenSeaTurtle.displayName)
        assertEquals("Fai manu", SpeciesCatalog.SpottedEagleRay.displayName)
        assertEquals("Feʻe", SpeciesCatalog.DayOctopus.displayName)
        assertEquals("Uʻa", SpeciesCatalog.HermitCrab.displayName)
        assertEquals("Manini", SpeciesCatalog.ConvictSurgeonfish.displayName)
        assertEquals("Taape", SpeciesCatalog.BluestripeSnapper.displayName)
        assertEquals("ʻOuma", SpeciesCatalog.YellowstripeGoatfish.displayName)
        assertEquals("Mamo", SpeciesCatalog.ScissortailSergeant.displayName)
        assertEquals("Green chromis", SpeciesCatalog.GreenChromis.copy(tahitianName = null).displayName)
    }

    @Test fun `every species can be found by id`() {
        SpeciesCatalog.all.forEach { assertEquals(it, SpeciesCatalog[it.id]) }
        assertNull(SpeciesCatalog["kraken"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `requiring an unknown species fails`() {
        SpeciesCatalog.require("kraken")
    }

    @Test fun `native params are in the documented order`() {
        val p = SpeciesCatalog.GreenChromis.nativeParams()
        assertEquals(NativeParam.COUNT, p.size)
        assertEquals(1.3f, p[NativeParam.CRUISE], 0f)
        assertEquals(5f, p[NativeParam.BURST], 0f)
        assertEquals(0.18f, p[NativeParam.DEPTH_MIN], 0f)
        assertEquals(0.62f, p[NativeParam.DEPTH_MAX], 0f)
        assertEquals(0.85f, p[NativeParam.SCHOOLING], 0f)
        assertEquals(0.24f, p[NativeParam.BODY_WIDTH], 0f)
    }

    @Test fun `a creature's length comes from its seed and stays in range`() {
        val s = SpeciesCatalog.GreenSeaTurtle
        for (seed in listOf(0, 1, -1, 65535, Int.MAX_VALUE, Int.MIN_VALUE, 123456789)) {
            val l = s.lengthFor(seed)
            assertTrue("$seed -> $l", l in s.lengthMm)
            assertEquals(l, s.lengthFor(seed), 0f)
        }
    }

    @Test fun `the reef has schooling fish and a turtle that paddles`() {
        val reef = SpeciesCatalog.all.filter { it.tankType == TankType.REEF }
        assertNotNull(reef.firstOrNull { it.locomotion == Locomotion.SWIM && it.schooling > 0.5f })
        assertNotNull(reef.firstOrNull { it.locomotion == Locomotion.PADDLE })
    }

    @Test fun `a turtle is much bigger and slower than a chromis`() {
        val c = SpeciesCatalog.GreenChromis
        val t = SpeciesCatalog.GreenSeaTurtle
        assertTrue(t.lengthMm.start > c.lengthMm.endInclusive * 2.5f)
        assertTrue(t.cruiseBodyLengthsPerS * t.lengthMm.start < c.cruiseBodyLengthsPerS * c.lengthMm.endInclusive * 1.5f)
        assertTrue(t.turnRateRadPerS < c.turnRateRadPerS)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a species with a bad depth band is refused`() {
        SpeciesCatalog.GreenChromis.copy(depthBand = 0.8f..1.2f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a species id must be snake case`() {
        SpeciesCatalog.GreenChromis.copy(id = "Green Chromis")
    }

    @Test fun `Tahitian names are written with the ʻeta, never an apostrophe`() {
        for (species in SpeciesCatalog.all) {
            val name = species.tahitianName ?: continue
            for (bad in listOf('\'', '’', '‘', '`', 'ʼ')) assertTrue("$name has '$bad'", bad !in name)
        }
        assertTrue(SpeciesCatalog.DayOctopus.tahitianName!!.contains('\u02BB'))
    }

    @Test fun `every way of moving has a species, and the native ids line up`() {
        assertEquals(Locomotion.entries.toSet(), SpeciesCatalog.all.map { it.locomotion }.toSet())
        assertEquals(listOf(0, 1, 2, 3, 4), Locomotion.entries.map { it.nativeId })
        // Visual 2 is food on the native side.
        assertTrue(SpeciesCatalog.all.none { it.visualId == 2 })
        assertEquals(setOf(0, 1, 3, 4, 5, 7, 8, 9, 10), SpeciesCatalog.all.map { it.visualId }.toSet())
        // 6 is the clouds of sand and ink.
        assertTrue(SpeciesCatalog.all.none { it.visualId == 6 })
    }

    @Test fun `the bottom dwellers live and feed on the sand`() {
        val bottom = listOf(SpeciesCatalog.SpottedEagleRay, SpeciesCatalog.DayOctopus, SpeciesCatalog.HermitCrab)
        bottom.forEach { assertTrue(it.id, it.bottomFeeder) }
        assertEquals(1f, SpeciesCatalog.HermitCrab.depthBand.start, 0f)
        assertTrue(SpeciesCatalog.DayOctopus.depthBand.start > 0.9f)
        assertTrue("a ray is wider than it is long", SpeciesCatalog.SpottedEagleRay.bodyWidth > 1.5f)
        assertTrue(SpeciesCatalog.HermitCrab.lengthMm.endInclusive < SpeciesCatalog.GreenChromis.lengthMm.endInclusive * 1.2f)
    }

    @Test fun `nothing is wider than twice its length`() {
        val ok = runCatching { SpeciesCatalog.SpottedEagleRay.copy(bodyWidth = 2f) }
        assertTrue(ok.isSuccess)
        val wide = runCatching { SpeciesCatalog.SpottedEagleRay.copy(bodyWidth = 2.5f) }
        assertTrue(wide.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `the lagoon has five schools of small fish, each in its own depth`() {
        val schools = SpeciesCatalog.all.filter { it.locomotion == Locomotion.SWIM && it.schooling > 0.7f }
        assertEquals(5, schools.size)
        schools.forEach { assertTrue(it.id, it.lengthMm.endInclusive <= 30f) }
        // Not all at the same depth: the sergeants high, the goatfish near the sand.
        assertTrue(SpeciesCatalog.ScissortailSergeant.depthBand.endInclusive < SpeciesCatalog.YellowstripeGoatfish.depthBand.start)
    }
}
