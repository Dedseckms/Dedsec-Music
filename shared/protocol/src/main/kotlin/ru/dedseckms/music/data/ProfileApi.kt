package ru.dedseckms.music.data

import java.io.IOException
import java.io.InputStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import kotlinx.coroutines.*
import okio.buffer
import java.util.concurrent.TimeUnit

data class AccountProfile(val userId: String, val avatarVersion: String, val librarySongCount: Int)

/** Native Navidrome tokens stay in memory and are sent only to the configured music origin. */
class ProfileApi(private val credentials: Credentials, client: okhttp3.OkHttpClient = NavApi.httpClient(),
                 private val allowLocalHttpForTests: Boolean = false) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val base = ServerAddress.normalize(credentials.server, allowLocalHttpForTests).toHttpUrl()
    private var tokens = java.util.concurrent.atomic.AtomicReference<String?>()
    private var token: String? get() = tokens.get(); set(value) { tokens.set(value) }
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()
    @Volatile private var cancelled = false
    suspend fun <T> await(block: ProfileApi.() -> T): T = try { withTimeout(15_000) { coroutineScope {
        val scoped = ProfileApi(credentials, client, allowLocalHttpForTests).also { it.tokens = tokens }
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { scoped.cancelled = true; scoped.pending.forEach { it.cancel() } }
        }
        try { withContext(Dispatchers.IO) {
            ensureActive()
            try { scoped.block() } catch (error: Exception) { ensureActive(); throw error }
        } }
        finally { withContext(NonCancellable) { watcher.cancelAndJoin() } }
    } } } catch (_: TimeoutCancellationException) { throw IOException("Профиль отвечает слишком долго. Попробуй ещё раз") }
    private fun execute(request: Request): Response {
        if (cancelled) throw CancellationException("Profile request cancelled")
        val call = client.newCall(request)
        call.timeout().timeout(12, TimeUnit.SECONDS)
        pending += call
        if (cancelled) call.cancel()
        try {
            val response = call.execute()
            val body = response.body ?: run { pending -= call; return response }
            val source = object : okio.ForwardingSource(body.source()) {
                override fun close() { try { super.close() } finally { pending -= call } }
            }.buffer()
            return response.newBuilder().body(object : okhttp3.ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        } catch (error: Exception) { pending -= call; throw error }
    }
    private fun endpoint(path: String) = base.newBuilder().addPathSegments(path).build()
    @Synchronized private fun login() {
        if (token != null) return
        val body = JSONObject().put("username", credentials.username).put("password", credentials.password).toString()
        execute(Request.Builder().url(endpoint("auth/login")).post(body.toRequestBody("application/json".toMediaType())).build()).use {
            if (!it.isSuccessful) throw IOException("Не удалось подтвердить музыкальный аккаунт")
            val value = JSONObject(String(readBounded(it.body?.byteStream() ?: throw IOException("Нет ответа"), 65536)))
            token = value.optString("token").takeIf { t -> t.isNotBlank() && t.length < 16384 }
                ?: throw IOException("Сервер не подтвердил аккаунт")
        }
    }
    @Synchronized private fun authorized(build: (String) -> Request): Response {
        login()
        var response = execute(build(token!!))
        if (response.code == 401) {
            response.close(); token = null; login()
            response = execute(build(token!!))
        }
        return response
    }
    private fun request(path: String, jwt: String) = Request.Builder().url(endpoint("dedsec/profile/v1/$path"))
        .header("Authorization", "Bearer $jwt")
    private fun profile(response: Response): AccountProfile = response.use {
        if (!it.isSuccessful) throw IOException(when (it.code) {
            413 -> "Для аватарки выбери изображение поменьше"
            415, 422 -> "Не удалось обработать изображение"
            404 -> "На этом сервере профили пока недоступны"
            else -> "Не удалось обновить профиль"
        })
        val data = JSONObject(String(readBounded(it.body?.byteStream() ?: throw IOException("Нет ответа"), 65536)))
        // avatarUrl from the server is deliberately ignored: authenticated image requests use a fixed origin/path.
        return AccountProfile(data.getString("userId"), data.optString("avatarVersion"), data.optInt("librarySongCount", -1))
    }
    fun me(): AccountProfile = profile(authorized { request("me", it).build() })
    fun upload(bytes: ByteArray): AccountProfile {
        require(bytes.size <= 5_000_000)
        return profile(authorized { request("avatar", it).put(bytes.toRequestBody("image/jpeg".toMediaType())).build() })
    }
    fun remove(): AccountProfile = profile(authorized { request("avatar", it).delete().build() })
    fun avatar(): ByteArray = authorized { request("avatar", it).build() }.use {
        if (!it.isSuccessful || it.body?.contentType()?.toString()?.startsWith("image/jpeg") != true) throw IOException("Аватарка пока недоступна")
        readBounded(it.body!!.byteStream(), 1024 * 1024)
    }
    private fun importRequest(path: String, oauthToken: String? = null, post: Boolean = false): JSONObject {
        if (oauthToken != null) require(oauthToken.isNotBlank() && oauthToken.length <= 8192 && !oauthToken.any { it.isWhitespace() }) { "Вставь только значение токена" }
        return authorized { jwt ->
            val builder = Request.Builder().url(endpoint("dedsec/import/v1/yandex/$path")).header("Authorization", "Bearer $jwt")
            if (post) builder.post((oauthToken?.let { JSONObject().put("oauthToken", it).toString() } ?: "{}")
                .toRequestBody("application/json".toMediaType()))
            builder.build()
        }.use {
            if (!it.isSuccessful) throw IOException(when (it.code) {
                404 -> "На этом сервере перенос пока не включён"
                409 -> "Перенос уже запущен. Обнови статус"
                429 -> "Очередь сервера заполнена. Попробуй позже"
                400, 413, 422 -> "Проверь токен и состояние переноса"
                401, 403 -> "Нет доступа к переносу. Войди в музыкальный аккаунт заново"
                else -> "Сервер переноса временно недоступен"
            })
            JSONObject(String(readBounded(it.body?.byteStream() ?: throw IOException("Нет ответа"), 65536), Charsets.UTF_8))
        }
    }
    fun currentImport(): ImportJob? = importRequest("jobs/current").optJSONObject("job")?.let(ImportJob::parse)
    fun startImport(oauthToken: String): ImportJob = ImportJob.parse(importRequest("jobs", oauthToken, true).getJSONObject("job"))
    fun resumeImport(id: String, oauthToken: String): ImportJob {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,96}")))
        return ImportJob.parse(importRequest("jobs/$id/resume", oauthToken, true).getJSONObject("job"))
    }
    fun cancelImport(id: String): ImportJob {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,96}")))
        return ImportJob.parse(importRequest("jobs/$id/cancel", post = true).getJSONObject("job"))
    }
}

fun readBounded(input: InputStream, limit: Int): ByteArray = input.use { source ->
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = source.read(buffer)
        if (count < 0) break
        if (output.size() + count > limit) throw IOException("Ответ слишком большой")
        output.write(buffer, 0, count)
    }
    output.toByteArray()
}
