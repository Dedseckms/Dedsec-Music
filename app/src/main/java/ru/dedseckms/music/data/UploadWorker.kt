package ru.dedseckms.music.data

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val account = inputData.getString("account") ?: return@withContext Result.failure()
        val credentials = CredentialStore(applicationContext).upload(account) ?: return@withContext Result.failure()
        val uri = Uri.parse(inputData.getString("uri") ?: return@withContext Result.failure())
        val name = inputData.getString("name") ?: return@withContext Result.failure()
        val metadata = android.media.MediaMetadataRetriever()
        var hasVideo = false
        val tags = try {
            metadata.setDataSource(applicationContext, uri)
            hasVideo = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            Triple(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
        } catch (_: Exception) { Triple("", "", 0L) } finally { metadata.release() }
        try {
            UploadPolicy.validate(name, inputData.getLong("size", -1), tags.third, hasVideo)
            val api = UploadApi(credentials)
            api.login()
            var last = -1
            api.upload(name, inputData.getLong("size", -1), {
                applicationContext.contentResolver.openInputStream(uri) ?: error("Файл недоступен")
            }) { sent, size ->
                if (isStopped) throw CancellationException()
                val progress = if (size > 0) (sent * 100 / size).toInt() else 0
                if (progress != last) {
                    last = progress
                    setProgressAsync(workDataOf("progress" to progress, "name" to name))
                }
            }
            // A normal user may not have scan permissions; the scheduled scan still discovers uploads.
            val requested = CredentialStore(applicationContext).load(account)?.let { runCatching { NavApi(it).requestScan() }.isSuccess } ?: false
            Result.success(workDataOf("name" to name, "title" to tags.first.take(250), "artist" to tags.second.take(250),
                "duration" to tags.third, "uploadedAt" to System.currentTimeMillis(), "scanRequested" to requested))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val message = if (error is UploadRejected || (error is java.io.IOException && error.message?.startsWith("Нет доступа") == true)) error.message
                else "Не удалось загрузить файл. Проверь соединение и нет ли файла с таким именем"
            Result.failure(workDataOf("name" to name, "error" to message))
        }
    }
}
