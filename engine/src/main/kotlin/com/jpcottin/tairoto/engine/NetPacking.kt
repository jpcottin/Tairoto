package com.jpcottin.tairoto.engine

import com.jpcottin.tairoto.world.BiteWant
import com.jpcottin.tairoto.world.EntityId
import com.jpcottin.tairoto.world.Fed
import com.jpcottin.tairoto.world.FoodLedger
import com.jpcottin.tairoto.world.NetCreature
import com.jpcottin.tairoto.world.NetFood
import com.jpcottin.tairoto.world.NetScent
import com.jpcottin.tairoto.world.SimInput
import com.jpcottin.tairoto.world.SimOutput

/**
 * One tick of a shared lagoon as flat arrays, for one trip across JNI: a
 * header of counts, then each section's ids in the longs and its numbers in
 * the floats, in order. jni_bridge.cpp reads and writes exactly this.
 *
 * In: release, take, ghosts, ghost food, adopted food, released food, bites, fed, scents, taps.
 * Out: owned, released, food, released food, scents, wants, granted; and the ledger.
 */
object NetPacking {
    /** x, y, z, heading, speed, vz, behaviour, state timer, hunger, cover, rest, alarm, target xyz, den, eaten, startle. */
    const val STATE = 18
    /** kind, state, x, y, z, vx, vy, vz, mass, freshness (the id and seed go in the longs). */
    const val FOOD = 10
    const val SCENT = 7
    const val IN_HEADER = 11
    const val OUT_HEADER = 7

    class Packed(val header: IntArray, val longs: LongArray, val floats: FloatArray)

    fun pack(input: SimInput): Packed {
        val h = intArrayOf(
            input.release.size, input.take.size, input.ghosts.size, input.ghostFood.size, input.adoptFood.size,
            input.releaseFood.size, input.bites.size, input.fed.size, if (input.scents != null) 1 else 0,
            input.scents?.size ?: 0, input.taps.size,
        )
        val longs = LongList()
        input.release.forEach { longs += it.raw }
        input.take.forEach { longs += it.id.raw }
        input.ghosts.forEach { longs += it.state.id.raw }
        input.ghostFood.forEach { longs += it.food.id; longs += it.food.seed.toLong() and 0xFFFFFFFFL }
        input.adoptFood.forEach { longs += it.id; longs += it.seed.toLong() and 0xFFFFFFFFL }
        input.releaseFood.forEach { longs += it }
        input.bites.forEach { longs += it.food; longs += it.creature.raw }
        input.fed.forEach { longs += it.creature.raw }
        val f = FloatList()
        input.take.forEach { t ->
            f += if (t.state != null) 1f else 0f
            f.state(t.state ?: NetCreature(t.id, 0f, 0f, 0f, 0f, 0f))
        }
        input.ghosts.forEach { f += it.ageS; f.state(it.state) }
        input.ghostFood.forEach { f += it.ageS; f.food(it.food) }
        input.adoptFood.forEach { f.food(it) }
        input.bites.forEach { f += it.wantMg; f += it.messiness }
        input.fed.forEach { f += it.mg }
        input.scents?.forEach { s -> f += s.x; f += s.y; f += s.z; f += s.waterMg; f += s.sandMg; f += s.eaters.toFloat(); f += s.activity }
        input.taps.forEach { f += it.x; f += it.y }
        return Packed(h, longs.toArray(), f.toArray())
    }

    /** Reads what native answered; [input] gives the creatures the granted bites were for. */
    fun unpack(input: SimInput, header: IntArray, longs: LongArray, floats: FloatArray, ledger: DoubleArray): SimOutput {
        require(header.size >= OUT_HEADER) { "short header" }
        var li = 0
        var fi = 0
        fun l() = longs[li++]
        fun fl() = floats[fi++]
        val ownedIds = List(header[0]) { EntityId(l()) }
        val releasedIds = List(header[1]) { EntityId(l()) }
        val foodIds = List(header[2]) { l() to l() }
        val releasedFoodIds = List(header[3]) { l() to l() }
        val wantIds = List(header[5]) { l() to EntityId(l()) }
        fun state(id: EntityId) = NetCreature(
            id, fl(), fl(), fl(), heading = fl(), speed = fl(), vz = fl(), behavior = Math.round(fl()),
            stateTimer = fl(), hunger = fl(), cover = fl(), rest = fl(), alarm = fl(), tx = fl(), ty = fl(), tz = fl(),
            den = fl() > 0.5f, eatenMg = fl(), startle = fl(),
        )
        fun food(ids: Pair<Long, Long>): NetFood {
            val kind = Math.round(fl())
            val state = Math.round(fl())
            return NetFood(
                ids.first, kind, state, fl(), fl(), fl(), vx = fl(), vy = fl(), vz = fl(), massMg = fl(), freshness = fl(),
                seed = ids.second.toInt(),
            )
        }
        val owned = ownedIds.map { state(it) }
        val released = releasedIds.map { state(it) }
        val food = foodIds.map { food(it) }
        val releasedFood = releasedFoodIds.map { food(it) }
        val scents = List(header[4]) { NetScent(fl(), fl(), fl(), fl(), fl(), Math.round(fl()), fl()) }
        val wants = wantIds.map { (fid, cid) -> BiteWant(fid, cid, fl(), fl()) }
        val granted = List(header[6]) { i -> Fed(input.bites.getOrNull(i)?.creature ?: EntityId(0), fl()) }
        return SimOutput(
            owned = owned,
            released = released,
            food = food,
            releasedFood = releasedFood,
            scents = scents,
            wants = wants,
            granted = granted,
            ledger = FoodLedger(ledger[0], ledger[1], ledger[2], ledger[3], ledger[4], ledger[5]),
        )
    }

    private fun FloatList.state(s: NetCreature) {
        this += s.x; this += s.y; this += s.z; this += s.heading; this += s.speed; this += s.vz
        this += s.behavior.toFloat(); this += s.stateTimer; this += s.hunger; this += s.cover; this += s.rest
        this += s.alarm; this += s.tx; this += s.ty; this += s.tz; this += if (s.den) 1f else 0f; this += s.eatenMg
        this += s.startle
    }

    private fun FloatList.food(f: NetFood) {
        this += f.kind.toFloat(); this += f.state.toFloat(); this += f.x; this += f.y; this += f.z
        this += f.vx; this += f.vy; this += f.vz; this += f.massMg; this += f.freshness
    }

    private class LongList {
        private var a = LongArray(64)
        private var n = 0
        operator fun plusAssign(v: Long) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun toArray() = a.copyOf(n)
    }

    private class FloatList {
        private var a = FloatArray(256)
        private var n = 0
        operator fun plusAssign(v: Float) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun toArray() = a.copyOf(n)
    }
}
