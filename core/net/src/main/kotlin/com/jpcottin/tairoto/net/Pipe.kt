package com.jpcottin.tairoto.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * A two-way byte stream between two devices: a TCP socket on a network, an
 * L2CAP channel over Bluetooth, or a pair of pipes in a test. The relay and
 * its links only ever read and write frames on one.
 */
interface Pipe : AutoCloseable {
    val input: InputStream
    val output: OutputStream
    /** Who is at the other end, for logs. */
    val label: String
    /** Closes both ways; a read blocked on [input] ends. */
    override fun close()
}

/** A [Pipe] over a TCP socket. */
class SocketPipe(private val socket: Socket) : Pipe {
    init {
        socket.tcpNoDelay = true
    }

    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()
    override val label: String get() = socket.remoteSocketAddress?.toString() ?: "socket"

    override fun close() {
        try {
            socket.close()
        } catch (e: IOException) {
            // Closing anyway.
        }
    }
}

/** Two [Pipe]s joined end to end in memory: what one writes the other reads. For tests. */
object MemoryPipes {
    fun pair(): Pair<Pipe, Pipe> {
        val ab = Channel()
        val ba = Channel()
        val closeAll = { ab.close(); ba.close() }
        return End(ba.input, ab.output, "memory a", closeAll) to End(ab.input, ba.output, "memory b", closeAll)
    }

    /** One way: chunks written go into a queue, reads take them out in order. */
    private class Channel {
        private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false
        private var current: ByteArray = ByteArray(0)
        private var at = 0

        fun close() {
            closed = true
            queue.offer(ByteArray(0)) // wakes a blocked reader
        }

        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closed) throw IOException("pipe closed")
                if (len > 0) queue.offer(b.copyOfRange(off, off + len))
            }
        }

        val input = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (at >= current.size) {
                    if (closed && queue.isEmpty()) return -1
                    current = queue.take()
                    at = 0
                    if (current.isEmpty() && closed) return -1
                }
                val n = minOf(len, current.size - at)
                System.arraycopy(current, at, b, off, n)
                at += n
                return n
            }
        }
    }

    private class End(
        override val input: InputStream,
        override val output: OutputStream,
        override val label: String,
        private val closeAll: () -> Unit,
    ) : Pipe {
        // A pipe closed at one end is closed at both, like a socket.
        override fun close() = closeAll()
    }
}

/**
 * Frames squeezed with zlib, flushed at each frame boundary: the lagoon's
 * messages are JSON and say the same things over and over, so they shrink
 * several times, which is what makes Bluetooth enough. A compressed stream
 * starts with the zlib header byte [ZLIB_FIRST_BYTE]; a plain one with the
 * first byte of a frame length (0: frames are far below 16 MB), so a relay
 * tells them apart from the first byte and answers in kind.
 */
class CompressedPipe(private val inner: Pipe, level: Int = 5) : Pipe {
    private val deflater = java.util.zip.Deflater(level)
    private val inflater = java.util.zip.Inflater()
    override val output: OutputStream = java.util.zip.DeflaterOutputStream(inner.output, deflater, 8192, true)
    override val input: InputStream = java.util.zip.InflaterInputStream(inner.input, inflater, 8192)
    override val label: String get() = "${inner.label} (compressed)"

    override fun close() {
        inner.close()
        // The streams may still be in use on another thread; the native buffers go with the garbage.
    }

    companion object {
        const val ZLIB_FIRST_BYTE = 0x78
    }
}

/** Counts the bytes that go through a [Pipe], as they go on the wire. */
class CountingPipe(private val inner: Pipe) : Pipe {
    @Volatile var bytesIn = 0L
        private set
    @Volatile var bytesOut = 0L
        private set

    override val input: InputStream = object : java.io.FilterInputStream(inner.input) {
        override fun read(): Int = super.read().also { if (it >= 0) bytesIn++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bytesIn += it }
    }
    override val output: OutputStream = object : java.io.FilterOutputStream(inner.output) {
        override fun write(b: Int) {
            out.write(b)
            bytesOut++
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytesOut += len
        }
    }
    override val label: String get() = inner.label
    override fun close() = inner.close()
}
