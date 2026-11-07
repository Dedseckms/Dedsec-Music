package ru.dedseckms.music.data

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

const val DEFAULT_MUSIC_SERVER = "https://music.example.org"
const val DEFAULT_UPLOAD_SERVER = "https://upload.music.example.org"

class Credentials(val server: String, val username: String, val password: String) {
    val accountId: String = sha256(server.trimEnd('/') + "|" + username.lowercase()).take(32)
    override fun toString() = "Credentials(<redacted>)"
}

fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

data class Song(
    val id: String, val title: String, val artist: String, val album: String = "",
    val albumId: String = "", val artistId: String = "", val coverArt: String = "",
    val duration: Int = 0, val genre: String = "", val suffix: String = "m4a",
    val contentType: String = "audio/mp4", val size: Long = 0,
    val starred: Boolean = false, val playCount: Int = 0, val rating: Int = 0,
    val sourceFilename: String = "",
    val genres: List<String> = emptyList(),
) {
    fun json() = JSONObject().put("id", id).put("title", title).put("artist", artist)
        .put("album", album).put("albumId", albumId).put("artistId", artistId).put("coverArt", coverArt)
        .put("duration", duration).put("genre", genre).put("suffix", suffix).put("contentType", contentType)
        .put("size", size).put("starred", if (starred) "true" else "").put("playCount", playCount).put("userRating", rating).put("sourceFilename", sourceFilename)
        .put("genres", JSONArray(genres.map { JSONObject().put("name", it) }))

    companion object {
        fun parse(value: JSONObject) = Song(
            id = value.getString("id"), title = value.optString("title", "Без названия"),
            artist = value.optString("artist", "Неизвестный исполнитель"), album = value.optString("album"),
            albumId = value.optString("albumId"), artistId = value.optString("artistId"),
            coverArt = value.optString("coverArt"), duration = value.optInt("duration"),
            genre = value.optString("genre").ifBlank { value.optJSONArray("genres").objects().firstOrNull()?.optString("name").orEmpty() }, suffix = value.optString("suffix", "m4a"),
            contentType = value.optString("contentType", "audio/mp4"), size = value.optLong("size"),
            starred = value.optString("starred").isNotEmpty(), playCount = value.optInt("playCount"),
            rating = value.optInt("userRating"),
            sourceFilename = value.optString("sourceFilename", value.optString("path")).substringAfterLast('/').substringAfterLast('\\'),
            genres = value.optJSONArray("genres").objects().map { it.optString("name") }.filter(String::isNotBlank).distinct(),
        )
    }
}

data class Artist(val id: String, val name: String, val coverArt: String = "", val albumCount: Int = 0) {
    companion object {
        fun parse(value: JSONObject) = Artist(value.getString("id"), value.optString("name"), value.optString("coverArt"), value.optInt("albumCount"))
    }
}
data class Genre(val name: String, val songCount: Int) {
    companion object { fun parse(value: JSONObject) = Genre(value.optString("value"), value.optInt("songCount")) }
}
data class SearchResults(val songs: List<Song>, val artists: List<Artist>)
data class ArtistProfile(val artist: Artist, val albums: List<Album> = emptyList(), val songs: List<Song> = emptyList(),
                         val loading: Boolean = true, val error: String? = null)
enum class SearchCategory { ALL, SONGS, ARTISTS, GENRES }
data class ScanStatus(val scanning: Boolean, val count: Int)

data class Album(val id: String, val name: String, val artist: String, val coverArt: String, val songCount: Int) {
    companion object {
        fun parse(value: JSONObject) = Album(value.getString("id"), value.optString("name"),
            value.optString("artist"), value.optString("coverArt"), value.optInt("songCount"))
    }
}

data class MusicPlaylist(val id: String, val name: String, val owner: String, val songCount: Int, val coverArt: String, val public: Boolean, val readOnly: Boolean = false) {
    companion object {
        fun parse(value: JSONObject) = MusicPlaylist(value.getString("id"), value.optString("name"),
            value.optString("owner"), value.optInt("songCount"), value.optString("coverArt"), value.optBoolean("public"), value.optBoolean("readonly"))
    }
}

data class PlaylistContents(val playlist: MusicPlaylist, val songs: List<Song>)

fun ownPlaylists(items: List<MusicPlaylist>, username: String) = items.filter { it.owner.equals(username, ignoreCase = true) }

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList()
    else (0 until length()).mapNotNull { optJSONObject(it) }

data class HistoryEntry(val song: Song, val lastPlayed: Long, val count: Int)
enum class MixMode { BALANCED, FAVORITES, DISCOVERY }

/** A lightweight per-account selector. Audio is never sent to an analysis service. */
object MixEngine {
    fun build(candidates: List<Song>, favorites: Set<String>, history: List<HistoryEntry>,
              disliked: Set<String>, mode: MixMode = MixMode.BALANCED, size: Int = 40,
              random: kotlin.random.Random = kotlin.random.Random.Default, mood: MixMood = MixMood.ANY): List<Song> {
        val recent = history.sortedByDescending { it.lastPlayed }.take(30).map { it.song.id }.toSet()
        val preferredGenres = history.take(100).map { it.song.genre }.filter { it.isNotBlank() }.toSet()
        val source = candidates.distinctBy { it.id }.filter { it.id !in disliked && (mode != MixMode.FAVORITES || it.id in favorites) }
        val pool = source.toMutableList()
        val result = mutableListOf<Song>()
        while (pool.isNotEmpty() && result.size < size) {
            val spaced = pool.filter { song -> result.takeLast(2).none { it.artist.equals(song.artist, true) } }
            val available = spaced.ifEmpty { pool }
            val weights = available.map { song ->
                var weight = 1.0
                if (song.id in favorites) weight += if (mode == MixMode.DISCOVERY) 0.2 else 2.5
                if (song.genre.isNotBlank() && song.genre in preferredGenres) weight += 0.6
                if (song.id in recent) weight *= 0.12
                if (mode == MixMode.DISCOVERY && history.none { it.song.id == song.id }) weight *= 3.0
                weight * MoodAffinity.weight(song, mood)
            }
            var ticket = random.nextDouble() * weights.sum()
            var index = weights.lastIndex
            for (i in weights.indices) { ticket -= weights[i]; if (ticket <= 0) { index = i; break } }
            val chosen = available[index]
            result += chosen
            pool.removeAll { it.id == chosen.id }
        }
        return result
    }
}
