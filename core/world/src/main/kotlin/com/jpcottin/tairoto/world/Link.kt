package com.jpcottin.tairoto.world

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything devices say to each other. */
@Serializable
sealed interface Msg {
    @Serializable @SerialName("hello")
    data class Hello(val device: DeviceId, val screen: ScreenMm) : Msg

    @Serializable @SerialName("spawn")
    data class Spawn(val creature: CreatureSpec, val owner: DeviceId) : Msg

    @Serializable @SerialName("remove")
    data class Remove(val id: EntityId) : Msg

    /**
     * [from] offers a creature to [to] at [epoch]: it has stopped simulating
     * it, and [state] is where it left it at shared time [t].
     */
    @Serializable @SerialName("handoff")
    data class Handoff(
        val id: EntityId,
        val from: DeviceId,
        val to: DeviceId,
        val epoch: Int,
        val state: NetCreature? = null,
        @SerialName("ts") val t: Double = 0.0,
    ) : Msg

    @Serializable @SerialName("claim")
    data class Claim(val id: EntityId, val by: DeviceId, val epoch: Int) : Msg

    @Serializable @SerialName("owner")
    data class Owner(val id: EntityId, val owner: DeviceId, val epoch: Int) : Msg

    @Serializable @SerialName("reset")
    data class Reset(val scene: TankScene) : Msg

    @Serializable @SerialName("place")
    data class PlaceDecor(val decor: DecorSpec) : Msg

    @Serializable @SerialName("move")
    data class MoveDecor(val id: EntityId, val at: Vec2, val angle: Float) : Msg

    @Serializable @SerialName("unplace")
    data class RemoveDecor(val id: EntityId) : Msg

    // ---- a lagoon shared by several devices

    /** The host welcomes a device: the lagoon as it is, who owns what, and where every screen lies. */
    @Serializable @SerialName("join")
    data class Join(val scene: TankScene, val owners: List<OwnerEntry>, val layout: Layout, @SerialName("ts") val t: Double) : Msg

    /** Where every screen lies now (a device joined or left). */
    @Serializable @SerialName("layout")
    data class LayoutChanged(val layout: Layout) : Msg

    /** A device asks the host the time; the answer also renews its lease on what it simulates. */
    @Serializable @SerialName("ping")
    data class Ping(val sent: Double) : Msg

    @Serializable @SerialName("pong")
    data class Pong(val sent: Double, val host: Double) : Msg

    /** The creatures a device simulates, as they were at shared time [t]. */
    @Serializable @SerialName("states")
    data class States(@SerialName("ts") val t: Double, val creatures: List<NetCreature>) : Msg

    /** The food a device owns, as it was at shared time [t]. */
    @Serializable @SerialName("foods")
    data class Foods(@SerialName("ts") val t: Double, val food: List<NetFood>) : Msg

    /** A piece of food drifted onto [to]'s screen: it is [to]'s now. */
    @Serializable @SerialName("food_handoff")
    data class FoodHandoff(@SerialName("ts") val t: Double, val food: NetFood, val to: DeviceId) : Msg

    /** The scent of a device's food, for fish on other screens to follow. */
    @Serializable @SerialName("scents")
    data class Scents(val scents: List<NetScent>) : Msg

    /** A creature took a bite of food another device owns; that device decides how much it got. */
    @Serializable @SerialName("bite")
    data class Bite(val want: BiteWant) : Msg

    /** What a bite got; passed on (counting [hops]) if the creature changed hands meanwhile. */
    @Serializable @SerialName("fed")
    data class FedMsg(val fed: Fed, val hops: Int = 0) : Msg

    /** A tap on the glass, so fish near the edge on the next screen dart away too. */
    @Serializable @SerialName("tap")
    data class Tap(val at: Vec2) : Msg

    /** A device leaves: it has stopped simulating everything. */
    @Serializable @SerialName("bye")
    data class Bye(val device: DeviceId) : Msg

    /** The host to a device it gave up on: everything it had has been taken over. */
    @Serializable @SerialName("gone")
    data class Gone(val device: DeviceId) : Msg
}

/** Who owns a creature, for a device joining a session. */
@Serializable
data class OwnerEntry(val id: EntityId, val owner: DeviceId, val epoch: Int)

/** One message as it arrives: who sent it, and to whom (null = everyone). */
data class Envelope(val from: DeviceId, val to: DeviceId?, val msg: Msg)

/**
 * A way to reach the other devices of a session. The ownership and handoff
 * logic sits above this and is the same whatever carries the bytes: loopback
 * on a device on its own, Wi-Fi, Wi-Fi Direct, Bluetooth or a relay later.
 */
interface Link {
    val self: DeviceId
    val peers: StateFlow<List<DeviceId>>
    val incoming: Flow<Envelope>

    /** Sends [msg] to [to], or to every device including this one when null. */
    suspend fun send(msg: Msg, to: DeviceId? = null)
}

/** The wire format, shared by every transport. */
object Wire {
    val json = Json {
        classDiscriminator = "t"
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    fun encode(msg: Msg): String = json.encodeToString(Msg.serializer(), msg)
    fun decode(text: String): Msg = json.decodeFromString(Msg.serializer(), text)
}

/**
 * A session of one: what is sent comes straight back. Messages still go
 * through the wire format, so a message that would not survive a real network
 * fails here first.
 */
class LoopbackLink(override val self: DeviceId = DeviceId.Solo) : Link {
    private val flow = MutableSharedFlow<Envelope>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    private val peerState = MutableStateFlow(listOf(self))

    override val peers: StateFlow<List<DeviceId>> = peerState.asStateFlow()
    override val incoming: Flow<Envelope> = flow.asSharedFlow()

    var sent: Int = 0
        private set

    override suspend fun send(msg: Msg, to: DeviceId?) {
        if (to != null && to != self) return // nobody else is here
        sent++
        flow.emit(Envelope(self, to, Wire.decode(Wire.encode(msg))))
    }
}
