package ru.dedseckms.music.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class LyricLine(val startMs: Long?, val text: String)
data class Lyrics(val status: String, val synced: Boolean, val lines: List<LyricLine>, val source: String, val revision: String) {
    companion object {
        fun parse(o: JSONObject): Lyrics {
            val status = o.getString("status")
            require(status in setOf("ready", "pending", "not_found", "instrumental", "error"))
            val raw = o.optJSONArray("lines")
            require((raw?.length() ?: 0) <= 4000)
            val lines = (0 until (raw?.length() ?: 0)).map { i ->
                val line = raw!!.getJSONObject(i); val text = line.getString("text")
                require(text.length <= 4096)
                LyricLine(if (line.isNull("startMs") || !line.has("startMs")) null else line.getLong("startMs").also { require(it in 0..86_400_000) }, text)
            }
            val synced = o.optBoolean("synced") && lines.isNotEmpty() && lines.all { it.startMs != null } && lines.zipWithNext().all { (a, b) -> a.startMs!! <= b.startMs!! }
            return Lyrics(status, synced, lines, o.optString("source").take(120), o.optString("revision").take(160))
        }
    }
    fun activeLine(position: Long): Int {
        if (!synced) return -1
        var low = 0; var high = lines.size
        while (low < high) { val middle = (low + high) / 2; if (lines[middle].startMs!! <= position) low = middle + 1 else high = middle }
        return low - 1
    }
}

data class LyricsState(val lyrics: Lyrics? = null, val loading: Boolean = true, val error: String? = null)

/** Lyrics retrieval never runs on the audio path; the cache belongs to one music account. */
class LyricsSession(parent: CoroutineScope, private val api: ProfileApi?, private val song: Song, private val cache: File?) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutable = MutableStateFlow(LyricsState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    init { refresh() }
    fun refresh() {
        request?.cancel()
        request = scope.launch {
            mutable.value = mutable.value.copy(loading = true, error = null)
            try {
                val saved = withContext(Dispatchers.IO) { runCatching { cache?.takeIf { it.length() in 1..524288 }?.readText()?.let { Lyrics.parse(JSONObject(it)) } }.getOrNull() }
                if (saved != null) mutable.value = mutable.value.copy(lyrics = saved, loading = false)
                if (api == null) { mutable.value = mutable.value.copy(loading = false, error = if (saved == null) "Для текста подключись к серверу" else null); return@launch }
                repeat(20) {
                    val raw = api.await { lyrics(song.id) }; val parsed = Lyrics.parse(raw)
                    ensureActive()
                    mutable.value = LyricsState(if (parsed.status == "pending" && saved != null) saved else parsed, parsed.status == "pending")
                    if (parsed.status != "pending") {
                        if (parsed.status in setOf("ready", "instrumental")) withContext(Dispatchers.IO) { runCatching {
                            cache?.let { file -> file.parentFile?.mkdirs(); val temp = File.createTempFile("lyrics-", ".tmp", file.parentFile)
                                try { temp.writeText(raw.toString()); java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) } finally { temp.delete() }
                            }
                        } }
                        return@launch
                    }
                    delay(1500)
                }
                mutable.value = mutable.value.copy(loading = false, error = "Поиск ещё выполняется. Попробуй обновить позже")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(loading = false, error = "Не удалось получить текст. Проверь подключение и версию серверного дополнения") }
        }
    }
    override fun close() { job.cancel() }
}
