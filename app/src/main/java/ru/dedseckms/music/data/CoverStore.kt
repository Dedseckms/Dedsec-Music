package ru.dedseckms.music.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** A private, bounded cache shared by all screens and the media session. */
class CoverStore(private val directory: File, private val account: (String) -> Credentials?,
                 private val apiFactory: (Credentials) -> NavApi = { NavApi(it) }) {
    private val slots = Semaphore(2)
    private val locks = Array(128) { Mutex() }
    private data class Key(val accountId: String, val cover: String, val size: Int)
    private val ready = java.util.concurrent.ConcurrentHashMap<Key, File>()
    private var writes = 0
    @Volatile private var backoffUntil = 0L

    /** An IO-free fast path for already loaded covers; file contents remain in the private app cache. */
    fun peek(accountId: String, cover: String, size: Int): File? = ready[Key(accountId, cover, size)]

    suspend fun file(accountId: String, cover: String, size: Int): File = withContext(Dispatchers.IO) {
        require(size in setOf(192, 384, 768))
        // One encoded source serves every decode size. List, notification and player no longer fetch it separately.
        val key = sha256("$accountId:$cover:source-v2")
        val target = File(directory, "$key.img")
        fun markReady(file: File): File {
            listOf(192, 384, 768).forEach { ready[Key(accountId, cover, it)] = file }
            return file
        }
        locks[Math.floorMod(key.hashCode(), locks.size)].withLock {
            if (account(accountId) == null) throw IOException("Аккаунт отключён")
            if (target.isFile && target.length() > 0) return@withLock markReady(target)
            val previousLarge = File(directory, "${sha256("$accountId:$cover:768")}.img")
            if (previousLarge.isFile && previousLarge.length() > 0) return@withLock markReady(previousLarge)
            val previousSmall = File(directory, "${sha256("$accountId:$cover:192")}.img")
            if (previousSmall.isFile && previousSmall.length() > 0) {
                ready[Key(accountId, cover, 192)] = previousSmall
                if (size == 192) return@withLock previousSmall
            }
            slots.withPermit {
                if (System.currentTimeMillis() < backoffUntil) throw IOException("Обложки временно недоступны")
                val credentials = account(accountId) ?: throw IOException("Аккаунт отключён")
                val api = apiFactory(credentials)
                val url = api.cover(cover, 768) ?: throw IOException("Нет обложки")
                val call = NavApi.httpClient().newCall(Request.Builder().url(url).build())
                call.timeout().timeout(8, TimeUnit.SECONDS)
                val watcher = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    val response = suspendCancellableCoroutine<Response> { continuation ->
                        continuation.invokeOnCancellation { call.cancel() }
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("Обложка недоступна")) }
                            override fun onResponse(call: Call, response: Response) {
                                continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
                            }
                        })
                    }
                    response.use {
                        if (it.code == 429) {
                            val delay = it.header("Retry-After")?.toLongOrNull()?.coerceIn(2, 60) ?: 5
                            backoffUntil = System.currentTimeMillis() + delay * 1000
                        }
                        if (!it.isSuccessful) throw IOException("Обложка недоступна")
                        val bytes = it.body?.byteStream()?.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > 2 * 1024 * 1024) throw IOException("Обложка слишком большая")
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: throw IOException("Пустая обложка")
                        if (bytes.isEmpty() || it.header("Content-Type")?.startsWith("image/", true) != true) throw IOException("Сервер не вернул изображение")
                        currentCoroutineContext().ensureActive()
                        if (account(accountId) == null) throw IOException("Аккаунт отключён")
                        directory.mkdirs()
                        val temporary = File(directory, "$key.tmp")
                        try { temporary.writeBytes(bytes); check(temporary.renameTo(target)) }
                        finally { temporary.delete() }
                        markReady(target)
                        if (++writes % 20 == 1) prune()
                    }
                } catch (e: Exception) { currentCoroutineContext().ensureActive(); throw e }
                finally { watcher.cancel(); call.cancel() }
                target
            }
        }
    }

    private fun prune() {
        val files = directory.listFiles()?.filter { it.extension == "img" }?.sortedByDescending { it.lastModified() }.orEmpty()
        var total = 0L
        val removed = mutableSetOf<File>()
        files.forEach { file -> total += file.length(); if (total > 128L * 1024 * 1024 && file.delete()) removed += file }
        ready.entries.removeAll { it.value in removed }
    }
}
