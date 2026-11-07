package ru.dedseckms.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class ProfileStore(context: Context, account: String) {
    private val directory = File(context.filesDir, "profiles/$account").apply { mkdirs() }
    val avatar = File(directory, "avatar.jpg")
    private val versionFile = File(directory, "version")
    fun version(): String = runCatching { versionFile.readText() }.getOrDefault("")
    fun save(bytes: ByteArray, version: String) {
        val temporary = File(directory, "avatar.pending")
        try { temporary.writeBytes(bytes); check(temporary.renameTo(avatar)); versionFile.writeText(version) }
        finally { temporary.delete() }
    }
    fun clear() { avatar.delete(); versionFile.delete() }
}

/** Decode sampled pixels, respect camera orientation, then publish a small square JPEG without metadata. */
internal fun prepareAvatar(context: Context, uri: Uri): ByteArray {
    val bytes = readBounded(context.contentResolver.openInputStream(uri) ?: throw IOException("Фото недоступно"), 20_000_000)
    val decoded = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            if (info.size.width <= 0 || info.size.height <= 0 || maxOf(info.size.width, info.size.height) > 16384) throw IOException("Изображение слишком большое")
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize((maxOf(info.size.width, info.size.height) / 1024).coerceAtLeast(1))
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || maxOf(bounds.outWidth, bounds.outHeight) > 16384) throw IOException("Выбери обычную фотографию")
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("Не удалось прочитать фото")
        val orientation = runCatching { android.media.ExifInterface(bytes.inputStream()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = android.graphics.Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f); 3 -> matrix.setRotate(180f); 4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f); 7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(-90f)
        }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }
    val square = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    try {
        val side = minOf(decoded.width, decoded.height)
        val left = (decoded.width - side) / 2; val top = (decoded.height - side) / 2
        Canvas(square).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(decoded, Rect(left, top, left + side, top + side), Rect(0, 0, 512, 512), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)) }
        return ByteArrayOutputStream().use { square.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
    } finally { square.recycle(); decoded.recycle() }
}
