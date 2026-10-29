package dev.apollo.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A photo he attaches in chat goes up smaller: the long edge at most 1568 px (what the models look at anyway), JPEG at
 * quality 85, re-encoded so no EXIF (location, camera, time) leaves the phone. The original stays on the phone.
 */
internal object PhotoShrink {
    const val LONG_EDGE = 1568
    const val QUALITY = 85
    private val STILL = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")

    /** The size it goes up at: the long edge at most [max], the shape kept, never made bigger. Pure. */
    fun target(w: Int, h: Int, max: Int = LONG_EDGE): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= max) return w to h
        val s = max.toDouble() / long
        return maxOf(1, Math.round(w * s).toInt()) to maxOf(1, Math.round(h * s).toInt())
    }

    /** The biggest power-of-two decode step that still leaves the long edge at least [max] (no blurry upscale after). Pure. */
    fun sample(w: Int, h: Int, max: Int = LONG_EDGE): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= max) s *= 2
        return s
    }

    /** EXIF orientation as degrees to turn (the re-encode drops the tag, so the pixels turn instead). Pure. */
    fun degrees(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    /** photo.HEIC -> photo.jpg. Pure. */
    fun jpgName(name: String): String = name.substringBeforeLast('.').ifEmpty { "photo" } + ".jpg"

    /** (bytes, name, mime) to upload: a still photo shrunk; anything else (a GIF, a PDF) or one that won't decode as it is. */
    fun shrink(bytes: ByteArray, name: String, mime: String): Triple<ByteArray, String, String> {
        val asIs = Triple(bytes, name, mime)
        if (mime.lowercase() !in STILL) return asIs
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return asIs
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight) })
            ?: return asIs
        val (w, h) = target(raw.width, raw.height)
        var bmp = if (w != raw.width || h != raw.height) Bitmap.createScaledBitmap(raw, w, h, true) else raw
        val turn = runCatching { degrees(ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) }.getOrDefault(0)
        if (turn != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
        val out = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) return asIs
        return Triple(out.toByteArray(), jpgName(name), "image/jpeg")
    }
}
