package com.thelightphone.music.art

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** [ArtCodec] over the platform bitmap codecs; plain `android.graphics`, nothing the plugin blocks. */
object AndroidArtCodec : ArtCodec {
    override fun decode(bytes: ByteArray): Gray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        bitmap.recycle()
        // Rec. 601 luma, the usual "what gray is this colour" weighting.
        val luma = IntArray(w * h) { i ->
            val p = argb[i]
            (((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000)
        }
        return Gray(w, h, luma)
    }

    override fun encodePng(image: Gray): ByteArray {
        val argb = IntArray(image.pixels.size) { i ->
            val v = image.pixels[i]
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bitmap = Bitmap.createBitmap(argb, image.width, image.height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
