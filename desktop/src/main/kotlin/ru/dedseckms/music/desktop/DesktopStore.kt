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
class DesktopStore(val credentials: Credentials) {
    val directory = DesktopPaths.account(credentials.accountId)
    private val lock = Any()
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
    fun offline(): List<Song> = read("offline.json").optJSONArray("items").objects().mapNotNull { entry ->
        runCatching { val song = Song.parse(entry.getJSONObject("song")); song.takeIf { Files.isRegularFile(audio(song)) && Files.size(audio(song)) == entry.getLong("bytes") } }.getOrNull()
    }
    fun downloaded(song: Song, bytes: Long) = synchronized(lock) {
        val old = read("offline.json").optJSONArray("items").objects().filterNot { it.getJSONObject("song").getString("id") == song.id }
        write("offline.json", JSONObject().put("items", JSONArray(old + JSONObject().put("song", song.json()).put("bytes", bytes))))
    }
    fun removeOffline(song: Song) = synchronized(lock) {
        Files.deleteIfExists(audio(song))
        val old = read("offline.json").optJSONArray("items").objects().filterNot { it.getJSONObject("song").getString("id") == song.id }
        write("offline.json", JSONObject().put("items", JSONArray(old)))
    }
    private fun write(name: String, value: JSONObject) {
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
