package com.jpcottin.tairoto.engine

import com.jpcottin.tairoto.world.BiteWant
import com.jpcottin.tairoto.world.EntityId
import com.jpcottin.tairoto.world.Fed
import com.jpcottin.tairoto.world.FoodUpdate
import com.jpcottin.tairoto.world.GhostUpdate
import com.jpcottin.tairoto.world.NetCreature
import com.jpcottin.tairoto.world.NetFood
import com.jpcottin.tairoto.world.NetScent
import com.jpcottin.tairoto.world.SimInput
import com.jpcottin.tairoto.world.Take
import com.jpcottin.tairoto.world.Vec2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The flat layout jni_bridge.cpp reads and writes: if this moves, native must move with it. */
class NetPackingTest {
    private val state = NetCreature(
        EntityId((3L shl 40) or 9), 1f, 2f, 3f, 0.5f, 20f, vz = -1f, behavior = 6, stateTimer = 4f, hunger = 0.7f,
        cover = 0.9f, rest = 0.1f, alarm = 0.2f, tx = 7f, ty = 8f, tz = 9f, den = true, eatenMg = 1.5f, startle = 0.3f,
    )
    private val food = NetFood((2L shl 24) or 77, 1, 2, 10f, 11f, 12f, 0.1f, 0.2f, 0.3f, 4.5f, 0.8f, seed = -5)

    @Test fun `an input packs counts first, then ids, then numbers, section by section`() {
        val input = SimInput(
            release = listOf(EntityId(1)),
            take = listOf(Take(state.id, state), Take(EntityId(2), null)),
            ghosts = listOf(GhostUpdate(state, 0.05f)),
            ghostFood = listOf(FoodUpdate(food, 0.02f)),
            adoptFood = listOf(food),
            releaseFood = listOf(99L),
            bites = listOf(BiteWant(food.id, state.id, 0.4f, 0.3f)),
            fed = listOf(Fed(state.id, 0.25f)),
            scents = listOf(NetScent(1f, 2f, 3f, 4f, 5f, 6, 7f)),
            taps = listOf(Vec2(5f, 6f)),
        )
        val p = NetPacking.pack(input)
        assertArrayEquals(intArrayOf(1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1), p.header)
        val seed = (-5L) and 0xFFFFFFFFL
        assertArrayEquals(
            longArrayOf(1, state.id.raw, 2, state.id.raw, food.id, seed, food.id, seed, 99, food.id, state.id.raw, state.id.raw),
            p.longs,
        )
        // take(1 + 18) * 2 + ghost (1 + 18) + ghost food (1 + 10) + adopt 10 + bite 2 + fed 1 + scent 7 + tap 2
        assertEquals(19 * 2 + 19 + 11 + 10 + 2 + 1 + 7 + 2, p.floats.size)
        assertEquals(1f, p.floats[0])
        assertEquals(state.x, p.floats[1])
        assertEquals(6f, p.floats[7]) // behaviour
        assertEquals(1f, p.floats[16]) // den
        assertEquals(0f, p.floats[19]) // the second take has no state
        assertEquals(0.05f, p.floats[38])
        assertEquals(6f, p.floats[p.floats.size - 4]) // the scent's eaters
        assertEquals(6f, p.floats.last())
    }

    @Test fun `no scents and an empty input pack small`() {
        val p = NetPacking.pack(SimInput())
        assertArrayEquals(IntArray(11), p.header)
        assertEquals(0, p.longs.size)
        assertEquals(0, p.floats.size)
    }

    @Test fun `an output unpacks back into states, food, scents, wants and the bites granted`() {
        // As native would write it: one owned creature, one owned piece of food, a scent, a want, a grant.
        val header = intArrayOf(1, 0, 1, 0, 1, 1, 1)
        val longs = longArrayOf(state.id.raw, food.id, 0xFFFFFFFBL, food.id, state.id.raw)
        val floats = floatArrayOf(
            1f, 2f, 3f, 0.5f, 20f, -1f, 6f, 4f, 0.7f, 0.9f, 0.1f, 0.2f, 7f, 8f, 9f, 1f, 1.5f, 0.3f,
            1f, 2f, 10f, 11f, 12f, 0.1f, 0.2f, 0.3f, 4.5f, 0.8f,
            1f, 2f, 3f, 4f, 5f, 6f, 7f,
            0.4f, 0.3f,
            0.25f,
        )
        val input = SimInput(bites = listOf(BiteWant(1L, EntityId(42), 1f, 0.1f)))
        val out = NetPacking.unpack(input, header, longs, floats, doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))
        assertEquals(listOf(state), out.owned)
        assertEquals(listOf(food), out.food)
        assertEquals(listOf(NetScent(1f, 2f, 3f, 4f, 5f, 6, 7f)), out.scents)
        assertEquals(listOf(BiteWant(food.id, state.id, 0.4f, 0.3f)), out.wants)
        assertEquals(listOf(Fed(EntityId(42), 0.25f)), out.granted)
        assertEquals(6.0, out.ledger.inWater, 0.0)
        assertEquals(4.0, out.ledger.sent, 0.0)
    }
}
