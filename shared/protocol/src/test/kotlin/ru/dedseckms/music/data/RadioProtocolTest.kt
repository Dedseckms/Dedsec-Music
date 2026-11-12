package ru.dedseckms.music.data

import java.io.IOException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RadioProtocolTest {
    private fun api(server: MockWebServer) = ProfileApi(Credentials(server.url("/").toString(), "listener", "password"), allowLocalHttpForTests = true)
    private fun login(server: MockWebServer) { server.enqueue(MockResponse().setBody("""{"token":"radio-test"}""")) }
    @Test fun moodModeHistoryAndExclusionsReachTheAuthenticatedServer() {
        MockWebServer().use { server ->
            login(server)
            server.enqueue(MockResponse().setBody("""{"songs":[{"id":"chosen","title":"Song","artist":"Artist"}],"analysis":{"indexed":19,"total":100},"reason":null}"""))
            val mix = api(server).radio(MixMode.DISCOVERY, MixMood.CHILL,
                listOf(HistoryEntry(Song("recent", "Title", "Artist"), 123456789, 3)), setOf("disliked"), listOf("current"))
            server.takeRequest()
            val request = server.takeRequest(); val json = JSONObject(request.body.readUtf8())
            assertEquals("/dedsec/profile/v1/radio/mix", request.path)
            assertEquals("POST", request.method); assertEquals("Bearer radio-test", request.getHeader("Authorization"))
            assertEquals("CHILL", json.getString("mood")); assertEquals("DISCOVERY", json.getString("mode"))
            assertEquals("recent", json.getJSONArray("history").getJSONObject(0).getString("id"))
            assertEquals("current", json.getJSONArray("excludeIds").getString(0))
            assertEquals("disliked", json.getJSONArray("dislikedIds").getString(0))
            assertEquals(listOf("chosen"), mix.songs.map(Song::id)); assertEquals(19, mix.indexed); assertNull(mix.reason)
        }
    }
    @Test fun emptyOrMissingRadioNeverFallsBackToRandomSongs() {
        MockWebServer().use { server ->
            login(server)
            server.enqueue(MockResponse().setBody("""{"songs":[],"analysis":{"indexed":0,"total":100},"reason":"ANALYZING"}"""))
            server.enqueue(MockResponse().setResponseCode(404).setBody("private-server-details"))
            val client = api(server)
            val mix = client.radio(MixMode.BALANCED, MixMood.FOCUS)
            assertTrue(mix.songs.isEmpty()); assertTrue(mix.emptyMessage().contains("анализирует"))
            val error = assertThrows(IOException::class.java) { client.radio(MixMode.BALANCED, MixMood.FOCUS) }
            assertFalse(error.message!!.contains("private-server-details"))
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun oversizedResponseIsRejectedAndFeedbackPositionsAreBounded() {
        MockWebServer().use { server ->
            login(server); server.enqueue(MockResponse().setBody("x".repeat(524289)))
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            val client = api(server)
            assertThrows(IOException::class.java) { client.radio(MixMode.BALANCED, MixMood.ANY) }
            client.radioEvent("song", "skipped", -1, 180000)
            server.takeRequest(); server.takeRequest()
            val request = server.takeRequest(); val json = JSONObject(request.body.readUtf8())
            assertEquals("/dedsec/profile/v1/radio/events", request.path)
            assertEquals(0L, json.getLong("positionMs")); assertEquals("skipped", json.getString("kind"))
        }
    }
}
