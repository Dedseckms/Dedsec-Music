package ru.dedseckms.music.data

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.*

class MusicApiException(val code: Int, message: String) : IOException(message)
class PlaylistChangedException(val latest: PlaylistContents) : IOException("Плейлист изменился. Список обновлён — выбери запись ещё раз")

object ServerAddress {
    fun normalize(value: String, allowLocalHttpForTests: Boolean = false): String {
        val text = value.trim().let { if ("://" in it) it else "https://$it" }
        val url = try { text.toHttpUrl() } catch (_: IllegalArgumentException) { throw IOException("Проверь адрес сервера") }
        val localTest = allowLocalHttpForTests && url.host in setOf("localhost", "127.0.0.1")
        if ((!url.isHttps && !localTest) || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
            throw IOException("Нужен HTTPS-адрес без пароля и дополнительных параметров")
        }
        return url.toString().trimEnd('/')
    }
}

class NavApi(val credentials: Credentials, val client: OkHttpClient = httpClient(), allowLocalHttpForTests: Boolean = false) {
    private val localHttp = allowLocalHttpForTests
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()
    @Volatile private var cancelled = false
    private val base = ServerAddress.normalize(credentials.server, allowLocalHttpForTests).toHttpUrl()

    /** Cancelling a screen request also closes its HTTP exchange, rather than just hiding its result. */
    suspend fun <T> await(block: NavApi.() -> T): T = try { withTimeout(12_000) { coroutineScope {
        val scoped = NavApi(credentials, client, localHttp)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                scoped.cancelled = true
                scoped.pending.forEach { it.cancel() }
            }
        }
        try { withContext(Dispatchers.IO) {
            ensureActive()
            try { scoped.block() } catch (e: Exception) { ensureActive(); throw e }
        } }
        finally { watcher.cancel() }
    } } } catch (_: TimeoutCancellationException) { throw IOException("Сервер отвечает слишком долго. Попробуй ещё раз") }

    fun url(method: String, parameters: List<Pair<String, String>> = emptyList()): HttpUrl {
        require(method.matches(Regex("[a-zA-Z0-9]+")))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val digest = MessageDigest.getInstance("MD5").digest((credentials.password + salt).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return base.newBuilder().addPathSegments("rest/$method.view")
            .addQueryParameter("u", credentials.username).addQueryParameter("t", digest)
            .addQueryParameter("s", salt).addQueryParameter("v", "1.16.1")
            .addQueryParameter("c", "DedSecMusic").addQueryParameter("f", "json")
            .apply { parameters.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
    }

    fun call(method: String, vararg parameters: Pair<String, String>): JSONObject {
        val request = Request.Builder().url(url(method, parameters.toList())).build()
        try {
            val call = client.newCall(request)
            call.timeout().timeout(10, TimeUnit.SECONDS)
            pending.add(call)
            if (cancelled) call.cancel()
            try { call.execute().use { response ->
                if (!response.isSuccessful) throw MusicApiException(response.code, "Сервер ответил HTTP ${response.code}")
                val payload = response.body?.string() ?: throw IOException("Пустой ответ сервера")
                val root = JSONObject(payload).getJSONObject("subsonic-response")
                if (root.optString("status") != "ok") {
                    val error = root.optJSONObject("error")
                    val code = error?.optInt("code") ?: 0
                    val message = when (code) {
                        40, 41, 44 -> "Проверь логин и пароль"
                        50 -> "У этого аккаунта нет доступа к операции"
                        70 -> "Запись не найдена. Обнови библиотеку"
                        else -> "Музыкальный сервер не смог выполнить запрос ($code)"
                    }
                    throw MusicApiException(code, message)
                }
                return root
            } } finally { pending.remove(call) }
        } catch (error: MusicApiException) { throw error }
        catch (_: org.json.JSONException) { throw IOException("Сервер вернул неожиданный ответ") }
        catch (_: IOException) { throw IOException("Нет связи с музыкальным сервером") }
    }

    fun ping() { call("ping") }
    fun canonicalUsername(): String = call("getUser", "username" to credentials.username).getJSONObject("user").getString("username")
    fun search(query: String): SearchResults {
        val result = call("search3", "query" to query, "songCount" to "100", "artistCount" to "50", "albumCount" to "0").optJSONObject("searchResult3")
        return SearchResults(result?.optJSONArray("song").objects().map(Song::parse), result?.optJSONArray("artist").objects().map(Artist::parse))
    }
    fun artists(): List<Artist> = try {
        call("getArtists").optJSONObject("artists")?.optJSONArray("index").objects()
            .flatMap { it.optJSONArray("artist").objects() }.map(Artist::parse).distinctBy { it.id }
    } catch (error: MusicApiException) {
        if (error.code != 70) throw error
        // Some libraries contain track artists but no album-artist index. Search exposes track artists too.
        val result = mutableListOf<Artist>()
        var offset = 0
        do {
            val page = call("search3", "query" to "", "songCount" to "0", "albumCount" to "0",
                "artistCount" to "500", "artistOffset" to offset.toString()).optJSONObject("searchResult3")
                ?.optJSONArray("artist").objects().map(Artist::parse)
            val fresh = page.filter { artist -> result.none { it.id == artist.id } }
            result += fresh
            offset += page.size
        } while (page.size == 500 && fresh.isNotEmpty() && !cancelled)
        result.distinctBy { it.id }
    }
    fun artist(id: String): ArtistProfile = call("getArtist", "id" to id).getJSONObject("artist").let {
        ArtistProfile(Artist.parse(it), it.optJSONArray("album").objects().map(Album::parse))
    }
    fun genres(): List<Genre> = call("getGenres").optJSONObject("genres")?.optJSONArray("genre").objects()
        .map(Genre::parse).filter { it.name.isNotBlank() && it.songCount > 0 }.sortedByDescending { it.songCount }
    fun genreSongs(genre: String, offset: Int = 0): List<Song> = call("getSongsByGenre", "genre" to genre, "count" to "100", "offset" to offset.toString())
        .optJSONObject("songsByGenre")?.optJSONArray("song").objects().map(Song::parse)
    fun scanStatus(): ScanStatus = call("getScanStatus").getJSONObject("scanStatus").let { ScanStatus(it.optBoolean("scanning"), it.optInt("count")) }
    fun songs(query: String = "", offset: Int = 0, count: Int = 100): List<Song> =
        call("search3", "query" to query, "songCount" to count.toString(), "songOffset" to offset.toString(),
            "artistCount" to "0", "albumCount" to "0").optJSONObject("searchResult3")
            ?.optJSONArray("song").objects().map(Song::parse)

    fun albums(): List<Album> = call("getAlbumList2", "type" to "newest", "size" to "40")
        .optJSONObject("albumList2")?.optJSONArray("album").objects().map(Album::parse)
    fun albumSongs(id: String) = call("getAlbum", "id" to id).getJSONObject("album").optJSONArray("song").objects().map(Song::parse)
    fun favorites(): List<Song> = call("getStarred2").optJSONObject("starred2")?.optJSONArray("song").objects().map(Song::parse)
    fun random(size: Int = 200): List<Song> = call("getRandomSongs", "size" to size.coerceIn(1, 500).toString())
        .optJSONObject("randomSongs")?.optJSONArray("song").objects().map(Song::parse)
    fun playlists(): List<MusicPlaylist> = ownPlaylists(call("getPlaylists").optJSONObject("playlists")
        ?.optJSONArray("playlist").objects().map(MusicPlaylist::parse), credentials.username)
    fun playlist(id: String): PlaylistContents {
        val value = call("getPlaylist", "id" to id).getJSONObject("playlist")
        if (!value.optString("owner").equals(credentials.username, true)) throw IOException("Это плейлист другого аккаунта")
        return PlaylistContents(MusicPlaylist.parse(value), value.optJSONArray("entry").objects().map(Song::parse))
    }
    fun playlistSongs(id: String): List<Song> = playlist(id).songs

    /** Remove one source position after checking the current ordered membership, including duplicates. */
    fun removeFromPlaylist(id: String, index: Int, expectedIds: List<String>): PlaylistContents {
        if (index !in expectedIds.indices) throw IOException("Выбери запись из плейлиста заново")
        val current = playlist(id)
        if (current.playlist.readOnly) throw IOException("Этот плейлист доступен только для просмотра")
        if (current.songs.map(Song::id) != expectedIds) throw PlaylistChangedException(current)
        call("updatePlaylist", "playlistId" to id, "songIndexToRemove" to index.toString())
        val remaining = current.songs.filterIndexed { position, _ -> position != index }
        return PlaylistContents(current.playlist.copy(songCount = remaining.size), remaining)
    }
    fun createPlaylist(name: String, songIds: List<String> = emptyList()): MusicPlaylist {
        val title = name.trim().take(160)
        if (title.isEmpty()) throw IOException("Введи название плейлиста")
        val created = MusicPlaylist.parse(call("createPlaylist", "name" to title).getJSONObject("playlist"))
        // Explicitly make the playlist private even on servers with a public default.
        try { call("updatePlaylist", "playlistId" to created.id, "public" to "false") }
        catch (error: Exception) {
            runCatching { call("deletePlaylist", "id" to created.id) }
            throw error
        }
        if (songIds.isNotEmpty()) call("updatePlaylist", *(listOf("playlistId" to created.id, "public" to "false") + songIds.distinct().map { "songIdToAdd" to it }).toTypedArray())
        return created.copy(public = false, songCount = songIds.distinct().size)
    }
    fun appendToPlaylist(playlist: MusicPlaylist, song: Song) {
        if (!playlist.owner.equals(credentials.username, true)) throw IOException("Изменять можно только свои плейлисты")
        if (playlist.readOnly) throw IOException("Этот плейлист доступен только для просмотра")
        call("updatePlaylist", "playlistId" to playlist.id, "songIdToAdd" to song.id, "public" to "false")
    }
    fun setFavorite(id: String, favorite: Boolean) { call(if (favorite) "star" else "unstar", "id" to id) }
    fun scrobble(id: String) { call("scrobble", "id" to id, "submission" to "true") }
    fun requestScan() { call("startScan") }
    fun stream(song: Song): String = url("stream", listOf("id" to song.id, "format" to "raw")).toString()
    fun cover(coverArt: String, size: Int = 400): String? = coverArt.takeIf { it.isNotBlank() }
        ?.let { url("getCoverArt", listOf("id" to it, "size" to size.toString())).toString() }
    fun download(song: Song): Request = Request.Builder().url(url("download", listOf("id" to song.id))).build()

    companion object {
        private val sharedClient by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build() }
        fun httpClient(): OkHttpClient = sharedClient
    }
}
