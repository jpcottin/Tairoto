package com.jpcottin.tairoto.world

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A fish someone drew: its colours as they lie on the printed outline, [WIDTH]
 * by [HEIGHT] pixels of RGBA (row by row, x from the snout to the tail tip, y
 * from one side of the fish to the other), alpha 0 outside the outline.
 *
 * It travels inside the lagoon (saved with it, sent to the other screens) as
 * deflated bytes in Base64, so a drawing is some ten kilobytes, and its [id]
 * is a hash of them: the same drawing has the same id on every device.
 */
@Serializable
data class Skin(val id: String, val data: String) {
    init {
        require(id.matches(Regex("[0-9a-f]{16}"))) { "a skin id is 16 hex digits: $id" }
        require(data.length <= MAX_DATA) { "a skin is at most $MAX_DATA characters" }
    }

    /** The pixels, or null when the data is damaged (a newer or broken file): such a fish is left out. */
    fun pixels(): ByteArray? = try {
        val packed = Base64.getDecoder().decode(data)
        val inflater = Inflater()
        inflater.setInput(packed)
        val out = ByteArray(BYTES)
        var n = 0
        while (n < BYTES && !inflater.finished()) {
            val got = inflater.inflate(out, n, BYTES - n)
            if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            n += got
        }
        inflater.end()
        if (n == BYTES) out else null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: DataFormatException) {
        null
    }

    /** The data is what its id says, so a skin cannot be swapped for another under the same id. */
    val isIntact: Boolean get() = idOf(data) == id && pixels() != null

    companion object {
        const val WIDTH = 128
        const val HEIGHT = 64
        const val BYTES = WIDTH * HEIGHT * 4
        /** Far more than a coloured-in fish takes (noise compresses badly, so allow for a rough photo). */
        const val MAX_DATA = 64 * 1024

        /** A skin of these RGBA pixels ([BYTES] of them). */
        fun of(rgba: ByteArray): Skin {
            require(rgba.size == BYTES) { "a skin is $WIDTH x $HEIGHT RGBA" }
            // Colour under a fully clear pixel is never seen: zero it, so it costs nothing to send.
            val clean = rgba.copyOf()
            for (i in 0 until WIDTH * HEIGHT) {
                if (clean[i * 4 + 3].toInt() == 0) {
                    clean[i * 4] = 0
                    clean[i * 4 + 1] = 0
                    clean[i * 4 + 2] = 0
                }
            }
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            deflater.setInput(clean)
            deflater.finish()
            val buf = ByteArray(8192)
            val out = ByteArrayOutputStream()
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            deflater.end()
            val data = Base64.getEncoder().encodeToString(out.toByteArray())
            return Skin(idOf(data), data)
        }

        fun idOf(data: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.US_ASCII))
            return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
