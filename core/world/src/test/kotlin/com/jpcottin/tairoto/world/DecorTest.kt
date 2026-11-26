package com.jpcottin.tairoto.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DecorTest {
    @Test fun `every decoration has a Tahitian name written with the ʻeta, never an apostrophe`() {
        DecorCatalog.all.forEach { k ->
            assertTrue(k.tahitianName.isNotBlank())
            assertFalse("${k.id} uses an apostrophe", k.tahitianName.contains('\'') || k.tahitianName.contains('’'))
            assertEquals(k.tahitianName, k.displayName)
        }
        assertEquals("Toʻa", DecorCatalog.CoralHead.tahitianName)
        assertEquals("Toʻapū", DecorCatalog.BranchingCoral.tahitianName)
        assertEquals("Pahua", DecorCatalog.GiantClam.tahitianName)
        assertEquals("ʻŌfaʻi", DecorCatalog.Stone.tahitianName)
        assertTrue(DecorCatalog.all.flatMap { it.tahitianName.toList() }.contains('ʻ'))
    }

    @Test fun `ids are unique and native ids match the engine's DecorKind`() {
        assertEquals(DecorCatalog.all.size, DecorCatalog.all.map { it.id }.toSet().size)
        assertEquals(listOf(0, 1, 2, 3), DecorCatalog.all.map { it.nativeId })
        assertNotNull(DecorCatalog["giant_clam"])
        assertNull(DecorCatalog["sunken_ship"])
    }

    @Test fun `everything leaves room to swim over it, and the clam is the lowest`() {
        DecorCatalog.all.forEach { assertTrue(it.heightMm.endInclusive < TankSize.DEFAULT_DEPTH_MM * 0.6f) }
        assertTrue(DecorCatalog.all.all { it == DecorCatalog.GiantClam || it.heightMm.start > DecorCatalog.GiantClam.heightMm.endInclusive })
    }

    @Test fun `size picks within the kind's range`() {
        val k = DecorCatalog.CoralHead
        fun spec(size: Float) = DecorSpec(EntityId(1), k.id, 3, Vec2.Zero, 0f, size)
        assertEquals(k.radiusMm.start, k.radiusFor(spec(0f)), 1e-4f)
        assertEquals(k.radiusMm.endInclusive, k.radiusFor(spec(1f)), 1e-4f)
        assertEquals(k.heightMm.endInclusive, spec(1f).heightMm, 1e-4f)
        assertTrue(spec(0.5f).covers(Vec2(k.radiusFor(spec(0.5f)) - 0.1f, 0f)))
        assertFalse(spec(0.5f).covers(Vec2(k.radiusMm.endInclusive + 1f, 0f)))
    }

    @Test fun `a new lagoon has a floor of coral, a clam and stones, none on top of another`() {
        val size = TankSize(190f, 420f, 55f)
        repeat(20) { seed ->
            val decor = DecorLayout.defaultDecor(size, EntityIdAllocator(0), Random(seed))
            assertTrue("seed $seed: ${decor.size}", decor.size >= 5)
            assertTrue(decor.any { it.kindId == DecorCatalog.BranchingCoral.id })
            decor.forEach { d ->
                assertTrue(size.contains(d.at))
                decor.filter { it !== d }.forEach { o -> assertTrue((o.at - d.at).length >= o.radiusMm + d.radiusMm) }
            }
            assertEquals(decor.size, decor.map { it.id }.toSet().size)
        }
    }

    @Test fun `a lagoon too small for a coral head just leaves it out`() {
        val decor = DecorLayout.defaultDecor(TankSize(60f, 60f, 55f), EntityIdAllocator(0), Random(1))
        assertTrue(decor.none { it.kindId == DecorCatalog.CoralHead.id })
    }

    @Test fun `the default scene has decorations and creature ids never clash with theirs`() {
        val scene = Population.defaultScene(TankSize(190f, 420f, 55f), EntityIdAllocator(0), Random(4))
        assertTrue(scene.decor.isNotEmpty())
        assertEquals(scene.allIds.size, scene.allIds.toSet().size)
    }
}
