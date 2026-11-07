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
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = slots.withPermit { withContext(Dispatchers.IO) {
        val account = inputData.getString("account") ?: return@withContext Result.failure()
        val credentials = CredentialStore(applicationContext).load(account) ?: return@withContext Result.failure()
        val song = try { Song.parse(JSONObject(inputData.getString("song") ?: "")) } catch (_: Exception) { return@withContext Result.failure() }
        val store = LocalStore(applicationContext, account)
        if (store.offline().any { it.id == song.id }) return@withContext Result.success()
        val destination = store.audioFile(song.id)
        val temporary = File(destination.parentFile, destination.name + ".part")
        try {
            if (destination.parentFile!!.usableSpace < song.size.coerceAtLeast(0) + 50 * 1024 * 1024) {
                return@withContext Result.failure(workDataOf("error" to "На телефоне мало свободного места"))
            }
            val api = NavApi(credentials)
            api.client.newCall(api.download(song)).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty download")
                if (body.contentType()?.subtype?.contains("json", true) == true) throw IOException("Audio unavailable")
                val expected = body.contentLength()
                var received = 0L
                var lastProgress = 0L
                body.byteStream().use { source -> temporary.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isStopped) throw CancellationException()
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
                if (isStopped) throw CancellationException()
                check(temporary.renameTo(destination))
                store.downloaded(song, received)
                Result.success()
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry()
            else Result.failure(workDataOf("error" to "Не удалось скачать трек. Попробуй ещё раз"))
        } finally { temporary.delete() }
    } }
    companion object { private val slots = Semaphore(2) }
}
