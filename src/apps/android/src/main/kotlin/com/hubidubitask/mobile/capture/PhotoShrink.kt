package com.hubidubitask.mobile.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A camera photo made fit to send to an agent: upright, at most [LONG_EDGE]
 * pixels on its long side, as a JPEG. Claude reads no more detail than that,
 * and a phone's full photo would cost its upload many times over.
 */
internal object PhotoShrink {
    const val LONG_EDGE = 1568

    fun of(file: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        require(longest > 0) { "not a photo" }
        // Decoded at the largest halving still above the size wanted, then scaled down to it.
        var sample = 1
        while (longest / (sample * 2) >= LONG_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("the photo could not be read")
        val scale = minOf(1f, LONG_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
        val turn = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            uprightBy(file)?.let { postRotate(it) }
        }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, turn, true)
        if (upright !== decoded) decoded.recycle()
        return ByteArrayOutputStream().use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, 85, out)
            upright.recycle()
            out.toByteArray()
        }
    }

    /** How far the camera's own note says to turn the photo, or null when it stands as taken. */
    private fun uprightBy(file: File): Float? =
        when (runCatching { ExifInterface(file.path) }.getOrNull()
            ?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> null
        }
}
