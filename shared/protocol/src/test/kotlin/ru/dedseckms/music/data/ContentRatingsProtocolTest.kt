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
            assertEquals(5, ids.length())
            val checkedAt = Song.parse(songs.getJSONObject(0)).contentRatingCheckedAt
            assertTrue(checkedAt > 0)
            assertFalse(Song.parse(songs.getJSONObject(0)).json().has("_dedsecRatingCheckedAt"))
            val cached = root(); ratings.enrich(cached); assertEquals(2, server.requestCount)
            assertEquals(checkedAt, Song.parse(cached.getJSONObject("searchResult3").getJSONArray("song").getJSONObject(0)).contentRatingCheckedAt)
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
            assertTrue(ContentPolicy.allowed(song, true)); assertEquals(2, server.requestCount)
            assertEquals(ContentRating.CLEAN, Song.parse(data.getJSONObject("searchResult3").getJSONArray("song").getJSONObject(4)).contentRating)
        }
    }
    @Test fun childRadioRejectsUnsafeSongsEvenWhenOldServerIgnoresFlag() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"test"}"""))
            server.enqueue(MockResponse().setBody("""{"songs":[{"id":"a","title":"A","explicitStatus":"explicit"},{"id":"c","title":"C","explicitStatus":"clean"},{"id":"u","title":"U"}],"analysis":{"indexed":3,"total":3}}"""))
            val api = ProfileApi(Credentials(server.url("/").toString(), "u", "p"), allowLocalHttpForTests = true)
            assertEquals(listOf("c", "u"), api.radio(MixMode.BALANCED, MixMood.ANY, childMode = true).songs.map(Song::id))
        }
    }
    @Test fun unconfirmedEntriesBeyond500And1000RemainUnratedAndPlayable() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"test"}"""))
            server.enqueue(MockResponse().setBody("""{"ratings":{"clean0":"clean"}}"""))
            val ratings = ContentRatings(Credentials(server.url("/").toString(), "u", "p"), NavApi.httpClient(), true)
            val songs = JSONArray()
            repeat(1002) { songs.put(JSONObject().put("id", "clean$it").put("title", "S").put("explicitStatus", "clean")) }
            ratings.enrich(JSONObject().put("playlist", JSONObject().put("entry", songs)))
            assertTrue(ContentPolicy.allowed(Song.parse(songs.getJSONObject(0)), true))
            for (index in listOf(500, 1000, 1001)) {
                val song = Song.parse(songs.getJSONObject(index))
                assertEquals(ContentRating.UNKNOWN, song.contentRating)
                assertTrue(ContentPolicy.allowed(song, true))
            }
            server.takeRequest()
            assertEquals(500, JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("ids").length())
        }
    }
    @Test fun timeoutDoesNotConfirmNativeClean() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"test"}"""))
            server.enqueue(MockResponse().setBody("""{"ratings":{"conflict":"clean"}}""").setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS))
            val ratings = ContentRatings(Credentials(server.url("/").toString(), "u", "p"), NavApi.httpClient(), true)
            val data = root(); ratings.enrich(data)
            assertEquals(ContentRating.UNKNOWN, Song.parse(data.getJSONObject("searchResult3").getJSONArray("song").getJSONObject(4)).contentRating)
        }
    }
    @Test fun trustedAdministratorOverrideCanClearNativeExplicitOrMarkUnknown() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"test"}"""))
            server.enqueue(MockResponse().setBody("""{"ratings":{"native":"clean","unknown":"explicit"},"overrides":{"native":"clean","unknown":"explicit"}}"""))
            val ratings = ContentRatings(Credentials(server.url("/").toString(), "u", "p"), NavApi.httpClient(), true)
            val data = root(); ratings.enrich(data)
            val songs = data.getJSONObject("searchResult3").getJSONArray("song")
            val clean = Song.parse(songs.getJSONObject(3)); val adult = Song.parse(songs.getJSONObject(2))
            assertTrue(clean.contentRatingOverride); assertTrue(ContentPolicy.allowed(clean, true))
            assertTrue(adult.contentRatingOverride); assertEquals(ContentRating.EXPLICIT, adult.contentRating)
            assertTrue(Song.parse(clean.json()).contentRatingOverride)
        }
    }
    @Test fun overrideMutationUsesFixedAuthenticatedEndpointAndResetIsExplicit() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"test"}"""))
            server.enqueue(MockResponse().setBody("""{"ratings":{"s":"explicit"},"overrides":{"s":"explicit"}}"""))
            server.enqueue(MockResponse().setBody("""{"ratings":{},"overrides":{}}"""))
            val api = ProfileApi(Credentials(server.url("/").toString(), "u", "p"), allowLocalHttpForTests = true)
            assertEquals(ContentRatingUpdate(ContentRating.EXPLICIT, true), api.setContentRating("s", ContentRating.EXPLICIT))
            assertEquals(ContentRatingUpdate(ContentRating.UNKNOWN, false), api.setContentRating("s", null))
            server.takeRequest()
            val set = server.takeRequest(); val reset = server.takeRequest()
            assertEquals("/dedsec/profile/v1/content-ratings/override", set.path)
            assertEquals("Bearer test", set.getHeader("Authorization"))
            assertEquals("source", JSONObject(reset.body.readUtf8()).getString("status"))
        }
    }

}
