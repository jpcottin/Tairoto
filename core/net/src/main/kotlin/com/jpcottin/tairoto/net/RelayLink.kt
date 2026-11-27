package com.jpcottin.tairoto.net

import com.jpcottin.tairoto.world.DeviceId
import com.jpcottin.tairoto.world.Envelope
import com.jpcottin.tairoto.world.Link
import com.jpcottin.tairoto.world.Msg
import com.jpcottin.tairoto.world.Wire
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** How a link to a relay is doing. */
sealed interface LinkState {
    data object Connecting : LinkState
    data class Connected(val self: DeviceId) : LinkState
    /** Over: the relay could not be reached, said no, or the connection dropped. */
    data class Closed(val reason: String) : LinkState
}

/**
 * A device's link through a relay: to emulators on a computer, or to the host
 * device nearby (which runs the relay itself), over the network or over
 * Bluetooth. Make one with [connect] or [over]; it is ready when it returns.
 */
class RelayLink private constructor(
    private val pipe: Pipe,
    private val wire: CountingPipe,
    override val self: DeviceId,
    members: List<Member>,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    io: CoroutineDispatcher,
) : Link, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val inbox = Channel<Envelope>(Channel.UNLIMITED)
    private val outbox = Channel<Frame>(Channel.UNLIMITED)
    private val names = HashMap<Int, String>().apply { members.forEach { put(it.slot, it.name) } }
    private val peerState = MutableStateFlow(members.map { DeviceId(it.slot, it.name) }.sortedBy { it.slot })
    private val linkState = MutableStateFlow<LinkState>(LinkState.Connected(self))

    override val peers: StateFlow<List<DeviceId>> = peerState.asStateFlow()
    override val incoming: Flow<Envelope> = inbox.receiveAsFlow()
    val state: StateFlow<LinkState> = linkState.asStateFlow()

    /** Bytes received and sent on the wire (after compression). */
    val bytesIn: Long get() = wire.bytesIn
    val bytesOut: Long get() = wire.bytesOut

    init {
        scope.launch { readLoop() }
        scope.launch { writeLoop() }
    }

    override suspend fun send(msg: Msg, to: DeviceId?) {
        if (linkState.value is LinkState.Closed || outbox.isClosedForSend) return
        val text = Wire.encode(msg)
        // A broadcast reaches this device too, as on every link; the relay sends it to the others.
        if (to == null || to.slot == self.slot) inbox.trySend(Envelope(self, to, Wire.decode(text)))
        if (to?.slot == self.slot) return
        outbox.trySend(Frame.data(self.slot, to?.slot ?: Frame.EVERYONE, text))
    }

    private suspend fun writeLoop() {
        try {
            // Until closed, and then what was queued before closing (a goodbye) still goes out.
            for (f in outbox) {
                FrameIo.write(output, f)
                // What is queued behind it goes in the same flush.
                while (true) {
                    val next = outbox.tryReceive().getOrNull() ?: break
                    FrameIo.write(output, next)
                }
                output.flush()
            }
            closed("closed")
        } catch (e: IOException) {
            closed("the connection dropped: ${e.message}")
        }
    }

    private fun readLoop() {
        try {
            while (true) {
                val f = FrameIo.read(input) ?: break
                when (f.type) {
                    Frame.DATA -> {
                        val msg = try {
                            Wire.decode(f.text)
                        } catch (e: SerializationException) {
                            continue // something this version does not know
                        } catch (e: IllegalArgumentException) {
                            continue
                        }
                        val from = DeviceId(f.from, names[f.from] ?: "device ${f.from}")
                        inbox.trySend(Envelope(from, if (f.to == Frame.EVERYONE) null else self, msg))
                    }
                    Frame.PEER_UP -> {
                        val m = Frame.json.decodeFromString(Member.serializer(), f.text)
                        names[m.slot] = m.name
                        peerState.value = (peerState.value.filter { it.slot != m.slot } + DeviceId(m.slot, m.name)).sortedBy { it.slot }
                    }
                    Frame.PEER_DOWN -> {
                        val d = Frame.json.decodeFromString(PeerDown.serializer(), f.text)
                        peerState.value = peerState.value.filter { it.slot != d.slot }
                    }
                }
            }
            closed("the relay closed the connection")
        } catch (e: IOException) {
            closed("the connection dropped: ${e.message}")
        }
    }

    private fun closed(reason: String) {
        if (linkState.value is LinkState.Closed) return
        linkState.value = LinkState.Closed(reason)
        peerState.value = listOf(self)
        pipe.close()
    }

    /** Closes the link once what was already sent has gone out (or after a second, whatever is left). */
    override fun close() {
        outbox.close()
        scope.launch {
            kotlinx.coroutines.delay(1_000)
            closed("closed")
            scope.cancel()
        }
    }

    companion object {
        const val DEFAULT_PORT = 7171
        /** The computer an emulator runs on, as the emulator sees it. */
        const val EMULATOR_HOST = "10.0.2.2"

        /**
         * Connects to a relay and asks into [session], as its host or to join
         * it. Throws [RelayRefused] if the relay says no, [IOException] if it
         * cannot be reached.
         */
        suspend fun connect(
            host: String,
            port: Int,
            session: String,
            name: String,
            asHost: Boolean,
            timeoutMs: Int = 5_000,
            io: CoroutineDispatcher = Dispatchers.IO,
            compress: Boolean = true,
        ): RelayLink = withContext(io) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (e: Exception) {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
                throw e
            }
            over(SocketPipe(socket), session, name, asHost, timeoutMs, io, compress)
        }

        /**
         * Asks into [session] over a byte stream already open to a relay
         * (a Bluetooth channel to the host). Closes [pipe] and throws if the
         * relay says no or does not answer within [timeoutMs]. With
         * [compress] the frames go squeezed (see [CompressedPipe]); a relay
         * notices and answers the same way.
         */
        suspend fun over(
            raw: Pipe,
            session: String,
            name: String,
            asHost: Boolean,
            timeoutMs: Int = 5_000,
            io: CoroutineDispatcher = Dispatchers.IO,
            compress: Boolean = true,
        ): RelayLink = withContext(io) {
            val wire = CountingPipe(raw)
            val pipe: Pipe = if (compress) CompressedPipe(wire) else wire
            // A stream has no timeout of its own: closing it ends a read that waits too long.
            val done = java.util.concurrent.CountDownLatch(1)
            val watchdog = Thread({
                if (!done.await(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)) pipe.close()
            }, "relay-handshake").apply { isDaemon = true; start() }
            try {
                val input = DataInputStream(BufferedInputStream(pipe.input))
                val output = DataOutputStream(BufferedOutputStream(pipe.output))
                FrameIo.write(output, Frame.control(Frame.JOIN, JoinRequest(session, name, asHost), JoinRequest.serializer()))
                output.flush()
                val answer = try {
                    FrameIo.read(input)
                } catch (e: IOException) {
                    if (done.count > 0 && !watchdog.isAlive) throw IOException("the relay did not answer in ${timeoutMs}ms")
                    throw e
                } ?: throw IOException("the relay hung up")
                done.countDown()
                when (answer.type) {
                    Frame.WELCOME -> {
                        val w = Frame.json.decodeFromString(Welcome.serializer(), answer.text)
                        RelayLink(pipe, wire, DeviceId(w.slot, name), w.members, input, output, io)
                    }
                    Frame.REFUSED -> throw RelayRefused(Frame.json.decodeFromString(Refused.serializer(), answer.text).reason)
                    else -> throw IOException("unexpected answer ${answer.type}")
                }
            } catch (e: Exception) {
                done.countDown()
                pipe.close()
                throw e
            }
        }
    }
}

class RelayRefused(reason: String) : IOException(reason)
