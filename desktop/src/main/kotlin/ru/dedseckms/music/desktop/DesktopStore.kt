package ru.dedseckms.music.desktop

import com.sun.jna.platform.win32.Crypt32Util
import org.json.JSONArray
import org.json.JSONObject
import ru.dedseckms.music.data.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*

object DesktopPaths {
    val root: Path = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "DedSecMusic")
    fun account(id: String): Path = root.resolve("accounts").resolve(id).also { Files.createDirectories(it) }
}

/** Windows protects the saved password with the current user's DPAPI key. */
class DesktopStore(val credentials: Credentials, val directory: Path = DesktopPaths.account(credentials.accountId)) {
    init { Files.createDirectories(directory) }
    private val lock = Any()
    fun offlinePreferences(): OfflinePreferences = read("offline-preferences.json").let { OfflinePreferences(it.optBoolean("afterListen"), it.optBoolean("onFavorite")) }
    fun offlinePreferences(value: OfflinePreferences) = synchronized(lock) { write("offline-preferences.json", JSONObject().put("afterListen", value.afterListen).put("onFavorite", value.onFavorite)) }
    fun childMode(): Boolean = read("content-preferences.json").optBoolean("childMode", false)
    fun childMode(enabled: Boolean) = synchronized(lock) { write("content-preferences.json", JSONObject().put("childMode", enabled)) }
    private fun queueDirectory(): Path = directory.resolve("download-queue").also { Files.createDirectories(it) }
    fun pendingDownloads(): List<Song> = Files.list(queueDirectory()).use { paths -> paths.filter { it.fileName.toString().endsWith(".json") }.map { path -> runCatching { Song.parse(JSONObject(Files.readString(path))) }.getOrNull() }.toList().filterNotNull() }
    fun queueDownloads(songs: List<Song>) { for (song in songs) synchronized(lock) { write("download-queue/${sha256(song.id)}.json", song.json()) } }
    fun finishDownload(id: String) = synchronized(lock) { Files.deleteIfExists(queueDirectory().resolve("${sha256(id)}.json")); Unit }
    fun audio(song: Song): Path = directory.resolve("offline/${sha256(song.id)}.audio")
    private fun read(name: String) = runCatching { JSONObject(Files.readString(directory.resolve(name))) }.getOrDefault(JSONObject())
    fun history(): List<HistoryEntry> = read("history.json").optJSONArray("items").objects().mapNotNull {
        runCatching { HistoryEntry(Song.parse(it.getJSONObject("song")), it.getLong("at"), it.getInt("count")) }.getOrNull()
    }
    fun searchHistory(): List<String> = read("search.json").optJSONArray("queries")?.let { array -> (0 until minOf(12, array.length())).map { array.optString(it).take(120) }.filter { it.length >= 2 } }.orEmpty()
    fun saveSearchHistory(queries: List<String>) = synchronized(lock) { write("search.json", JSONObject().put("queries", JSONArray(queries.take(12)))) }
    fun record(song: Song) = synchronized(lock) {
        val old = history()
        val entry = HistoryEntry(song, System.currentTimeMillis(), (old.find { it.song.id == song.id }?.count ?: 0) + 1)
        write("history.json", JSONObject().put("items", JSONArray((listOf(entry) + old.filterNot { it.song.id == song.id }).take(500)
            .map { JSONObject().put("song", it.song.json()).put("at", it.lastPlayed).put("count", it.count) })))
    }
    private fun offlineEntry(entry: JSONObject): Song? = runCatching { val song = Song.parse(entry.getJSONObject("song")); song.takeIf { Files.isRegularFile(audio(song)) && Files.size(audio(song)) == entry.getLong("bytes") } }.getOrNull()
    fun offline(): List<Song> {
        val index = directory.resolve("offline-index")
        val current = if (Files.isDirectory(index)) Files.list(index).use { paths -> paths.filter { it.fileName.toString().endsWith(".json") }.map { path -> runCatching { offlineEntry(JSONObject(Files.readString(path))) }.getOrNull() }.toList().filterNotNull() } else emptyList()
        return (current + read("offline.json").optJSONArray("items").objects().mapNotNull(::offlineEntry)).distinctBy(Song::id)
    }
    fun downloaded(song: Song, bytes: Long) = synchronized(lock) { write("offline-index/${sha256(song.id)}.json", JSONObject().put("song", song.json()).put("bytes", bytes)) }
    /** Rewrite account metadata only; cached audio bytes are never replaced or removed. */
    fun updateContentRating(id: String, rating: ContentRating, overridden: Boolean) = synchronized(lock) {
        fun update(song: Song) = song.copy(contentRating = rating, contentRatingOverride = overridden)
        fun entry(value: JSONObject): JSONObject {
            val song = value.optJSONObject("song") ?: return value
            if (song.optString("id") == id) value.put("song", update(Song.parse(song)).json())
            return value
        }
        val index = "offline-index/${sha256(id)}.json"
        if (Files.isRegularFile(directory.resolve(index))) write(index, entry(read(index)))
        val legacy = read("offline.json")
        legacy.optJSONArray("items")?.let { items ->
            for (i in 0 until items.length()) items.optJSONObject(i)?.let(::entry)
            write("offline.json", legacy)
        }
        val history = read("history.json")
        history.optJSONArray("items")?.let { items ->
            for (i in 0 until items.length()) items.optJSONObject(i)?.let(::entry)
            write("history.json", history)
        }
        val queued = "download-queue/${sha256(id)}.json"
        if (Files.isRegularFile(directory.resolve(queued))) write(queued, update(Song.parse(read(queued))).json())
    }
    fun removeOffline(song: Song) = synchronized(lock) { Files.deleteIfExists(audio(song)); Files.deleteIfExists(directory.resolve("offline-index/${sha256(song.id)}.json")); Unit }
    private fun write(name: String, value: JSONObject) {
        Files.createDirectories(directory.resolve(name).parent)
        val temp = Files.createTempFile(directory, "write-", ".tmp")
        try { Files.writeString(temp, value.toString()); Files.move(temp, directory.resolve(name), ATOMIC_MOVE, REPLACE_EXISTING) }
        finally { Files.deleteIfExists(temp) }
    }
    companion object {
        fun remember(credentials: Credentials?) {
            Files.createDirectories(DesktopPaths.root)
            val target = DesktopPaths.root.resolve("login.bin")
            if (credentials == null) { Files.deleteIfExists(target); return }
            if (!System.getProperty("os.name").startsWith("Windows")) return
            val json = JSONObject().put("server", credentials.server).put("username", credentials.username).put("password", credentials.password).toString().toByteArray()
            Files.write(target, Crypt32Util.cryptProtectData(json))
        }
        fun remembered(): Credentials? = runCatching {
            if (!System.getProperty("os.name").startsWith("Windows")) return null
            val value = JSONObject(String(Crypt32Util.cryptUnprotectData(Files.readAllBytes(DesktopPaths.root.resolve("login.bin")))))
            Credentials(value.getString("server"), value.getString("username"), value.getString("password"))
        }.getOrNull()
    }
}
