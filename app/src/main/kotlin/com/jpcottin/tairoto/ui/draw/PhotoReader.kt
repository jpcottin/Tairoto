package com.jpcottin.tairoto.ui.draw

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.jpcottin.tairoto.world.Picture

/** Opens a photo for the scanner: upright (as the camera meant it), at most [MAX_SIDE] pixels on its long side. */
object PhotoReader {
    const val MAX_SIDE = 2000

    fun read(resolver: ContentResolver, uri: Uri): Picture {
        val source = ImageDecoder.createSource(resolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val long = maxOf(w, h)
            if (long > MAX_SIDE) decoder.setTargetSize(w * MAX_SIDE / long, h * MAX_SIDE / long)
        }
        return toPicture(bitmap)
    }

    fun toPicture(bitmap: Bitmap): Picture {
        val b = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Picture(b.width, b.height, px)
    }
}
