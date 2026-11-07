package ru.dedseckms.music.data

import java.io.IOException
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject

data class ServerDisk(val used: Long, val total: Long) { val free: Long get() = (total - used).coerceAtLeast(0) }

/** Quantum receives a separate, user-configured login. No Navidrome password is forwarded implicitly. */
class UploadApi(private val credentials: Credentials, private val client: OkHttpClient = NavApi.httpClient()) {
    private val base = ServerAddress.normalize(credentials.server).toHttpUrl()
    private var token: String? = null
    private fun path(value: String) = base.newBuilder().addPathSegments(value)
    fun login() {
        val password = URLEncoder.encode(credentials.password, "UTF-8").replace("+", "%20")
        val request = Request.Builder().url(path("api/auth/login").addQueryParameter("username", credentials.username).build())
            .header("X-Password", password).post(ByteArray(0).toRequestBody()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(when (response.code) {
                401, 403 -> "Проверь отдельный аккаунт загрузки Quantum"
                429 -> "Слишком много попыток входа. Попробуй позже"
                else -> "Загрузчик недоступен (HTTP ${response.code})"
            })
            val value = response.body?.string()?.trim()?.trim('"').orEmpty()
            if (value.split('.').size != 3) throw IOException("Загрузчик вернул неожиданный ответ")
            token = value
        }
    }
    private fun authorized(url: okhttp3.HttpUrl) = Request.Builder().url(url)
        .header("Authorization", "Bearer ${token ?: throw IOException("Войди в аккаунт загрузки")}")
    fun disk(): ServerDisk? {
        client.newCall(authorized(path("api/settings/sources").build()).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val data = JSONObject(response.body?.string().orEmpty())
            val source = data.optJSONObject("Uploads") ?: return null
            return ServerDisk(source.optLong("usedAlt"), source.optLong("total"))
        }
    }
    fun upload(filename: String, length: Long, open: () -> java.io.InputStream, progress: (Long, Long) -> Unit) {
        val safe = safeFilename(filename)
        UploadPolicy.checkBytes(length)
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = length.takeIf { it >= 0 } ?: -1
            override fun writeTo(sink: BufferedSink) {
                open().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        UploadPolicy.checkBytes(sent + count)
                        sink.write(buffer, 0, count)
                        sent += count
                        progress(sent, length)
                    }
                }
            }
        }
        val url = path("api/resources").addQueryParameter("source", "Uploads")
            .addQueryParameter("path", "/$safe").addQueryParameter("override", "false").build()
        client.newCall(authorized(url).post(body).build()).execute().use { response ->
            if (response.code == 413) throw UploadRejected("Файл больше 100 МБ")
            if (response.code == 415) throw UploadRejected("Нужен настоящий аудиофайл без видео")
            if (response.code == 422) throw UploadRejected("Аудиофайл не прошёл проверку. Предел — 20 минут")
            if (!response.isSuccessful) throw IOException(when (response.code) {
                409 -> "Файл с таким именем уже есть. Переименуй его и повтори"
                401, 403 -> "Нет доступа к загрузке. Проверь аккаунт Quantum"
                507 -> "На сервере недостаточно места"
                else -> "Не удалось загрузить файл (HTTP ${response.code})"
            })
        }
    }
    companion object {
        fun safeFilename(value: String): String {
            val name = value.substringAfterLast('/').substringAfterLast('\\').trim().take(180)
            if (name.isBlank() || name in setOf(".", "..") || name.startsWith('.') || name.any { it.code < 32 }) throw IOException("Недопустимое имя файла")
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension !in setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "aiff", "wma")) throw IOException("Выбери музыкальный аудиофайл")
            return name
        }
    }
}
