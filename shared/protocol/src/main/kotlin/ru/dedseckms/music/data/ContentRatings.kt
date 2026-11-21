package ru.dedseckms.music.data

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** Bounded optional metadata lookup, independent of the audio data path. */
internal class ContentRatings(credentials: Credentials, client: OkHttpClient, localHttp: Boolean) {
    private val api = ProfileApi(credentials, client.newBuilder().callTimeout(2, TimeUnit.SECONDS)
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build(), localHttp)
    private data class Entry(val rating: ContentRating, val overridden: Boolean, val expires: Long, val checkedAt: Long)
    private val cache = LinkedHashMap<String, Entry>()
    private var retryAfter = 0L
    private var unsupported = false
    @Synchronized fun invalidate(id: String) { cache.remove(id); retryAfter = 0L }

    @Synchronized fun enrich(root: JSONObject) {
        val songs = ArrayList<JSONObject>()
        fun collect(value: Any?, depth: Int = 0) {
            if (depth > 6) return
            when (value) {
                is JSONObject -> {
                    val id = value.optString("id")
                    if (value.has("title") && !value.optBoolean("isDir") && id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) songs += value
                    else value.keys().asSequence().toList().forEach { collect(value.opt(it), depth + 1) }
                }
                is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i), depth + 1)
            }
        }
        collect(root)
        if (songs.isEmpty()) return
        val now = System.nanoTime()
        val ids = songs.map { it.getString("id") }.distinct()
        val missing = ids.filter { cache[it]?.expires?.let { expiry -> expiry > now } != true }.take(500)
        if (missing.isNotEmpty() && now >= retryAfter) {
            try {
                val checkedAt = System.currentTimeMillis()
                val result = api.contentRatings(missing)
                unsupported = false
                for (id in missing) {
                    val rating = result.ratings[id] ?: ContentRating.UNKNOWN
                    cache[id] = Entry(rating, id in result.overrides, now + TimeUnit.SECONDS.toNanos(if (rating == ContentRating.UNKNOWN) 30 else 300), checkedAt)
                }
                while (cache.size > 20000) cache.remove(cache.keys.first())
            } catch (error: Exception) {
                // A temporary failure must not masquerade as an old server without an overlay.
                unsupported = error is ContentRatingsUnavailable && error.unsupported
                retryAfter = now + TimeUnit.SECONDS.toNanos(30)
            }
        }
        for (song in songs) {
            val native = ContentRating.parse(song.optString("explicitStatus"))
            val found = cache[song.optString("id")]?.takeIf { it.expires > now }
            val merged = when {
                found?.overridden == true -> found.rating
                native == ContentRating.EXPLICIT || found?.rating == ContentRating.EXPLICIT -> ContentRating.EXPLICIT
                found?.rating == ContentRating.CLEAN -> ContentRating.CLEAN
                native == ContentRating.CLEAN && unsupported -> ContentRating.CLEAN
                else -> ContentRating.UNKNOWN
            }
            song.put("explicitStatus", merged.wire).put("contentRatingOverride", found?.overridden == true)
                .put("_dedsecRatingCheckedAt", found?.checkedAt ?: 0L)
        }
    }
}
