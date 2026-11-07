package ru.dedseckms.music.data

import java.io.IOException

class UploadRejected(message: String) : IOException(message)

/** Mirrors server limits for early feedback; the server must validate untrusted uploads independently. */
object UploadPolicy {
    const val MAX_BYTES = 100_000_000L
    const val MAX_DURATION_MS = 20 * 60 * 1000L
    fun checkBytes(bytes: Long) {
        if (bytes > MAX_BYTES) throw UploadRejected("Файл больше 100 МБ")
    }
    fun validate(name: String, size: Long, durationMs: Long, hasVideo: Boolean) {
        UploadApi.safeFilename(name)
        checkBytes(size)
        if (hasVideo || durationMs <= 0) throw UploadRejected("Нужен настоящий аудиофайл без видео")
        if (durationMs > MAX_DURATION_MS) throw UploadRejected("Запись длиннее 20 минут")
    }
}
