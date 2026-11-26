package com.jpcottin.tairoto.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jpcottin.tairoto.world.BiteWant
import com.jpcottin.tairoto.world.CreatureSpec
import com.jpcottin.tairoto.world.EntityId
import com.jpcottin.tairoto.world.FoodUpdate
import com.jpcottin.tairoto.world.GhostUpdate
import com.jpcottin.tairoto.world.NetFood
import com.jpcottin.tairoto.world.SimInput
import com.jpcottin.tairoto.world.SpeciesCatalog
import com.jpcottin.tairoto.world.TankSize
import com.jpcottin.tairoto.world.Take
import com.jpcottin.tairoto.world.Vec2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two native tanks in one process stand for two devices of a shared lagoon:
 * what one exchange gives out, the test hands to the other, as the network
 * would. Checks the JNI layout end to end (NetPacking.kt against jni_bridge.cpp).
 */
@RunWith(AndroidJUnit4::class)
class SharedNativeTest {
    private val size = TankSize(300f, 200f, 55f)
    private lateinit var a: NativeTank
    private lateinit var b: NativeTank
    private val chromis = SpeciesCatalog.GreenChromis
    private val fish = List(6) { CreatureSpec(EntityId(it + 1L), chromis.id, 100 + it, 22f, Vec2(100f + 8f * it, 100f), 20f) }

    @Before fun setUp() {
        a = NativeTank().apply { setDeterministic(true, 1); setTank(size); setFoodSlot(0) }
        b = NativeTank().apply { setDeterministic(true, 2); setTank(size); setFoodSlot(1) }
        fish.forEach {
            a.spawn(it, chromis)
            b.spawn(it, chromis, ghost = true)
        }
    }

    @After fun tearDown() {
        a.close()
        b.close()
    }

    /** Steps both and tells b what a simulates, 15 times a second. */
    private fun run(seconds: Float) {
        repeat((seconds * 15).toInt()) {
            a.stepForTest(1f / 15f)
            b.stepForTest(1f / 15f)
            val out = a.exchange(SimInput())
            b.exchange(SimInput(ghosts = out.owned.map { GhostUpdate(it, 0.02f) }))
        }
    }

    @Test fun ghostsFollowTheirOwnerThroughTheBridge() {
        val out = a.exchange(SimInput())
        assertEquals(fish.map { it.id }.toSet(), out.owned.map { it.id }.toSet())
        assertTrue("b simulates nothing", b.exchange(SimInput()).owned.isEmpty())
        run(6f)
        val there = a.snapshot().associateBy { it.id }
        for (g in b.snapshot()) {
            val o = there.getValue(g.id)
            assertTrue("${g.id} is ${Vec2(g.x - o.x, g.y - o.y).length} mm off", Vec2(g.x - o.x, g.y - o.y).length < 12f)
        }
    }

    @Test fun aCreatureChangesHandsWithItsState() {
        run(2f)
        val id = fish[0].id
        val out = a.exchange(SimInput(release = listOf(id)))
        val last = out.released.single()
        assertEquals(id, last.id)
        assertTrue(out.owned.none { it.id == id })
        val bOut = b.exchange(SimInput(take = listOf(Take(id, last))))
        val taken = bOut.owned.single { it.id == id }
        assertEquals(last.hunger, taken.hunger, 1e-4f)
        assertEquals(last.behavior, taken.behavior)
        // Released twice, taken twice: nothing more happens.
        assertTrue(a.exchange(SimInput(release = listOf(id))).released.isEmpty())
        assertEquals(1, b.exchange(SimInput(take = listOf(Take(id, last)))).owned.count { it.id == id })
    }

    @Test fun foodCarriesItsDevicesSlotAndChangesHandsAccountedFor() {
        b.dropFood(Vec2(200f, 100f), FoodKind.PELLET, 1)
        b.stepForTest(0.1f)
        val bOut = b.exchange(SimInput())
        val pellet = bOut.food.single()
        assertEquals(1, NetFood.slotOf(pellet.id))
        assertEquals(pellet.massMg.toDouble(), bOut.ledger.dropped, 1e-3)

        // a hears of it, then b hands it to a.
        a.exchange(SimInput(ghostFood = listOf(FoodUpdate(pellet, 0f))))
        assertEquals(0.0, a.exchange(SimInput()).ledger.inWater, 1e-6)
        val released = b.exchange(SimInput(releaseFood = listOf(pellet.id))).releasedFood.single()
        val aOut = a.exchange(SimInput(adoptFood = listOf(released)))
        assertEquals(released.massMg.toDouble(), aOut.ledger.received, 1e-3)
        assertEquals(released.massMg.toDouble(), aOut.ledger.inWater, 1e-3)
        assertEquals(pellet.id, aOut.food.single().id)
        val bAfter = b.exchange(SimInput())
        assertEquals(released.massMg.toDouble(), bAfter.ledger.sent, 1e-3)
        assertEquals(0.0, bAfter.ledger.inWater, 1e-6)
    }

    @Test fun aBiteFromAnotherDeviceIsDecidedWhereTheFoodIs() {
        a.dropFood(Vec2(150f, 150f), FoodKind.PELLET, 1)
        a.stepForTest(0.1f)
        val pellet = a.exchange(SimInput()).food.single()
        val out = a.exchange(SimInput(bites = listOf(BiteWant(pellet.id, EntityId(77), 1.0f, 0.3f), BiteWant(12345, EntityId(78), 1f, 0f))))
        assertEquals(2, out.granted.size)
        assertEquals(EntityId(77), out.granted[0].creature)
        assertEquals(1.0f, out.granted[0].mg, 1e-4f)
        assertEquals(0f, out.granted[1].mg)
        assertEquals(1.0, out.ledger.eaten, 1e-4)
        // The crumbs broke off here: still a's, still accounted for.
        val l = out.ledger
        assertEquals(l.dropped, l.eaten + l.decomposed + l.inWater, 1e-3)
    }

    @Test fun aTapFromAnotherScreenFrightensFishHere() {
        run(1f)
        val before = a.exchange(SimInput()).owned.associateBy { it.id }
        val at = Vec2(before.values.first().x, before.values.first().y)
        a.exchange(SimInput(taps = listOf(at)))
        a.stepForTest(0.3f)
        val after = a.exchange(SimInput()).owned
        assertTrue("they darted off", after.count { it.startle > 0f } >= 3)
    }
}
