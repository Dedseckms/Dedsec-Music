@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package ru.dedseckms.music.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException
import ru.dedseckms.music.MusicApp
import ru.dedseckms.music.data.LocalStore
import ru.dedseckms.music.data.NavApi

/** Media sessions expose only opaque song IDs. Authentication stays inside the data source. */
class AccountDataSource(private val app: MusicApp) : DataSource {
    private var delegate: DataSource? = null
    private var publicUri: Uri? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
    override fun open(dataSpec: DataSpec): Long {
        val credentials = app.credentials.active() ?: throw IOException("Войди в аккаунт")
        if (dataSpec.uri.scheme != "dedsec" || dataSpec.uri.host != credentials.accountId) throw IOException("Аккаунт воспроизведения изменился")
        val id = dataSpec.uri.lastPathSegment ?: throw IOException("Нет идентификатора трека")
        val store = LocalStore(app, credentials.accountId)
        val offline = store.offline().any { it.id == id }
        val target: Uri
        val source: DataSource
        if (offline) {
            target = Uri.fromFile(store.audioFile(id)); source = FileDataSource()
        } else {
            target = Uri.parse(NavApi(credentials).url("stream", listOf("id" to id, "format" to "raw")).toString())
            source = OkHttpDataSource.Factory(NavApi.httpClient()).createDataSource()
        }
        publicUri = dataSpec.uri
        listeners.forEach(source::addTransferListener)
        delegate = source
        try { return source.open(dataSpec.withUri(target)) }
        catch (_: IOException) { runCatching { source.close() }; throw IOException("Трек недоступен. Проверь подключение или офлайн-копию") }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        delegate?.read(buffer, offset, length) ?: throw IOException("Источник закрыт")
    } catch (_: IOException) { throw IOException("Передача аудио прервана") }
    override fun getUri(): Uri? = publicUri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
    override fun close() { try { delegate?.close() } finally { delegate = null; publicUri = null } }
}
