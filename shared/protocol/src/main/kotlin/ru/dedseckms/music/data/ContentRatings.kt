package ru.dedseckms.music.data

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** One batched optional lookup per returned page, never per row or on the audio data path. */
internal class ContentRatings(credentials: Credentials, client: OkHttpClient, localHttp: Boolean) {
    private val api = ProfileApi(credentials, client.newBuilder().callTimeout(2, TimeUnit.SECONDS)
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build(), localHttp)
    private data class Entry(val rating: ContentRating, val expires: Long)
    private val cache = LinkedHashMap<String, Entry>()
    private var retryAfter = 0L

    @Synchronized fun enrich(root: JSONObject) {
        val songs = ArrayList<JSONObject>()
        fun collect(value: Any?, depth: Int = 0) {
            if (depth > 6 || songs.size >= 500) return
            when (value) {
                is JSONObject -> {
                    val id = value.optString("id")
                    if (value.has("title") && !value.optBoolean("isDir") && id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) songs += value
                    else value.keys().asSequence().toList().forEach { collect(value.opt(it), depth + 1) }
                }
                is JSONArray -> for (i in 0 until minOf(value.length(), 1000)) collect(value.opt(i), depth + 1)
            }
        }
        collect(root)
        if (songs.isEmpty()) return
        val now = System.nanoTime()
        val ids = songs.filter { ContentRating.parse(it.optString("explicitStatus")) != ContentRating.EXPLICIT }
            .map { it.getString("id") }.distinct()
        val missing = ids.filter { cache[it]?.expires?.let { expiry -> expiry > now } != true }
        if (missing.isNotEmpty() && now >= retryAfter) {
            try {
                val result = api.contentRatings(missing)
                for (id in missing) {
                    val rating = result[id] ?: ContentRating.UNKNOWN
                    cache[id] = Entry(rating, now + TimeUnit.SECONDS.toNanos(if (rating == ContentRating.UNKNOWN) 30 else 300))
                }
                while (cache.size > 20000) cache.remove(cache.keys.first())
            } catch (_: Exception) {
                // Old servers and temporary outages remain UNKNOWN, never implicitly clean.
                retryAfter = now + TimeUnit.SECONDS.toNanos(30)
            }
        }
        for (song in songs) {
            val native = ContentRating.parse(song.optString("explicitStatus"))
            if (native == ContentRating.EXPLICIT) continue
            val found = cache[song.optString("id")]?.takeIf { it.expires > now }?.rating ?: continue
            if (found == ContentRating.EXPLICIT || native == ContentRating.UNKNOWN && found == ContentRating.CLEAN) song.put("explicitStatus", found.wire)
        }
    }
}
