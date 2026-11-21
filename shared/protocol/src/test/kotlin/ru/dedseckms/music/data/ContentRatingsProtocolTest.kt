package ru.dedseckms.music.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContentRatingsProtocolTest {
    private fun root() = JSONObject().put("searchResult3", JSONObject().put("song", JSONArray().put(JSONObject().put("id", "adult").put("title", "A"))
        .put(JSONObject().put("id", "clean").put("title", "B")).put(JSONObject().put("id", "unknown").put("title", "C"))
        .put(JSONObject().put("id", "native").put("title", "D").put("explicitStatus", "explicit")).put(JSONObject().put("id", "conflict").put("title", "E").put("explicitStatus", "clean"))))
    @Test fun batchedRatingsPreserveNativeAndUnknownAndReuseAccountCache() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"test"}"""))
            server.enqueue(MockResponse().setBody("""{"ratings":{"adult":"explicit","clean":"clean","outsider":"clean","conflict":"explicit"}}"""))
            val ratings = ContentRatings(Credentials(server.url("/").toString(), "u", "p"), NavApi.httpClient(), true)
            val data = root(); ratings.enrich(data)
            val songs = data.getJSONObject("searchResult3").getJSONArray("song")
            assertEquals("explicit", songs.getJSONObject(0).getString("explicitStatus"))
            assertEquals("clean", songs.getJSONObject(1).getString("explicitStatus"))
            assertEquals("", songs.getJSONObject(2).optString("explicitStatus"))
            assertEquals("explicit", songs.getJSONObject(3).getString("explicitStatus"))
            val conflict = Song.parse(songs.getJSONObject(4))
            assertEquals(ContentRating.EXPLICIT, conflict.contentRating)
            assertFalse(ContentPolicy.allowed(conflict, true))
            server.takeRequest()
            val request = server.takeRequest()
            assertEquals("/dedsec/profile/v1/content-ratings", request.path)
            assertEquals("Bearer test", request.getHeader("Authorization"))
            val ids = JSONObject(request.body.readUtf8()).getJSONArray("ids")
            assertEquals(4, ids.length())
            ratings.enrich(root()); assertEquals(2, server.requestCount)
        }
    }
    @Test fun unsupportedServerNeverTurnsUnknownIntoClean() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"test"}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            val ratings = ContentRatings(Credentials(server.url("/").toString(), "u", "p"), NavApi.httpClient(), true)
            val data = root(); ratings.enrich(data); ratings.enrich(root())
            val song = Song.parse(data.getJSONObject("searchResult3").getJSONArray("song").getJSONObject(0))
            assertEquals(ContentRating.UNKNOWN, song.contentRating)
            assertFalse(ContentPolicy.allowed(song, true)); assertEquals(2, server.requestCount)
        }
    }
    @Test fun childRadioRejectsUnsafeSongsEvenWhenOldServerIgnoresFlag() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"test"}"""))
            server.enqueue(MockResponse().setBody("""{"songs":[{"id":"a","title":"A","explicitStatus":"explicit"},{"id":"c","title":"C","explicitStatus":"clean"},{"id":"u","title":"U"}],"analysis":{"indexed":3,"total":3}}"""))
            val api = ProfileApi(Credentials(server.url("/").toString(), "u", "p"), allowLocalHttpForTests = true)
            assertEquals(listOf("c"), api.radio(MixMode.BALANCED, MixMood.ANY, childMode = true).songs.map(Song::id))
        }
    }
}
