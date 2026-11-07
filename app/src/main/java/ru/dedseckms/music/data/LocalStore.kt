package ru.dedseckms.music.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** Android Keystore protects credentials; backups and plaintext credential files are disabled. */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_sessions", Context.MODE_PRIVATE)
    private val decrypted = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Credentials>>()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("dedsec_music_credentials_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("dedsec_music_credentials_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun write(name: String, credentials: Credentials) {
        val data = JSONObject().put("server", credentials.server).put("username", credentials.username).put("password", credentials.password)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(data.toString().toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }
    private fun read(name: String): Credentials? {
        val encoded = prefs.getString(name, null) ?: return null
        decrypted[name]?.takeIf { it.first == encoded }?.let { return it.second }
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            val data = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size))))
            Credentials(data.getString("server"), data.getString("username"), data.getString("password")).also { decrypted[name] = encoded to it }
        } catch (_: Exception) { null }
    }
    fun save(credentials: Credentials) {
        write("music_${credentials.accountId}", credentials)
        prefs.edit().putString("active", credentials.accountId).apply()
    }
    fun activeId(): String? = prefs.getString("active", null)
    fun active(): Credentials? = activeId()?.let(::load)
    fun load(accountId: String): Credentials? = read("music_$accountId")
    fun saveUpload(accountId: String, credentials: Credentials) = write("upload_$accountId", credentials)
    fun upload(accountId: String): Credentials? = read("upload_$accountId")
    fun logout() {
        decrypted.clear()
        activeId()?.let { id -> prefs.edit().remove("music_$id").remove("upload_$id").remove("active").apply() }
    }
}

/** Every cache and preference is namespaced by server and account, including offline files. */
class LocalStore(private val context: Context, val accountId: String) {
    init { require(accountId.matches(Regex("[a-f0-9]{32}"))) }
    private val directory = File(context.filesDir, "accounts/$accountId").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("account_$accountId", Context.MODE_PRIVATE)
    private val lock = locks.computeIfAbsent(accountId) { Any() }
    private fun json(name: String): JSONObject = try { JSONObject(File(directory, name).readText()) } catch (_: Exception) { JSONObject() }
    private fun save(name: String, value: JSONObject) {
        val destination = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        temporary.writeText(value.toString())
        check(temporary.renameTo(destination)) { "Не удалось сохранить данные приложения" }
    }
    fun cachedSongs(): List<Song> = json("library.json").optJSONArray("songs").objects().map(Song::parse)
    fun cachedArtists(): List<Artist> = json("artists.json").optJSONArray("items").objects().map(Artist::parse)
    fun cacheArtists(items: List<Artist>) = synchronized(lock) { save("artists.json", JSONObject().put("items", JSONArray(items.map {
        JSONObject().put("id", it.id).put("name", it.name).put("coverArt", it.coverArt).put("albumCount", it.albumCount)
    }))) }
    fun cachedGenres(): List<Genre> = json("genres.json").optJSONArray("items").objects().map(Genre::parse)
    fun cacheGenres(items: List<Genre>) = synchronized(lock) { save("genres.json", JSONObject().put("items", JSONArray(items.map {
        JSONObject().put("value", it.name).put("songCount", it.songCount)
    }))) }
    fun cacheSongs(songs: List<Song>) = synchronized(lock) { save("library.json", JSONObject().put("songs", JSONArray(songs.distinctBy { it.id }.take(2000).map { it.json() }))) }
    fun cachedFavorites(): List<Song> = json("favorites.json").optJSONArray("songs").objects().map(Song::parse)
    fun cacheFavorites(songs: List<Song>) = synchronized(lock) { save("favorites.json", JSONObject().put("songs", JSONArray(songs.distinctBy { it.id }.map { it.json() }))) }
    fun history(): List<HistoryEntry> = json("history.json").optJSONArray("entries").objects().mapNotNull { value ->
        try { HistoryEntry(Song.parse(value.getJSONObject("song")), value.getLong("lastPlayed"), value.optInt("count", 1)) } catch (_: Exception) { null }
    }
    fun record(song: Song) = synchronized(lock) {
        val previous = history()
        val count = (previous.find { it.song.id == song.id }?.count ?: 0) + 1
        val entries = listOf(HistoryEntry(song, System.currentTimeMillis(), count)) + previous.filterNot { it.song.id == song.id }
        save("history.json", JSONObject().put("entries", JSONArray(entries.take(500).map {
            JSONObject().put("song", it.song.json()).put("lastPlayed", it.lastPlayed).put("count", it.count)
        })))
    }
    fun disliked(): Set<String> = prefs.getStringSet("disliked", emptySet())?.toSet() ?: emptySet()
    fun dislike(id: String) = synchronized(lock) { prefs.edit().putStringSet("disliked", disliked() + id).apply() }
    fun pendingFavorites(): Map<String, Boolean> = json("favorites-pending.json").let { data -> data.keys().asSequence().associateWith { data.getBoolean(it) } }
    fun pendingFavorite(id: String, favorite: Boolean) = synchronized(lock) {
        val value = json("favorites-pending.json").put(id, favorite)
        save("favorites-pending.json", value)
    }
    fun favoriteSynced(id: String, value: Boolean) = synchronized(lock) {
        val data = json("favorites-pending.json")
        if (data.has(id) && data.getBoolean(id) == value) data.remove(id)
        save("favorites-pending.json", data)
    }
    fun wifiOnly(): Boolean = prefs.getBoolean("wifi_only", true)
    fun trackGain(id: String): Int = prefs.getInt("gain_${sha256(id)}", 0).coerceIn(-6, 6)
    fun trackGain(id: String, decibels: Int) { prefs.edit().putInt("gain_${sha256(id)}", decibels.coerceIn(-6, 6)).apply() }
    fun wifiOnly(value: Boolean) { prefs.edit().putBoolean("wifi_only", value).apply() }
    fun audioFile(id: String): File = File(directory, "offline/${sha256(id)}.audio").also { it.parentFile?.mkdirs() }
    fun offline(): List<Song> = json("offline.json").optJSONArray("entries").objects().mapNotNull { value ->
        val song = try { Song.parse(value.getJSONObject("song")) } catch (_: Exception) { return@mapNotNull null }
        val file = audioFile(song.id)
        song.takeIf { file.isFile && file.length() > 0 && file.length() == value.optLong("bytes") }
    }
    fun downloaded(song: Song, bytes: Long) = synchronized(lock) {
        val entries = json("offline.json").optJSONArray("entries").objects().filterNot { it.optJSONObject("song")?.optString("id") == song.id }
        val added = JSONObject().put("song", song.json()).put("bytes", bytes)
        save("offline.json", JSONObject().put("entries", JSONArray(entries + added)))
    }
    fun removeDownload(id: String) = synchronized(lock) {
        val file = audioFile(id)
        if (file.exists() && !file.delete()) error("Не удалось удалить офлайн-файл")
        val entries = json("offline.json").optJSONArray("entries").objects().filterNot { it.optJSONObject("song")?.optString("id") == id }
        save("offline.json", JSONObject().put("entries", JSONArray(entries)))
    }
    companion object { private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>() }
}
