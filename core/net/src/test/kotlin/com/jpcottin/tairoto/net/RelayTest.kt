package com.jpcottin.tairoto.net

import com.jpcottin.tairoto.world.DeviceId
import com.jpcottin.tairoto.world.EntityId
import com.jpcottin.tairoto.world.Envelope
import com.jpcottin.tairoto.world.Msg
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

/** The relay and its link over real sockets on this machine. */
class RelayTest {
    private val relay = RelayServer(0)
    private val links = ArrayList<RelayLink>()

    @After fun tearDown() {
        links.forEach { it.close() }
        relay.close()
    }

    private suspend fun connect(name: String, host: Boolean = false, session: String = "lagoon") =
        RelayLink.connect("127.0.0.1", relay.port, session, name, host).also { links += it }

    private suspend fun RelayLink.next(): Envelope = withTimeout(3_000) { incoming.first() }

    @Test fun `the host gets slot 0 and the others come after, in order`() = runBlocking {
        val host = connect("tablet", host = true)
        val a = connect("phone")
        val b = connect("fold")
        assertEquals(DeviceId(0, "tablet"), host.self)
        assertEquals(1, a.self.slot)
        assertEquals(2, b.self.slot)
        assertEquals(listOf(0, 1, 2), b.peers.value.map { it.slot })
        withTimeout(3_000) { host.peers.first { it.size == 3 } }
        assertEquals(listOf("tablet", "phone", "fold"), host.peers.value.map { it.name })
        assertEquals(mapOf("lagoon" to 3), relay.sessions())
    }

    @Test fun `a message goes to one device, or to all of them and back to the sender`() = runBlocking {
        val host = connect("host", host = true)
        val a = connect("a")
        val b = connect("b")
        a.send(Msg.Remove(EntityId(1)), to = b.self)
        val got = b.next()
        assertEquals(Msg.Remove(EntityId(1)), got.msg)
        assertEquals(a.self, got.from)
        host.send(Msg.Remove(EntityId(2)))
        for (l in listOf(host, a, b)) {
            val e = l.next()
            assertEquals(Msg.Remove(EntityId(2)), e.msg)
            assertEquals(0, e.from.slot)
        }
    }

    @Test fun `many messages arrive all and in order`() = runBlocking {
        connect("host", host = true)
        val a = connect("a")
        val b = connect("b")
        repeat(2_000) { a.send(Msg.Remove(EntityId(it.toLong())), to = b.self) }
        val got = withTimeout(10_000) {
            val ids = ArrayList<Long>()
            b.incoming.first { ids += (it.msg as Msg.Remove).id.raw; ids.size == 2_000 }
            ids
        }
        assertEquals((0L until 2_000L).toList(), got)
    }

    @Test fun `a device leaving is seen by the others`() = runBlocking {
        val host = connect("host", host = true)
        val a = connect("a")
        withTimeout(3_000) { host.peers.first { it.size == 2 } }
        a.close()
        withTimeout(3_000) { host.peers.first { it.size == 1 } }
        assertEquals(listOf(0), host.peers.value.map { it.slot })
    }

    @Test fun `when the host goes the lagoon ends for everyone`() = runBlocking {
        val host = connect("host", host = true)
        val a = connect("a")
        host.close()
        withTimeout(3_000) { a.state.first { it is LinkState.Closed } }
        assertTrue(relay.sessions().isEmpty())
        // And the name is free for a new one.
        connect("new host", host = true)
        Unit
    }

    @Test fun `joining a lagoon that is not there, or hosting one that is, is refused`() = runBlocking {
        try {
            connect("a")
            fail("joined nothing")
        } catch (e: RelayRefused) {
            assertTrue(e.message!!.contains("no lagoon"))
        }
        connect("host", host = true)
        try {
            connect("other host", host = true)
            fail("two hosts")
        } catch (e: RelayRefused) {
            assertTrue(e.message!!.contains("already"))
        }
    }

    @Test fun `sessions are kept apart`() = runBlocking {
        val h1 = connect("h1", host = true, session = "one")
        val h2 = connect("h2", host = true, session = "two")
        val a = connect("a", session = "one")
        h1.send(Msg.Remove(EntityId(7)))
        assertEquals(Msg.Remove(EntityId(7)), a.next().msg)
        assertEquals(Msg.Remove(EntityId(7)), h1.next().msg)
        h2.send(Msg.Remove(EntityId(8)), to = DeviceId(1, "a"))
        // h2 has no slot 1 in its session: nothing reaches a.
        h1.send(Msg.Remove(EntityId(9)), to = a.self)
        assertEquals(Msg.Remove(EntityId(9)), a.next().msg)
    }

    @Test fun `a device cannot speak for another`() = runBlocking {
        val host = connect("host", host = true)
        // A raw client that joins, then sends a frame claiming to be the host.
        Socket("127.0.0.1", relay.port).use { s ->
            val out = DataOutputStream(s.getOutputStream())
            val input = DataInputStream(s.getInputStream())
            FrameIo.write(out, Frame.control(Frame.JOIN, JoinRequest("lagoon", "liar", false), JoinRequest.serializer()))
            out.flush()
            assertEquals(Frame.WELCOME, FrameIo.read(input)!!.type)
            FrameIo.write(out, Frame.data(from = 0, to = 0, text = """{"t":"remove","id":3}"""))
            out.flush()
            val got = host.next()
            assertEquals(1, got.from.slot)
        }
    }

    @Test fun `garbage on a connection closes it and nothing else`() = runBlocking {
        val host = connect("host", host = true)
        Socket("127.0.0.1", relay.port).use { s ->
            val out = s.getOutputStream()
            out.write(byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f, 1, 2, 3))
            out.flush()
            assertEquals(-1, s.getInputStream().read())
        }
        val a = connect("a")
        a.send(Msg.Remove(EntityId(5)), to = host.self)
        assertEquals(Msg.Remove(EntityId(5)), host.next().msg)
    }

    @Test fun `a relay that is not there fails fast`() = runBlocking {
        val port = relay.port
        relay.close()
        try {
            RelayLink.connect("127.0.0.1", port, "x", "a", true, timeoutMs = 1_000)
            fail("connected to nothing")
        } catch (e: IOException) {
            // Refused: as it should.
        }
    }
}
