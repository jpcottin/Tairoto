package com.jpcottin.tairoto.world

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Decorations, the net, and undo / redo, through the session's one way in. */
class EditTest {
    private val size = TankSize(200f, 300f, 55f)

    private fun TestScope.started(scene: TankScene = TankScene(size)): TankSession {
        val s = TankSession(scene, LoopbackLink(), Random(5))
        val job = launch { s.run() }
        backgroundJobs += job
        return s
    }

    private val backgroundJobs = mutableListOf<kotlinx.coroutines.Job>()

    private fun TestScope.done() = backgroundJobs.forEach { it.cancel() }

    @Test fun `decoration messages survive the wire format`() {
        val d = DecorSpec(EntityId(7), "giant_clam", 9, Vec2(3f, 4f), 1.2f, 0.4f)
        listOf(Msg.PlaceDecor(d), Msg.MoveDecor(EntityId(7), Vec2(5f, 6f), 0.3f), Msg.RemoveDecor(EntityId(7)),
            Msg.Reset(TankScene(size, decor = listOf(d))))
            .forEach { assertEquals(it, Wire.decode(Wire.encode(it))) }
    }

    @Test fun `placing a decoration puts it on the sand where asked`() = runTest {
        val s = started()
        yield()
        val d = s.placeDecor("coral_head", Vec2(80f, 90f))!!
        val scene = s.scene.first { it.decor.isNotEmpty() }
        assertEquals(d, scene.decor.single())
        assertEquals(Vec2(80f, 90f), d.at)
        // Off the edge of the lagoon it stays on the sand.
        val e = s.placeDecor("stone", Vec2(-40f, 900f))!!
        assertEquals(Vec2(0f, 300f), e.at)
        done()
    }

    @Test fun `an unknown kind is refused`() = runTest {
        val s = started()
        yield()
        assertTrue(runCatching { s.placeDecor("sunken_ship", Vec2.Zero) }.isFailure)
        s.handle(Envelope(DeviceId.Solo, null, Msg.PlaceDecor(DecorSpec(EntityId(3), "sunken_ship", 1, Vec2.Zero, 0f, 0f))))
        assertTrue(s.scene.value.decor.isEmpty())
        done()
    }

    @Test fun `the floor holds at most MAX_DECOR decorations`() = runTest {
        val s = started()
        yield()
        repeat(TankScene.MAX_DECOR) { s.placeDecor("stone", Vec2(10f + it, 20f)) }
        s.scene.first { it.decor.size == TankScene.MAX_DECOR }
        assertNull(s.placeDecor("stone", Vec2(50f, 50f)))
        done()
    }

    @Test fun `a drag is one undoable move, not hundreds`() = runTest {
        val s = started()
        yield()
        val d = s.placeDecor("stone", Vec2(50f, 50f))!!
        s.scene.first { it.decor.isNotEmpty() }
        for (i in 1..30) s.dragDecor(d.id, Vec2(50f + i, 50f + i * 2), 0.1f * i)
        s.scene.first { it.decor.single().at == Vec2(80f, 110f) }
        s.endDrag(d)
        assertEquals("moving ʻŌfaʻi", s.undo.value.undo)
        assertTrue(s.undo())
        val back = s.scene.first { it.decor.single().at == Vec2(50f, 50f) }.decor.single()
        assertEquals(d.angle, back.angle)
        assertEquals("placing ʻŌfaʻi", s.undo.value.undo)
        assertTrue(s.redo())
        s.scene.first { it.decor.single().at == Vec2(80f, 110f) }
        done()
    }

    @Test fun `a drag that ends where it began is not an edit`() = runTest {
        val s = started()
        yield()
        val d = s.placeDecor("stone", Vec2(50f, 50f))!!
        s.scene.first { it.decor.isNotEmpty() }
        s.endDrag(d)
        assertEquals("placing ʻŌfaʻi", s.undo.value.undo)
        done()
    }

    @Test fun `removing a decoration can be undone, and done again`() = runTest {
        val s = started()
        yield()
        val d = s.placeDecor("giant_clam", Vec2(60f, 70f))!!
        s.scene.first { it.decor.isNotEmpty() }
        assertTrue(s.removeDecor(d.id))
        s.scene.first { it.decor.isEmpty() }
        assertFalse(s.removeDecor(d.id))
        s.undo()
        assertEquals(d, s.scene.first { it.decor.isNotEmpty() }.decor.single())
        s.redo()
        s.scene.first { it.decor.isEmpty() }
        done()
    }

    @Test fun `netting a creature takes it out, and undoing puts it back where it was caught`() = runTest {
        val s = started()
        yield()
        s.add("green_sea_turtle", 1)
        val turtle = s.scene.first { it.creatures.isNotEmpty() }.creatures.single()
        assertTrue(s.net(turtle.id, Vec2(33f, 44f), 20f))
        s.scene.first { it.creatures.isEmpty() }
        assertEquals("netting the Honu", s.undo.value.undo)
        s.undo()
        val back = s.scene.first { it.creatures.isNotEmpty() }.creatures.single()
        assertEquals(turtle.id, back.id)
        assertEquals(Vec2(33f, 44f), back.spawnAt)
        assertEquals(20f, back.spawnDepthMm)
        assertNotNull(s.ownership(turtle.id))
        s.redo()
        s.scene.first { it.creatures.isEmpty() }
        assertFalse(s.net(turtle.id))
        done()
    }

    @Test fun `a release can be undone as a whole`() = runTest {
        val s = started()
        yield()
        s.add("green_chromis", 6)
        s.scene.first { it.creatures.size == 6 }
        assertEquals("release of 6 ʻAtoti", s.undo.value.undo)
        s.undo()
        s.scene.first { it.creatures.isEmpty() }
        assertTrue(s.undo.value.canRedo)
        s.redo()
        s.scene.first { it.creatures.size == 6 }
        done()
    }

    @Test fun `undo goes back through edits in order, and a new edit drops what was undone`() = runTest {
        val s = started()
        yield()
        val a = s.placeDecor("stone", Vec2(20f, 20f))!!
        val b = s.placeDecor("coral_head", Vec2(100f, 100f))!!
        s.scene.first { it.decor.size == 2 }
        s.undo()
        s.scene.first { it.decor.map { d -> d.id } == listOf(a.id) }
        assertEquals("placing Toʻa", s.undo.value.redo)
        s.placeDecor("giant_clam", Vec2(150f, 200f))
        assertFalse(s.undo.value.canRedo)
        assertFalse(s.redo())
        s.undo()
        s.undo()
        s.scene.first { it.decor.isEmpty() }
        assertFalse(s.undo())
        assertFalse(s.undo.value.canUndo)
        assertTrue(b.id != a.id)
        done()
    }

    @Test fun `a reset starts over with the default floor and forgets the history`() = runTest {
        val s = started()
        yield()
        s.placeDecor("stone", Vec2(20f, 20f))
        s.reset()
        val scene = s.scene.first { it.creatures.isNotEmpty() }
        assertTrue(scene.decor.isNotEmpty())
        assertFalse(s.undo.value.canUndo)
        done()
    }

    @Test fun `ids of restored decorations are never minted again`() = runTest {
        val d = DecorSpec(EntityId(40), "stone", 1, Vec2(10f, 10f), 0f, 0.5f)
        val s = started(TankScene(size, decor = listOf(d)))
        yield()
        val e = s.placeDecor("stone", Vec2(50f, 50f))!!
        assertTrue(e.id.serial > 40)
        done()
    }

    @Test fun `the scene diff follows decorations placed, moved and removed`() {
        val a = DecorSpec(EntityId(1), "stone", 1, Vec2(10f, 10f), 0f, 0.5f)
        val b = DecorSpec(EntityId(2), "coral_head", 2, Vec2(50f, 50f), 0f, 0.5f)
        val c = DecorSpec(EntityId(3), "giant_clam", 3, Vec2(90f, 90f), 0f, 0.5f)
        val applied = mapOf(a.id to a, b.id to b)
        val scene = TankScene(size, decor = listOf(a, b.copy(at = Vec2(60f, 50f)), c))
        val diff = SceneDiff.between(emptySet(), scene, applied)
        assertEquals(listOf(c), diff.decorAdded)
        assertEquals(listOf(b.id), diff.decorMoved.map { it.id })
        assertTrue(diff.decorRemoved.isEmpty())
        val gone = SceneDiff.between(emptySet(), TankScene(size), applied)
        assertEquals(setOf(a.id, b.id), gone.decorRemoved.toSet())
        assertTrue(SceneDiff.between(emptySet(), TankScene(size, decor = listOf(a)), mapOf(a.id to a)).isEmpty)
    }
}
