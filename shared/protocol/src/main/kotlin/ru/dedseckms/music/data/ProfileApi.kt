package ru.dedseckms.music.data

import java.io.IOException
import java.io.InputStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.*
import okio.buffer
import java.util.concurrent.TimeUnit

data class AccountProfile(val userId: String, val avatarVersion: String, val librarySongCount: Int, val isAdmin: Boolean = false)

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
        return AccountProfile(data.getString("userId"), data.optString("avatarVersion"), data.optInt("librarySongCount", -1), data.optBoolean("isAdmin", false))
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
    fun adminUsers(): List<AdminUser> = adminRequest("users").getJSONArray("users").objects().map(AdminUser::parse)
    fun adminUploads(id: String, offset: Int = 0): AdminUploads {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val data = adminRequest("users/$id/uploads?offset=${offset.coerceAtLeast(0)}&limit=50")
        return AdminUploads(data.getJSONArray("items").objects().map(AdminUpload::parse), data.optInt("total"))
    }
    fun adminCreate(username: String, password: String): Boolean {
        require(username.isNotBlank() && username.length <= 80 && password.length >= 12 && password.toByteArray().size <= 72) { "Логин и пароль: минимум 12 символов, до 72 байт" }
        return adminRequest("users", "POST", JSONObject().put("username", username).put("password", password)).optBoolean("uploadReady")
    }
    fun adminDelete(id: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        adminRequest("users/$id", "DELETE")
    }
    private fun adminRequest(path: String, method: String = "GET", payload: JSONObject? = null): JSONObject = authorized { jwt ->
        val split = path.split('?', limit = 2)
        val url = endpoint("dedsec/profile/v1/admin/${split[0]}").newBuilder()
        if (split.size == 2) url.encodedQuery(split[1])
        val builder = Request.Builder().url(url.build()).header("Authorization", "Bearer $jwt")
        if (method == "POST") builder.post((payload ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        if (method == "DELETE") builder.delete()
        builder.build()
    }.use {
        if (!it.isSuccessful) throw IOException(when (it.code) {
            403 -> "Управление доступно только администратору"
            409 -> "Имя занято или аккаунт нельзя удалить"
            400, 422 -> "Проверь логин и пароль: минимум 12 символов"
            404 -> "Управление пользователями пока не включено на сервере"
            else -> "Не удалось изменить пользователей. Обнови список перед повтором"
        })
        JSONObject(String(readBounded(it.body?.byteStream() ?: throw IOException("Нет ответа"), 524288), Charsets.UTF_8))
    }
    private fun radioRequest(path: String, payload: JSONObject): JSONObject {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65536) { "Слишком большая история микса" }
        return authorized { request("radio/$path", it).post(bytes.toRequestBody("application/json".toMediaType())).build() }.use {
            if (!it.isSuccessful) throw IOException(when (it.code) {
                404 -> "На этом сервере персональная волна пока не включена"
                429, 503 -> "Сервер волны занят. Попробуй позже"
                else -> "Не удалось подобрать волну. Попробуй ещё раз"
            })
            JSONObject(String(readBounded(it.body?.byteStream() ?: throw IOException("Нет ответа"), 524288), Charsets.UTF_8))
        }
    }
    fun radio(mode: MixMode, mood: MixMood, history: List<HistoryEntry> = emptyList(), disliked: Set<String> = emptySet(),
              exclude: List<String> = emptyList(), size: Int = 40): RadioMix {
        val recent = JSONArray()
        var historyBytes = 0
        history.sortedByDescending { it.lastPlayed }.take(200).forEach {
            val entry = JSONObject().put("id", it.song.id.take(128)).put("playedAt", it.lastPlayed).put("count", it.count.coerceIn(1, 100000))
            val bytes = entry.toString().toByteArray(Charsets.UTF_8).size + 1
            if (historyBytes + bytes <= 28000) { recent.put(entry); historyBytes += bytes }
        }
        fun ids(values: List<String>, budget: Int): JSONArray {
            val result = JSONArray(); var bytes = 0
            values.forEach { id ->
                val value = id.take(128); val cost = JSONObject.quote(value).toByteArray(Charsets.UTF_8).size + 1
                if (bytes + cost <= budget) { result.put(value); bytes += cost }
            }
            return result
        }
        val data = radioRequest("mix", JSONObject().put("mode", mode.name).put("mood", mood.name).put("size", size.coerceIn(1, 60))
            .put("history", recent).put("dislikedIds", ids(disliked.take(1000), 24000))
            .put("excludeIds", ids(exclude.takeLast(100), 6000)))
        val tracks = data.getJSONArray("songs")
        if (tracks.length() > 60) throw IOException("Слишком большой микс")
        val analysis = data.optJSONObject("analysis")
        return RadioMix((0 until tracks.length()).map { Song.parse(tracks.getJSONObject(it)) },
            analysis?.optInt("indexed") ?: 0, analysis?.optInt("total") ?: 0, data.optString("reason").takeUnless { it.isBlank() || it == "null" })
    }
    fun radioEvent(id: String, kind: String, positionMs: Long, durationMs: Long) {
        require(kind in setOf("skipped", "played", "disliked"))
        radioRequest("events", JSONObject().put("id", id.take(128)).put("kind", kind)
            .put("positionMs", positionMs.coerceAtLeast(0)).put("durationMs", durationMs.coerceAtLeast(0)))
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
