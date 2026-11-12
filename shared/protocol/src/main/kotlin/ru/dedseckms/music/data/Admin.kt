package ru.dedseckms.music.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

data class AdminUser(val id: String, val username: String, val isAdmin: Boolean, val uploadCount: Int, val canDelete: Boolean) {
    companion object { fun parse(data: JSONObject) = AdminUser(data.getString("id"), data.getString("username"), data.optBoolean("isAdmin"), data.optInt("uploadCount"), data.optBoolean("canDelete")) }
}
data class AdminUpload(val name: String, val source: String, val size: Long) {
    companion object { fun parse(data: JSONObject) = AdminUpload(data.getString("name"), data.optString("source"), data.optLong("size")) }
}
data class AdminUploads(val items: List<AdminUpload>, val total: Int)
data class AdminState(val users: List<AdminUser> = emptyList(), val selected: AdminUser? = null,
    val uploads: List<AdminUpload> = emptyList(), val uploadTotal: Int = 0, val busy: Boolean = false, val message: String? = null)

/** A screen owns this account-scoped session and cancels it on dismissal or logout. */
class AdminSession(parent: CoroutineScope, private val api: ProfileApi) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutable = MutableStateFlow(AdminState())
    val state: StateFlow<AdminState> = mutable
    init { refresh() }
    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy || !job.isActive) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(message = (e as? java.io.IOException)?.message ?: "Не удалось выполнить операцию") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun refresh() = action { val users = api.await { adminUsers() }; mutable.value = mutable.value.copy(users = users) }
    fun uploads(user: AdminUser) = action {
        mutable.value = mutable.value.copy(selected = user, uploads = emptyList(), uploadTotal = 0)
        val result = api.await { adminUploads(user.id) }
        mutable.value = mutable.value.copy(uploads = result.items, uploadTotal = result.total)
    }
    fun more() = action {
        val user = mutable.value.selected ?: return@action
        val offset = mutable.value.uploads.size
        val result = api.await { adminUploads(user.id, offset) }
        mutable.value = mutable.value.copy(uploads = mutable.value.uploads + result.items, uploadTotal = result.total)
    }
    fun back() { if (!mutable.value.busy) mutable.value = mutable.value.copy(selected = null, uploads = emptyList(), uploadTotal = 0, message = null) }
    fun create(username: String, password: String) = action {
        val ready = api.await { adminCreate(username, password) }
        val users = api.await { adminUsers() }
        mutable.value = mutable.value.copy(users = users, message = if (ready) "Аккаунт создан. Этот логин и пароль подходят для музыки и загрузки" else "Музыкальный аккаунт создан. Загрузку нужно настроить отдельно на сервере")
    }
    fun delete(user: AdminUser) = action {
        api.await { adminDelete(user.id) }
        val users = api.await { adminUsers() }
        mutable.value = AdminState(users = users, busy = true, message = "Аккаунт удалён. Общая музыка сохранена")
    }
    override fun close() { job.cancel() }
}
