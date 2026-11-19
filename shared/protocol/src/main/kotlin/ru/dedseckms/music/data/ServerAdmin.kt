package ru.dedseckms.music.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class SystemSample(val at: Long, val cpu: Double?, val memoryTotal: Long, val memoryUsed: Long,
    val memoryAvailable: Long, val swapUsed: Long, val load1: Double, val load5: Double, val load15: Double,
    val rx: Double, val tx: Double) {
    companion object { fun parse(o: JSONObject) = SystemSample(o.optDouble("at", o.optDouble("sampledAt", 0.0)).toLong(),
        if (o.has("cpuPercent") && !o.isNull("cpuPercent")) o.optDouble("cpuPercent").takeIf(Double::isFinite) else null,
        o.optLong("memoryTotalBytes", -1), o.optLong("memoryUsedBytes", -1), o.optLong("memoryAvailableBytes", -1), o.optLong("swapUsedBytes", -1),
        o.optDouble("load1", Double.NaN), o.optDouble("load5", Double.NaN), o.optDouble("load15", Double.NaN),
        o.optDouble("rxBytesPerSecond", Double.NaN), o.optDouble("txBytesPerSecond", Double.NaN)) }
}
data class ServerProcess(val id: String, val pid: Int, val name: String, val cpu: Double?, val memory: Long) {
    companion object { fun parse(o: JSONObject) = ServerProcess(o.optString("id"), o.optInt("pid"), o.optString("name"),
        if (o.has("cpuPercent") && !o.isNull("cpuPercent")) o.optDouble("cpuPercent").takeIf(Double::isFinite) else null, o.optLong("rssBytes")) }
}
data class ServerSystem(val sampledAt: Long, val scope: String, val cpuCount: Int, val uptime: Long,
    val latest: SystemSample, val history: List<SystemSample>, val processes: List<ServerProcess>, val processCount: Int) {
    companion object { fun parse(o: JSONObject) = ServerSystem(o.optDouble("sampledAt").toLong(), o.optString("scope", "container"),
        o.optInt("cpuCount", 1), o.optLong("uptimeSeconds", -1), SystemSample.parse(o.getJSONObject("latest")),
        o.optJSONArray("history").objects().map(SystemSample::parse), o.optJSONArray("processes").objects().map(ServerProcess::parse), o.optInt("processCount")) }
}
data class StorageVolume(val id: String, val label: String, val mountPoint: String, val total: Long, val used: Long,
    val available: Long, val readOnly: Boolean, val eligible: Boolean, val reason: String) {
    companion object { fun parse(o: JSONObject) = StorageVolume(o.getString("id"), o.optString("label"), o.optString("mountPoint"),
        o.optLong("totalBytes"), o.optLong("usedBytes"), o.optLong("availableBytes"), o.optBoolean("readOnly"), o.optBoolean("eligibleTarget"), o.optString("reason")) }
}
data class StorageShard(val id: String, val label: String, val volumeId: String, val logicalPath: String, val tracks: Int, val bytes: Long) {
    companion object { fun parse(o: JSONObject) = StorageShard(o.getString("id"), o.optString("label"), o.optString("volumeId"), o.optString("logicalPath"), o.optInt("trackCount"), o.optLong("indexedBytes")) }
}
data class StoragePlan(val id: String, val hash: String, val files: Int, val bytes: Long, val reserve: Long,
    val expiresAt: Long, val warnings: List<String>) {
    companion object { fun parse(o: JSONObject) = StoragePlan(o.getString("id"), o.getString("planHash"), o.optInt("files"), o.optLong("bytes"),
        o.optLong("reserveBytes"), o.optDouble("expiresAt").toLong(), o.optJSONArray("warnings")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()) }
}
data class StorageMove(val id: String, val state: String, val phase: String, val filesTotal: Int, val filesDone: Int,
    val bytesTotal: Long, val bytesCopied: Long, val error: String, val updatedAt: Long) {
    companion object { fun parse(o: JSONObject) = StorageMove(o.getString("id"), o.optString("state"), o.optString("phase"),
        o.optInt("filesTotal"), o.optInt("filesDone"), o.optLong("bytesTotal"), o.optLong("bytesCopied"), o.optString("errorCode"), o.optDouble("updatedAt").toLong()) }
}
data class ServerStorage(val volumes: List<StorageVolume>, val shards: List<StorageShard>, val activeJob: StorageMove?) {
    companion object { fun parse(o: JSONObject) = ServerStorage(o.getJSONArray("volumes").objects().map(StorageVolume::parse),
        o.getJSONArray("shards").objects().map(StorageShard::parse), o.optJSONObject("activeJob")?.let(StorageMove::parse)) }
}
data class ServerAdminState(val system: ServerSystem? = null, val storage: ServerStorage? = null, val plan: StoragePlan? = null,
    val loading: Boolean = true, val acting: Boolean = false, val error: String? = null, val windowSeconds: Int = 3600)

/** Polling exists only while the administrator dashboard is open. */
class ServerAdminSession(parent: CoroutineScope, private val api: ProfileApi, autoRefresh: Boolean = true) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutable = MutableStateFlow(ServerAdminState())
    val state = mutable.asStateFlow()
    private var refreshing = false
    init { if (autoRefresh) scope.launch { while (isActive) { refreshNow(); delay(5000) } } }
    fun refresh() { scope.launch { refreshNow() } }
    fun window(seconds: Int) { mutable.value = mutable.value.copy(windowSeconds = seconds.coerceIn(300, 86400)); refresh() }
    private suspend fun refreshNow() {
        if (refreshing || mutable.value.acting) return
        refreshing = true
        try {
            val system = api.await { adminSystem(mutable.value.windowSeconds) }
            mutable.value = mutable.value.copy(system = system, loading = false, error = null)
            val storage = api.await { adminStorage() }
            mutable.value = mutable.value.copy(storage = storage)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.value = mutable.value.copy(loading = false, error = (e as? java.io.IOException)?.message ?: "Не удалось получить состояние сервера") }
        finally { refreshing = false }
    }
    private fun action(block: suspend () -> Unit) {
        if (mutable.value.acting) return
        mutable.value = mutable.value.copy(acting = true, error = null)
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(error = (e as? java.io.IOException)?.message ?: "Операция не выполнена. Обнови состояние") }
            finally { mutable.value = mutable.value.copy(acting = false) }
            refreshNow()
        }
    }
    fun plan(shard: String, volume: String) = action { mutable.value = mutable.value.copy(plan = api.await { adminStoragePlan(shard, volume) }) }
    fun dismissPlan() { if (!mutable.value.acting) mutable.value = mutable.value.copy(plan = null) }
    fun start() { val plan = mutable.value.plan ?: return; action {
        api.await { adminStorageStart(plan.id, plan.hash) }; mutable.value = mutable.value.copy(plan = null)
    } }
    fun cancel(id: String) = action { api.await { adminStorageAction(id, "cancel") } }
    fun resume(id: String) = action { api.await { adminStorageAction(id, "resume") } }
    override fun close() { job.cancel() }
}
