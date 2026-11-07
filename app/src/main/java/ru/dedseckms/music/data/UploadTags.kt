package ru.dedseckms.music.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

data class UploadTags(val title: String = "", val artist: String = "", val durationMs: Long = 0)

/** Only reads document URIs to which the user has already granted persistent access. */
object UploadTagReader {
    fun read(context: Context, uri: Uri): UploadTags {
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(context, uri)
            UploadTags(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0)
        } catch (_: Exception) { UploadTags() } finally { runCatching { reader.release() } }
    }
    fun findPersisted(context: Context, filename: String): UploadTags {
        for (permission in context.contentResolver.persistedUriPermissions) {
            if (!permission.isReadPermission) continue
            val name = runCatching {
                context.contentResolver.query(permission.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) UploadApi.safeFilename(it.getString(0)) else null
                }
            }.getOrNull()
            if (name == filename) return read(context, permission.uri)
        }
        return UploadTags()
    }
}
