package com.jpcottin.tairoto.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OwnershipTest {
    private val host = DeviceId(0, "host")
    private val a = DeviceId(1, "phone a")
    private val b = DeviceId(2, "tablet")
    private val id = EntityId(42)

    private fun accepted(r: OwnershipResult) = (r as OwnershipResult.Accepted).ownership

    @Test fun `an entity changes hands with an offer then a claim`() {
        val reg = OwnershipRegistry(host)
        reg.register(id, a)
        val offered = accepted(reg.handoff(id, a, b, epoch = 1))
        assertEquals(a, offered.owner) // still a's until claimed
        assertEquals(b, offered.pendingTo)
        val claimed = accepted(reg.claim(id, b, epoch = 1))
        assertEquals(Ownership(b, 1), claimed)
        assertEquals(listOf(id), reg.ownedBy(b))
    }

    @Test fun `only the owner can offer, and only at the next epoch`() {
        val reg = OwnershipRegistry(host)
        reg.register(id, a)
        assertTrue(reg.handoff(id, b, a, 1) is OwnershipResult.Rejected)
        assertTrue(reg.handoff(id, a, b, 0) is OwnershipResult.Rejected)
        assertTrue(reg.handoff(id, a, b, 2) is OwnershipResult.Rejected)
        assertTrue(reg.handoff(id, a, a, 1) is OwnershipResult.Rejected)
        assertTrue(reg.handoff(EntityId(7), a, b, 1) is OwnershipResult.Rejected)
    }

    @Test fun `a second offer while one is pending is refused`() {
        val reg = OwnershipRegistry(host)
        reg.register(id, a)
        reg.handoff(id, a, b, 1)
        assertTrue(reg.handoff(id, a, host, 1) is OwnershipResult.Rejected)
    }

    @Test fun `a claim by the wrong device or epoch is refused`() {
        val reg = OwnershipRegistry(host)
        reg.register(id, a)
        reg.handoff(id, a, b, 1)
        assertTrue(reg.claim(id, host, 1) is OwnershipResult.Rejected)
        assertTrue(reg.claim(id, b, 2) is OwnershipResult.Rejected)
        assertEquals(a, reg[id]!!.owner)
    }

    @Test fun `a stale claim after the offer expired does nothing`() {
        val reg = OwnershipRegistry(host)
        reg.register(id, a)
        reg.handoff(id, a, b, 1)
        assertEquals(Ownership(a, 0), accepted(reg.expire(id)))
        assertTrue(reg.claim(id, b, 1) is OwnershipResult.Rejected)
        assertEquals(a, reg[id]!!.owner)
        assertTrue(reg.expire(id) is OwnershipResult.Rejected)
    }

    @Test fun `a lost device's entities go to the host, and offers to it are cancelled`() {
        val reg = OwnershipRegistry(host)
        val mine = EntityId(1)
        val offeredToA = EntityId(2)
        reg.register(mine, a)
        reg.register(offeredToA, b)
        reg.handoff(offeredToA, b, a, 1)
        val results = reg.deviceLost(a)
        assertEquals(2, results.size)
        assertEquals(Ownership(host, 1), reg[mine])
        assertEquals(Ownership(b, 0), reg[offeredToA])
    }

    @Test fun `registering twice is refused and unregistering forgets`() {
        val reg = OwnershipRegistry(host)
        reg.register(id, a)
        assertTrue(reg.register(id, b) is OwnershipResult.Rejected)
        assertTrue(reg.unregister(id))
        assertNull(reg[id])
        assertEquals(0, reg.size)
    }

    /**
     * Throws thousands of random, often wrong, messages at the registry, the
     * way a lossy network with late packets would, and checks after each one
     * that every entity still has exactly one owner from the session and an
     * epoch that never went backwards.
     */
    @Test fun `every entity always has exactly one owner, whatever arrives`() {
        val devices = listOf(host, a, b, DeviceId(3, "fold"))
        repeat(20) { round ->
            val rnd = Random(round)
            val reg = OwnershipRegistry(host)
            val ids = List(12) { EntityId(it.toLong() + 1) }
            ids.forEach { reg.register(it, devices.random(rnd)) }
            val lastEpoch = ids.associateWith { 0 }.toMutableMap()
            repeat(3000) {
                val e = ids.random(rnd)
                val cur = reg[e]!!
                val nearEpoch = cur.epoch + rnd.nextInt(-1, 3)
                when (rnd.nextInt(5)) {
                    0, 1 -> reg.handoff(e, if (rnd.nextBoolean()) cur.owner else devices.random(rnd), devices.random(rnd), nearEpoch)
                    2 -> reg.claim(e, if (rnd.nextInt(3) > 0) cur.pendingTo ?: devices.random(rnd) else devices.random(rnd),
                        if (rnd.nextInt(3) > 0) cur.pendingEpoch else nearEpoch)
                    3 -> reg.expire(e)
                    else -> if (rnd.nextInt(40) == 0) reg.deviceLost(devices.drop(1).random(rnd))
                }
                for (x in ids) {
                    val o = reg[x]!!
                    assertTrue(o.owner in devices)
                    assertTrue("epoch went back for $x", o.epoch >= lastEpoch[x]!!)
                    lastEpoch[x] = o.epoch
                    if (o.pendingTo != null) {
                        assertTrue(o.pendingTo != o.owner)
                        assertEquals(o.epoch + 1, o.pendingEpoch)
                    }
                }
            }
            assertEquals(ids.size, ids.sumOf { x -> devices.count { reg.ownedBy(it).contains(x) } })
        }
    }
}
