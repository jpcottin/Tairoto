package com.jpcottin.tairoto.world

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Writes made-up photos of a coloured-in page, for trying the scanner on an
 * emulator (pushed as photos) and for the README. Only when asked:
 *   ./gradlew :core:world:test --tests '*PagePhotoWriter*' -Dtairoto.photos=<dir>
 */
class PagePhotoWriter {
    @Test fun `write photos of a coloured-in page`() {
        val dir = System.getProperty("tairoto.photos")
        assumeTrue("no -Dtairoto.photos", !dir.isNullOrEmpty())
        val out = File(dir!!).apply { mkdirs() }
        // Held a little askew, as a phone would.
        val w = 1600
        val h = 1200
        val where = listOf(Vec2(150f, 170f), Vec2(1480f, 110f), Vec2(1530f, 1060f), Vec2(110f, 1110f))
        write(PagePhotos.photo(w, h, where, light = 0.95f, paint = PagePhotos::colourful), File(out, "page-colourful.png"))
        write(PagePhotos.photo(w, h, where, light = 0.8f, seed = 4), File(out, "page-stripes.png"))
    }

    private fun write(p: Picture, file: File) {
        val img = BufferedImage(p.width, p.height, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, p.width, p.height, p.argb, 0, p.width)
        ImageIO.write(img, "png", file)
    }
}
