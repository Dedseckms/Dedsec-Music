package ru.dedseckms.music.desktop

import kotlinx.coroutines.*
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/** Cancels only this operation's exchanges, including a blocking upload body. */
internal suspend fun <T> transfer(base: OkHttpClient, block: suspend (OkHttpClient) -> T): T = withContext(Dispatchers.IO) { coroutineScope {
    val dispatcher = Dispatcher()
    val client = base.newBuilder().dispatcher(dispatcher).build()
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { dispatcher.cancelAll() } }
    try { ensureActive(); block(client) }
    catch (e: Exception) { ensureActive(); throw e }
    finally { watcher.cancel() }
} }
