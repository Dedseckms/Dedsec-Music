package ru.dedseckms.music.data

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

const val YANDEX_IMPORT_GUIDE = "https://github.com/Dedseckms/Dedsec-Music/blob/main/docs/YANDEX_IMPORT.md#получить-токен"

data class ImportTrack(val title: String, val artist: String)
data class ImportJob(
    val id: String, val state: String, val phase: String,
    val discovered: Long, val total: Long, val downloaded: Long, val skipped: Long,
    val failed: Long, val unavailable: Long, val excluded: Long, val processed: Long,
    val totalBytes: Long, val current: ImportTrack?, val errorCode: String?,
    val playlistCount: Long = 0, val completedPlaylistCount: Long = 0,
    val pendingPlaylistCount: Long = 0, val missingPlaylistTracks: Long = 0,
) {
    val active get() = state in setOf("queued", "running", "cancelling")
    val syncingPlaylists get() = active && phase in setOf("indexing", "playlists")
    val resumable get() = state in setOf("cancelled", "paused_auth", "failed", "finished_with_errors")
    val fraction: Float? get() = if (phase == "download" && total > 0 || state in setOf("completed", "finished_with_errors") && total > 0)
        (processed.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null
    val label: String get() = when (state) {
        "cancelled" -> "Перенос остановлен"
        "paused_auth" -> "Для продолжения нужен токен"
        "completed" -> "Музыка перенесена"
        "finished_with_errors" -> "Перенос завершён с пропусками"
        "failed" -> when (errorCode) { "NO_SPACE" -> "На сервере мало свободного места"; "AUTH_REQUIRED" -> "Нужен действующий токен"; else -> "Перенос приостановлен из-за ошибки" }
        "cancelling" -> "Останавливаем перенос"
        else -> when (phase) { "inventory" -> "Собираем коллекцию"; "classification" -> "Отбираем музыку"; "download" -> "Скачиваем на сервер"; "indexing" -> "Добавляем треки в каталог"; "playlists" -> "Восстанавливаем личные плейлисты"; else -> "Ожидаем свободную очередь" }
    }
    companion object {
        fun parse(data: JSONObject): ImportJob {
            val id = data.getString("id"); require(id.matches(Regex("[A-Za-z0-9_-]{1,96}")))
            val state = data.getString("state"); require(state in setOf("queued", "running", "cancelling", "cancelled", "paused_auth", "completed", "finished_with_errors", "failed"))
            val phase = data.getString("phase"); require(phase in setOf("queued", "inventory", "classification", "download", "indexing", "playlists", "finished", "paused"))
            fun count(key: String) = data.getLong(key).also { require(it >= 0) }
            val current = data.optJSONObject("current")?.let { ImportTrack(it.optString("title").take(300), it.optString("artist").take(300)) }
            return ImportJob(id, state, phase, count("discovered"), count("total"), count("downloaded"), count("skipped"),
                count("failed"), count("unavailable"), count("excluded"), count("processed"), count("totalBytes"), current,
                data.optString("errorCode").takeUnless { it.isBlank() || it == "null" }, data.optLong("playlistCount").coerceAtLeast(0),
                data.optLong("completedPlaylistCount").coerceAtLeast(0), data.optLong("pendingPlaylistCount").coerceAtLeast(0), data.optLong("missingPlaylistTracks").coerceAtLeast(0))
        }
    }
}

data class ImportStatus(val job: ImportJob? = null, val busy: Boolean = false, val error: String? = null)

/** Observes a server-owned task. Closing this session never cancels that task. */
class YandexImportSession(parent: CoroutineScope, private val api: ProfileApi) : AutoCloseable {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val mutable = MutableStateFlow(ImportStatus())
    val state = mutable.asStateFlow()
    private var polling: Job? = null
    private fun safe(error: Exception) = if (error is IOException && error.message?.contains("http", true) != true)
        error.message ?: "Нет связи с сервером переноса" else "Не удалось связаться с сервером переноса"
    fun refresh() {
        if (state.value.busy) return
        poll()
    }
    private fun poll() {
        polling?.cancel()
        polling = scope.launch {
            var failures = 0
            do {
                try {
                    val job = api.await { currentImport() }
                    mutable.update { it.copy(job = job, error = null) }; failures = 0
                    if (job?.active != true) break
                } catch (error: CancellationException) { throw error
                } catch (error: Exception) {
                    mutable.update { it.copy(error = safe(error)) }; failures++
                    if (failures >= 3) break
                }
                delay(if (failures == 0) 3000 else 10_000)
            } while (isActive)
        }
    }
    fun submit(oauthToken: String) = command { val job = state.value.job; if (job?.resumable == true) resumeImport(job.id, oauthToken) else startImport(oauthToken) }
    fun cancel() { val id = state.value.job?.takeIf { it.active && it.state != "cancelling" }?.id ?: return; command { cancelImport(id) } }
    private fun command(block: ProfileApi.() -> ImportJob) {
        if (state.value.busy) return
        polling?.cancel(); mutable.update { it.copy(busy = true, error = null) }
        scope.launch {
            try { val job = api.await(block); mutable.update { it.copy(job = job) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { mutable.update { it.copy(error = safe(error)) }
            } finally { mutable.update { it.copy(busy = false) } }
            if (state.value.error == null) poll()
        }
    }
    override fun close() { scope.cancel() }
}
