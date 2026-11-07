package ru.dedseckms.music.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/** Keep system-session metadata small even when the shared cache contains a large PNG cover. */
internal object SessionArtwork {
    fun encode(file: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || maxOf(bounds.outWidth, bounds.outHeight) > 8192) return null
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / decodeOptions.inSampleSize > 384) decodeOptions.inSampleSize *= 2
        val original = BitmapFactory.decodeFile(file.path, decodeOptions) ?: return null
        var thumbnail = original
        return try {
            val ratio = minOf(1f, 192f / maxOf(original.width, original.height))
            if (ratio < 1) thumbnail = Bitmap.createScaledBitmap(original, (original.width * ratio).toInt().coerceAtLeast(1), (original.height * ratio).toInt().coerceAtLeast(1), true)
            ByteArrayOutputStream().use { output ->
                if (!thumbnail.compress(Bitmap.CompressFormat.PNG, 100, output)) null
                else output.toByteArray().takeIf { it.size <= 256 * 1024 }
            }
        } finally {
            if (thumbnail !== original) thumbnail.recycle()
            original.recycle()
        }
    }
}
