package ru.dedseckms.music.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.*
import org.json.JSONObject

class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = slots.withPermit { coroutineScope {
        val pending = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>()
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { pending.get()?.cancel() }
        }
        try { withContext(Dispatchers.IO) {
        val account = inputData.getString("account") ?: return@withContext Result.failure()
        val credentials = CredentialStore(applicationContext).load(account) ?: return@withContext Result.failure()
        val song = try { Song.parse(JSONObject(inputData.getString("song") ?: "")) } catch (_: Exception) { return@withContext Result.failure() }
        val store = LocalStore(applicationContext, account)
        val existing = store.audioFile(song.id)
        if (existing.isFile && existing.length() > 0) { store.downloaded(song, existing.length()); store.finishDownload(song.id); return@withContext Result.success() }
        val destination = store.audioFile(song.id)
        val temporary = File.createTempFile("download-", ".part", destination.parentFile)
        try {
            if (destination.parentFile!!.usableSpace < song.size.coerceAtLeast(0) + 50 * 1024 * 1024) {
                return@withContext Result.failure(workDataOf("error" to "На телефоне мало свободного места"))
            }
            if (CredentialStore(applicationContext).activeId() != account) return@withContext Result.failure()
            if (store.wifiOnly() && applicationContext.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered) return@withContext Result.retry()
            val api = NavApi(credentials)
            val call = api.client.newCall(api.download(song)); pending.set(call)
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty download")
                if (body.contentType()?.subtype?.contains("json", true) == true) throw IOException("Audio unavailable")
                val expected = body.contentLength()
                var received = 0L
                var lastProgress = 0L
                body.byteStream().use { source -> temporary.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (isStopped || CredentialStore(applicationContext).activeId() != account) throw CancellationException()
                        if (store.wifiOnly() && applicationContext.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered) return@withContext Result.retry()
                        val count = source.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        received += count
                        if (received - lastProgress > 256 * 1024) {
                            setProgress(workDataOf("progress" to if (expected > 0) (received * 100 / expected).toInt() else 0))
                            lastProgress = received
                        }
                    }
                } }
                if (received == 0L || (expected >= 0 && expected != received)) throw IOException("Incomplete download")
                val reader = android.media.MediaMetadataRetriever()
                try {
                    reader.setDataSource(temporary.absolutePath)
                    val duration = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    if (duration <= 0) throw IOException("Invalid audio")
                    if (song.duration > 0 && kotlin.math.abs(duration - song.duration * 1000L) > maxOf(3000L, song.duration * 30L)) throw IOException("Audio duration mismatch")
                } finally { reader.release() }
                currentCoroutineContext().ensureActive()
                        if (isStopped || CredentialStore(applicationContext).activeId() != account) throw CancellationException()
                        if (store.wifiOnly() && applicationContext.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered) return@withContext Result.retry()
                check(temporary.renameTo(destination))
                store.downloaded(song, received)
                store.finishDownload(song.id)
                Result.success()
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (runAttemptCount < 2) Result.retry()
            else Result.failure(workDataOf("error" to "Не удалось скачать трек. Попробуй ещё раз"))
        } finally { temporary.delete() }
    } } finally { watcher.cancel() }
    } }
    companion object { private val slots = Semaphore(2) }
}
