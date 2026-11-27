package com.jpcottin.tairoto.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Forwards frames between the devices of each session. The first device in
 * a session is its host and gets slot 0; the others get 1, 2, 3... in the
 * order they come, never reused within a session. When the host goes the
 * session ends and everyone is let go: the host is the referee of who owns
 * what, and nobody else can take that over.
 *
 * Devices come in over TCP on [port] (null: no TCP at all), or over any
 * other byte stream handed to [serve], such as a Bluetooth channel; one
 * session can mix both. One thread per connection to read, one to write, so
 * a slow device never holds the others up. Meant for a handful of devices on
 * a table.
 */
class RelayServer(
    port: Int? = 0,
    bindTo: InetAddress? = null,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val server: ServerSocket? = port?.let { p ->
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindTo, p))
        }
    }
    /** The TCP port it listens on; -1 without TCP. */
    val port: Int get() = server?.localPort ?: -1

    private val running = AtomicBoolean(true)
    private val lock = Any()
    private val sessions = HashMap<String, Session>()
    private val acceptor = server?.let { Thread({ acceptLoop(it) }, "relay-accept").apply { isDaemon = true; start() } }
    private val open = HashSet<Connection>()

    private class Session(val name: String) {
        val members = LinkedHashMap<Int, Connection>()
        var nextSlot = 1
    }

    private inner class Connection(val raw: Pipe) {
        val out = LinkedBlockingQueue<Frame>()
        /** Set by the reader from the first byte, before anything is written back. */
        @Volatile var pipe: Pipe = raw
        private val opened = java.util.concurrent.CountDownLatch(1)
        var slot = -1
        var name = ""
        var session: Session? = null
        @Volatile var open = true
        /** Frames handed to [send] and not yet flushed onto the wire (taken off [out] is not sent yet). */
        private val unsent = java.util.concurrent.atomic.AtomicInteger(0)

        private val writer = Thread({ writeLoop() }, "relay-write").apply { isDaemon = true }

        fun start() {
            writer.start()
            Thread({ readLoop() }, "relay-read").apply { isDaemon = true; start() }
        }

        fun send(f: Frame) {
            if (!open) return
            unsent.incrementAndGet()
            out.offer(f)
        }

        private fun writeLoop() {
            try {
                // Answers the way the device speaks: compressed or not, as its first byte says.
                while (open && !opened.await(250, TimeUnit.MILLISECONDS)) Unit
                val stream = DataOutputStream(BufferedOutputStream(pipe.output))
                while (open) {
                    val f = out.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    FrameIo.write(stream, f)
                    var n = 1
                    // Write what is queued behind it in the same flush.
                    while (true) {
                        FrameIo.write(stream, out.poll() ?: break)
                        n++
                    }
                    stream.flush()
                    unsent.addAndGet(-n)
                }
            } catch (e: IOException) {
                // The other end went away; the reader notices too.
            } finally {
                close()
            }
        }

        private fun readLoop() {
            try {
                val first = java.io.PushbackInputStream(raw.input, 1)
                val b = first.read()
                if (b < 0) return
                first.unread(b)
                val plain = object : Pipe by raw {
                    override val input: java.io.InputStream = first
                }
                pipe = if (b == CompressedPipe.ZLIB_FIRST_BYTE) CompressedPipe(plain) else plain
                opened.countDown()
                val input = DataInputStream(BufferedInputStream(pipe.input))
                while (open) {
                    val f = FrameIo.read(input) ?: break
                    onFrame(this, f)
                }
            } catch (e: IOException) {
                log("connection ${pipe.label}: ${e.message}")
            } catch (e: IllegalArgumentException) {
                log("connection ${pipe.label}: ${e.message}")
            } finally {
                close()
                left(this)
            }
        }

        fun close() {
            if (!open) return
            open = false
            opened.countDown()
            raw.close()
            synchronized(lock) { this@RelayServer.open.remove(this) }
        }

        /** Lets it go after what is queued has gone out. */
        fun closeSoon() {
            Thread {
                val until = System.currentTimeMillis() + 500
                while (unsent.get() > 0 && System.currentTimeMillis() < until) Thread.sleep(10)
                close()
            }.apply { isDaemon = true; start() }
        }
    }

    /** Takes a device that came in some other way (Bluetooth): from now on it is like any other. */
    fun serve(pipe: Pipe) {
        if (!running.get()) {
            pipe.close()
            return
        }
        val c = Connection(pipe)
        synchronized(lock) { open.add(c) }
        c.start()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val s = try {
                server.accept()
            } catch (e: SocketException) {
                break
            } catch (e: IOException) {
                continue
            }
            serve(SocketPipe(s))
        }
    }

    private fun onFrame(c: Connection, f: Frame) {
        if (c.session == null) {
            if (f.type != Frame.JOIN) throw IOException("expected to join first, got ${f.type}")
            join(c, Frame.json.decodeFromString(JoinRequest.serializer(), f.text))
            return
        }
        if (f.type != Frame.DATA) return
        val session = c.session ?: return
        // The relay stamps who it is from: a device cannot speak for another.
        val stamped = f.copy(from = c.slot)
        val targets = synchronized(lock) {
            if (f.to == Frame.EVERYONE) session.members.values.filter { it !== c } else listOfNotNull(session.members[f.to])
        }
        targets.forEach { it.send(stamped) }
    }

    private fun join(c: Connection, req: JoinRequest) {
        val refuse: String? = synchronized(lock) {
            val existing = sessions[req.session]
            when {
                req.version != PROTOCOL_VERSION -> "this relay speaks version $PROTOCOL_VERSION, not ${req.version}"
                req.host && existing != null -> "there is already a lagoon called ${req.session}"
                !req.host && existing == null -> "no lagoon called ${req.session}"
                else -> {
                    val s = existing ?: Session(req.session).also { sessions[req.session] = it }
                    c.slot = if (req.host) 0 else s.nextSlot++
                    c.name = req.name
                    c.session = s
                    s.members[c.slot] = c
                    null
                }
            }
        }
        if (refuse != null) {
            c.send(Frame.control(Frame.REFUSED, Refused(refuse), Refused.serializer()))
            c.closeSoon()
            return
        }
        val session = c.session!!
        val (members, others) = synchronized(lock) {
            session.members.values.map { Member(it.slot, it.name) } to session.members.values.filter { it !== c }
        }
        log("${req.name} joined ${req.session} as ${c.slot}")
        c.send(Frame.control(Frame.WELCOME, Welcome(c.slot, members), Welcome.serializer()))
        val up = Frame.control(Frame.PEER_UP, Member(c.slot, c.name), Member.serializer())
        others.forEach { it.send(up) }
    }

    private fun left(c: Connection) {
        val session = c.session ?: return
        val (others, ended) = synchronized(lock) {
            if (session.members[c.slot] !== c) return
            session.members.remove(c.slot)
            val ended = c.slot == 0
            if (ended || session.members.isEmpty()) sessions.remove(session.name)
            session.members.values.toList() to ended
        }
        log("${c.name} left ${session.name}${if (ended) ": the lagoon ends" else ""}")
        val down = Frame.control(Frame.PEER_DOWN, PeerDown(c.slot), PeerDown.serializer())
        others.forEach { it.send(down) }
        if (ended) others.forEach { it.closeSoon() }
    }

    /** The sessions and how many devices are in each, for the console and for tests. */
    fun sessions(): Map<String, Int> = synchronized(lock) { sessions.mapValues { it.value.members.size } }

    override fun close() {
        running.set(false)
        try {
            server?.close()
        } catch (e: IOException) {
            // Closing anyway.
        }
        val all = synchronized(lock) { (sessions.values.flatMap { it.members.values } + open).toSet().also { sessions.clear() } }
        all.forEach { it.close() }
        acceptor?.join(1000)
    }
}
