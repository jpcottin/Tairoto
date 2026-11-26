package com.jpcottin.tairoto.world

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * A creature's moment as its owner sends it: enough for another device to draw
 * it (a ghost), and to carry on simulating it if it is handed over. The pose
 * of the body (spine, arms, legs) is not sent: every device animates its own
 * copy from the motion, so a fish crossing an edge looks the same on both.
 * Short names keep the messages small; 15 a second go to every neighbour.
 */
@Serializable
data class NetCreature(
    @SerialName("i") val id: EntityId,
    val x: Float,
    val y: Float,
    val z: Float,
    @SerialName("h") val heading: Float,
    @SerialName("s") val speed: Float,
    val vz: Float = 0f,
    @SerialName("b") val behavior: Int = 0,
    @SerialName("st") val stateTimer: Float = 0f,
    @SerialName("hu") val hunger: Float = 0.5f,
    @SerialName("c") val cover: Float = 0f,
    @SerialName("r") val rest: Float = 0f,
    @SerialName("a") val alarm: Float = 0f,
    val tx: Float = 0f,
    val ty: Float = 0f,
    val tz: Float = 0f,
    @SerialName("d") val den: Boolean = false,
    @SerialName("e") val eatenMg: Float = 0f,
    @SerialName("k") val startle: Float = 0f,
) {
    val at: Vec2 get() = Vec2(x, y)

    /** The same state rounded to what matters on the wire (a hundredth of a mm, of a radian). */
    fun compact(): NetCreature = copy(
        x = r2(x), y = r2(y), z = r2(z), heading = r3(heading), speed = r2(speed), vz = r2(vz),
        stateTimer = r2(stateTimer), hunger = r3(hunger), cover = r3(cover), rest = r3(rest), alarm = r3(alarm),
        tx = r2(tx), ty = r2(ty), tz = r2(tz), eatenMg = r2(eatenMg), startle = r2(startle),
    )
}

/** A piece of food as its owner sends it. Ids carry the slot of the device that made it. */
@Serializable
data class NetFood(
    @SerialName("i") val id: Long,
    @SerialName("k") val kind: Int,
    @SerialName("s") val state: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val vz: Float = 0f,
    @SerialName("m") val massMg: Float,
    @SerialName("f") val freshness: Float = 1f,
    @SerialName("sd") val seed: Int = 0,
) {
    val at: Vec2 get() = Vec2(x, y)

    fun compact(): NetFood = copy(
        x = r2(x), y = r2(y), z = r2(z), vx = r2(vx), vy = r2(vy), vz = r2(vz),
        massMg = r4(massMg), freshness = r3(freshness),
    )

    companion object {
        /** The device slot that made a piece of food, from its id. */
        fun slotOf(id: Long): Int = ((id ushr 24) and 0xFF).toInt()
    }
}

/** A patch of food and how strongly it draws fish, shared so fish smell food on the next screen. */
@Serializable
data class NetScent(
    val x: Float,
    val y: Float,
    val z: Float,
    @SerialName("w") val waterMg: Float,
    @SerialName("s") val sandMg: Float,
    @SerialName("e") val eaters: Int = 0,
    @SerialName("a") val activity: Float = 0f,
) {
    val at: Vec2 get() = Vec2(x, y)

    fun compact(): NetScent = copy(x = r2(x), y = r2(y), z = r2(z), waterMg = r3(waterMg), sandMg = r3(sandMg), activity = r2(activity))
}

/** A creature of this device bit food that another device owns: how much it would like. */
@Serializable
data class BiteWant(
    @SerialName("f") val food: Long,
    @SerialName("c") val creature: EntityId,
    @SerialName("m") val wantMg: Float,
    /** The eater's messiness: the food's owner breaks the crumbs off. */
    @SerialName("x") val messiness: Float,
)

/** What a bite got: the food's owner decided it. */
@Serializable
data class Fed(@SerialName("c") val creature: EntityId, @SerialName("m") val mg: Float)

/** Where the food of one device went. Summed over a session, dropped = eaten + decomposed + in the water. */
data class FoodLedger(
    val dropped: Double = 0.0,
    val eaten: Double = 0.0,
    val decomposed: Double = 0.0,
    /** Handed to another device with the food. */
    val sent: Double = 0.0,
    /** Taken over from another device with the food. */
    val received: Double = 0.0,
    /** Still in the water here. */
    val inWater: Double = 0.0,
) {
    /** What this device has to account for, minus what it accounts for: 0 when nothing is lost. */
    val imbalance: Double get() = dropped + received - eaten - decomposed - sent - inWater

    operator fun plus(o: FoodLedger) = FoodLedger(
        dropped + o.dropped, eaten + o.eaten, decomposed + o.decomposed, sent + o.sent, received + o.received, inWater + o.inWater,
    )
}

/** A remote creature's latest state and how old it is, for dead reckoning. */
data class GhostUpdate(val state: NetCreature, val ageS: Float)

/** A remote piece of food's latest state and how old it is. */
data class FoodUpdate(val food: NetFood, val ageS: Float)

/** This device takes over a creature, with its owner's last state if it has one. */
data class Take(val id: EntityId, val state: NetCreature?)

/**
 * Everything the network changes in the local simulation since the last
 * exchange. Applied in this order: releases, takes, ghosts, food, bites.
 */
data class SimInput(
    val release: List<EntityId> = emptyList(),
    val take: List<Take> = emptyList(),
    val ghosts: List<GhostUpdate> = emptyList(),
    val ghostFood: List<FoodUpdate> = emptyList(),
    val adoptFood: List<NetFood> = emptyList(),
    val releaseFood: List<Long> = emptyList(),
    /** Bites by other devices' creatures on this device's food; answered in [SimOutput.granted], same order. */
    val bites: List<BiteWant> = emptyList(),
    /** What this device's creatures got from other devices' food. */
    val fed: List<Fed> = emptyList(),
    /** The scent patches of the other devices' food (replacing the previous ones), or null to keep them. */
    val scents: List<NetScent>? = null,
    /** Taps on the glass of other devices. */
    val taps: List<Vec2> = emptyList(),
) {
    val isEmpty: Boolean
        get() = release.isEmpty() && take.isEmpty() && ghosts.isEmpty() && ghostFood.isEmpty() && adoptFood.isEmpty() &&
            releaseFood.isEmpty() && bites.isEmpty() && fed.isEmpty() && scents == null && taps.isEmpty()
}

data class SimOutput(
    /** Every creature this device simulates, after the input was applied. */
    val owned: List<NetCreature> = emptyList(),
    /** The last state of each creature released by this exchange, to hand over. */
    val released: List<NetCreature> = emptyList(),
    /** Every piece of food this device owns. */
    val food: List<NetFood> = emptyList(),
    /** The last state of each piece of food released by this exchange, to hand over. */
    val releasedFood: List<NetFood> = emptyList(),
    /** The scent patches of this device's own food. */
    val scents: List<NetScent> = emptyList(),
    /** Bites this device's creatures took at other devices' food since the last exchange. */
    val wants: List<BiteWant> = emptyList(),
    /** The answers to [SimInput.bites], in the same order. */
    val granted: List<Fed> = emptyList(),
    val ledger: FoodLedger = FoodLedger(),
)

/**
 * The local simulation as the network sees it. One call a tick moves
 * everything across, so a native engine pays one thread hop, not dozens.
 */
interface SharedSim {
    suspend fun exchange(input: SimInput): SimOutput
}

private fun r2(v: Float) = (v * 100f).roundToInt() / 100f
private fun r3(v: Float) = (v * 1000f).roundToInt() / 1000f
private fun r4(v: Float) = (v * 10000f).roundToInt() / 10000f
