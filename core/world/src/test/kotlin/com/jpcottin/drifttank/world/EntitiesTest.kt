package com.jpcottin.drifttank.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EntitiesTest {
    @Test fun `ids carry the slot of the device that made them`() {
        val a = EntityIdAllocator(slot = 3)
        val id = a.allocate()
        assertEquals(3, id.creatorSlot)
        assertEquals(1L, id.serial)
        assertEquals(2L, a.allocate().serial)
    }

    @Test fun `two devices never mint the same id`() {
        val a = EntityIdAllocator(1)
        val b = EntityIdAllocator(2)
        val ids = (List(500) { a.allocate() } + List(500) { b.allocate() }).toSet()
        assertEquals(1000, ids.size)
    }

    @Test fun `after a restore the allocator carries on past the highest id`() {
        val a = EntityIdAllocator(0)
        val old = List(10) { a.allocate() }
        val restored = EntityIdAllocator(0).apply { advancePast(old) }
        assertTrue(restored.allocate().serial > old.maxOf { it.serial })
    }

    @Test fun `ids of other devices do not move the allocator`() {
        val other = List(10) { EntityIdAllocator(7).allocate() }
        val mine = EntityIdAllocator(0).apply { advancePast(other) }
        assertEquals(1L, mine.allocate().serial)
    }

    @Test fun `the default reef has a school and a turtle, all inside the tank and its depth band`() {
        val size = TankSize(200f, 320f, 110f)
        val scene = Population.defaultScene(size, EntityIdAllocator(0), Random(42))
        assertEquals(SpeciesCatalog.GreenChromis.defaultCount, scene.count("green_chromis"))
        assertEquals(1, scene.count("green_sea_turtle"))
        scene.creatures.forEach {
            val s = SpeciesCatalog.require(it.speciesId)
            assertTrue(size.contains(it.spawnAt))
            assertTrue(it.spawnDepthMm in size.depthMm * s.depthBand.start..size.depthMm * s.depthBand.endInclusive)
            assertTrue(it.lengthMm in s.lengthMm)
        }
    }

    @Test fun `a school is released close together`() {
        val size = TankSize(400f, 400f, 110f)
        val school = Population.release(SpeciesCatalog.GreenChromis, 12, size, EntityIdAllocator(0), Random(1), around = Vec2(200f, 200f))
        school.forEach { assertTrue((it.spawnAt - Vec2(200f, 200f)).length < 35f * 4f * 1.5f) }
    }

    @Test fun `the same seed gives the same tank`() {
        val size = TankSize(200f, 320f, 110f)
        val a = Population.defaultScene(size, EntityIdAllocator(0), Random(9))
        val b = Population.defaultScene(size, EntityIdAllocator(0), Random(9))
        val c = Population.defaultScene(size, EntityIdAllocator(0), Random(10))
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test fun `an id prints its slot and serial`() {
        assertEquals("E2:5", EntityId((2L shl EntityId.SLOT_SHIFT) or 5L).toString())
    }
}
