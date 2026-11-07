package ru.dedseckms.music.desktop

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

fun decodePhoto(bytes: ByteArray): BufferedImage {
    require(bytes.size <= 5_000_000) { "Фото больше 5 МБ" }
    ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("Выбери фотографию")
        try {
            reader.input = input
            val width = reader.getWidth(0); val height = reader.getHeight(0)
            require(width in 1..16384 && height in 1..16384 && width.toLong() * height <= 64_000_000) { "Фото слишком большое" }
            val options = reader.defaultReadParam
            val sample = ((maxOf(width, height) + 1023) / 1024).coerceAtLeast(1)
            options.setSourceSubsampling(sample, sample, 0, 0)
            return reader.read(0, options)
        } finally { reader.dispose() }
    }
}

fun safeCoverGeometry(bytes: ByteArray): Boolean = runCatching {
    ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return false
        try { reader.input = input; reader.getWidth(0) in 1..2048 && reader.getHeight(0) in 1..2048 }
        finally { reader.dispose() }
    }
}.getOrDefault(false)
