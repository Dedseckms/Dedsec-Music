package ru.dedseckms.music.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class LyricsAlignmentProgress(val enabled: Boolean, val total: Int, val pending: Int, val done: Int,
    val skipped: Int, val errors: Int, val active: Int) {
    companion object {
        fun parse(o: JSONObject) = LyricsAlignmentProgress(o.getBoolean("enabled"), o.getInt("total"), o.getInt("pending"),
            o.getInt("done"), o.getInt("skipped"), o.getInt("errors"), o.getInt("active"))
    }
}
data class LyricsBatchProgress(val total: Int, val checked: Int, val synced: Int, val plain: Int,
    val instrumental: Int, val notFound: Int, val errors: Int, val pending: Int, val running: Boolean, val discovering: Boolean,
    val alignment: LyricsAlignmentProgress? = null) {
    companion object {
        fun parse(o: JSONObject) = LyricsBatchProgress(o.getInt("total"), o.getInt("checked"), o.getInt("synced"),
            o.getInt("plain"), o.getInt("instrumental"), o.getInt("notFound"), o.getInt("errors"), o.getInt("pending"),
            o.getBoolean("running"), o.optBoolean("discovering"), o.optJSONObject("alignment")?.let(LyricsAlignmentProgress::parse))
    }
}
data class LyricsBatchState(val progress: LyricsBatchProgress? = null, val error: String? = null, val acting: Boolean = false)

/** Polls only while the owner has the progress panel open. Jobs themselves live on the server. */
class LyricsBatchSession(parent: CoroutineScope, private val api: ProfileApi) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutable = MutableStateFlow(LyricsBatchState())
    val state = mutable.asStateFlow()
    private var busy = false
    init { scope.launch { while (isActive) { request(null); delay(5000) } } }
    fun action(action: String) {
        require(action in setOf("start", "pause", "align_start", "align_pause"))
        if (mutable.value.acting) return
        mutable.value = mutable.value.copy(acting = true)
        scope.launch { while (busy) delay(50); request(action) }
    }
    private suspend fun request(action: String?) {
        if (busy || action == null && mutable.value.acting) return
        busy = true
        try {
            val progress = api.await { lyricsBatch(action) }
            mutable.value = mutable.value.copy(progress = progress, error = null)
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.value = mutable.value.copy(error = e.message ?: "Не удалось получить прогресс") }
        finally { if (action != null) mutable.value = mutable.value.copy(acting = false); busy = false }
    }
    override fun close() { job.cancel() }
}
