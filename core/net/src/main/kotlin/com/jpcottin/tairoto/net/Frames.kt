package com.jpcottin.tairoto.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * What goes over a relay connection: a length, a type, who from, who to,
 * then the payload. The relay reads only the header; the payload of a
 * [DATA] frame is the session's own message, which it passes on untouched.
 *
 *     u32 length (of what follows) | u8 type | u16 from | u16 to | payload
 */
data class Frame(val type: Int, val from: Int, val to: Int, val payload: ByteArray) {
    val text: String get() = payload.decodeToString()

    override fun equals(other: Any?): Boolean =
        other is Frame && type == other.type && from == other.from && to == other.to && payload.contentEquals(other.payload)

    override fun hashCode(): Int = ((type * 31 + from) * 31 + to) * 31 + payload.contentHashCode()

    companion object {
        const val JOIN = 1
        const val WELCOME = 2
        const val PEER_UP = 3
        const val PEER_DOWN = 4
        const val DATA = 5
        const val REFUSED = 6

        /** To every other device of the session. */
        const val EVERYONE = 0xFFFF
        const val RELAY = 0xFFFE

        const val HEADER = 5
        /** A whole lagoon with every creature fits many times over; anything bigger is a broken stream. */
        const val MAX_LENGTH = 8 shl 20

        fun data(from: Int, to: Int, text: String) = Frame(DATA, from, to, text.encodeToByteArray())

        fun <T> control(type: Int, body: T, serializer: kotlinx.serialization.KSerializer<T>) =
            Frame(type, RELAY, RELAY, json.encodeToString(serializer, body).encodeToByteArray())

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** A device asking in: which session, under what name, as its host or to join it. */
@Serializable
data class JoinRequest(val session: String, val name: String, val host: Boolean, val version: Int = PROTOCOL_VERSION)

@Serializable
data class Member(val slot: Int, val name: String)

/** The relay's answer: the slot this device has, and who else is there. */
@Serializable
data class Welcome(val slot: Int, val members: List<Member>)

@Serializable
data class Refused(val reason: String)

@Serializable
data class PeerDown(val slot: Int)

const val PROTOCOL_VERSION = 1

object FrameIo {
    fun write(out: DataOutputStream, f: Frame) {
        require(f.payload.size + Frame.HEADER <= Frame.MAX_LENGTH) { "frame too big: ${f.payload.size}" }
        out.writeInt(f.payload.size + Frame.HEADER)
        out.writeByte(f.type)
        out.writeShort(f.from)
        out.writeShort(f.to)
        out.write(f.payload)
    }

    /** The next frame, or null at the end of the stream. Throws on a stream that makes no sense. */
    fun read(input: DataInputStream): Frame? {
        val length = try {
            input.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (length < Frame.HEADER || length > Frame.MAX_LENGTH) throw IOException("bad frame length $length")
        val type = input.readUnsignedByte()
        val from = input.readUnsignedShort()
        val to = input.readUnsignedShort()
        val payload = ByteArray(length - Frame.HEADER)
        input.readFully(payload)
        return Frame(type, from, to, payload)
    }
}
