package com.jpcottin.tairoto.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SavedLagoonTest {
    private val size = TankSize(190f, 420f, 55f)
    private val scene = Population.defaultScene(size, EntityIdAllocator(0), Random(8))

    @Test fun `a lagoon comes back exactly as it was saved`() {
        assertEquals(scene, SavedLagoon.decode(SavedLagoon.encode(scene)))
    }

    @Test fun `creatures come back where they were, not where they were released`() {
        val first = scene.creatures.first()
        val placed = scene.withPlaces(mapOf(first.id to CreaturePlace(Vec2(12f, 34f), 21f)))
        val back = SavedLagoon.decode(SavedLagoon.encode(placed))!!
        assertEquals(Vec2(12f, 34f), back.creature(first.id)!!.spawnAt)
        assertEquals(21f, back.creature(first.id)!!.spawnDepthMm)
        assertEquals(scene.creatures[1], back.creatures[1])
    }

    @Test fun `places outside the lagoon or not finite are kept in it or ignored`() {
        val (a, b) = scene.creatures.take(2)
        val placed = scene.withPlaces(
            mapOf(a.id to CreaturePlace(Vec2(-5f, 9999f), 300f), b.id to CreaturePlace(Vec2(Float.NaN, 1f), 2f)),
        )
        assertEquals(Vec2(0f, size.heightMm), placed.creature(a.id)!!.spawnAt)
        assertEquals(size.depthMm, placed.creature(a.id)!!.spawnDepthMm)
        assertEquals(b, placed.creature(b.id))
    }

    @Test fun `a broken or empty file restores nothing instead of crashing`() {
        listOf("", "{", "not json", "{\"version\":1}", "{\"version\":1,\"scene\":{\"size\":{\"widthMm\":-1,\"heightMm\":2,\"depthMm\":3}}}")
            .forEach { assertNull(it, SavedLagoon.decode(it)) }
    }

    @Test fun `a file from a newer version is not read`() {
        val text = SavedLagoon.encode(scene).replaceFirst("\"version\":1", "\"version\":99")
        assertNull(SavedLagoon.decode(text))
    }

    @Test fun `species and decorations this version does not know are left out, the rest kept`() {
        val alien = scene.creatures.first().copy(id = EntityId(9_999), speciesId = "dugong")
        val wreck = scene.decor.first().copy(id = EntityId(9_998), kindId = "sunken_ship")
        val text = SavedLagoon.encode(scene.copy(creatures = scene.creatures + alien, decor = scene.decor + wreck))
        val back = SavedLagoon.decode(text)!!
        assertEquals(scene.creatures, back.creatures)
        assertEquals(scene.decor, back.decor)
    }

    @Test fun `fields added by a newer version are ignored`() {
        val text = SavedLagoon.encode(scene).dropLast(1) + ",\"weather\":\"rain\"}"
        assertEquals(scene, SavedLagoon.decode(text))
    }

    @Test fun `duplicate ids are kept once`() {
        val c = scene.creatures.first()
        val back = SavedLagoon.decode(SavedLagoon.encode(scene.copy(creatures = scene.creatures + c)))!!
        assertEquals(scene.creatures.size, back.creatures.size)
        assertTrue(back.creatures.count { it.id == c.id } == 1)
    }
}
