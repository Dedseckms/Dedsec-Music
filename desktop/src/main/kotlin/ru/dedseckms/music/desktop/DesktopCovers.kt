package ru.dedseckms.music.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.skia.Image
import ru.dedseckms.music.data.NavApi
import ru.dedseckms.music.data.sha256
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*

/** Per-account cache: bounded encoded disk storage and decoded LRU; cancelled requests close HTTP. */
class DesktopCovers(private val api: NavApi, private val directory: Path) {
    private val slots = Semaphore(2)
    private val diskLock = Any()
    private val cache = object : LinkedHashMap<String, ImageBitmap>(100, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 120
    }
    suspend fun get(id: String, size: Int): ImageBitmap? = withContext(Dispatchers.IO) { slots.withPermit {
        if (id.isBlank()) return@withPermit null
        val key = "$id:$size"
        synchronized(cache) { cache[key] }?.let { return@withPermit it }
        val path = directory.resolve("covers/${sha256(key)}.jpg")
        try {
            val saved = synchronized(diskLock) {
                if (Files.isRegularFile(path) && Files.size(path) <= MAX_COVER) {
                    Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
                    Files.readAllBytes(path)
                } else null
            }
            val bytes = saved?.takeIf(::safeCoverGeometry) ?: coroutineScope {
                val url = api.cover(id, size) ?: return@coroutineScope null
                val call = api.client.newCall(okhttp3.Request.Builder().url(url).build())
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
                try {
                    ensureActive()
                    call.execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val data = response.body?.byteStream()?.use { it.readNBytes(MAX_COVER + 1) } ?: return@use null
                        ensureActive()
                        if (data.size > MAX_COVER || !safeCoverGeometry(data)) return@use null
                        synchronized(diskLock) {
                            ensureActive(); Files.createDirectories(path.parent)
                            trimCoverCache(path.parent, data.size.toLong())
                            if (Files.getFileStore(path.parent).usableSpace >= 256L * 1024 * 1024 + data.size) {
                                val part = Files.createTempFile(path.parent, "cover-", ".part")
                                try { Files.write(part, data); ensureActive(); Files.move(part, path, ATOMIC_MOVE, REPLACE_EXISTING) }
                                finally { Files.deleteIfExists(part) }
                            }
                        }
                        data
                    }
                } finally { watcher.cancel() }
            } ?: return@withPermit null
            ensureActive()
            val image = Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
            synchronized(cache) { cache[key] = image }; image
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { ensureActive(); null }
    } }
    companion object { const val MAX_COVER = 4 * 1024 * 1024 }
}

internal fun trimCoverCache(directory: Path, incoming: Long, limit: Long = 128L * 1024 * 1024) {
    val files = Files.list(directory).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jpg") }.toList() }
    var total = files.sumOf { Files.size(it) }
    for (path in files.sortedBy { Files.getLastModifiedTime(it).toMillis() }) {
        if (total + incoming <= limit) break
        val size = Files.size(path); Files.deleteIfExists(path); total -= size
    }
}

object DesktopDemo {
    private val records = listOf("Линия горизонта" to "Тихий сигнал", "После полуночи" to "Город внутри", "Новый свет" to "Невесомость", "Точка притяжения" to "Мягкий ток", "Тёплый воздух" to "Лёгкое касание", "Поближе к звёздам" to "Небо рядом")
    val albums = records.mapIndexed { i, (title, artist) -> ru.dedseckms.music.data.Album("a$i", title, artist, "", 12) }
    val songs = (0..23).map { i -> ru.dedseckms.music.data.Song("s$i", listOf("Снова на волне", "Останься здесь", "Между строк", "Без лишних слов", "Пока город спит", "Медленный свет")[i % 6], records[i % 6].second,
        album = records[i % 6].first, albumId = "a${i % 6}", artistId = "artist${i % 6}", duration = 150 + i * 7, genre = listOf("Electronic", "Indie", "Ambient")[i % 3]) }
}
