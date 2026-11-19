package ru.dedseckms.music.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first

class LyricsTest {
    @Test fun cachedLyricsRemainAvailableWithoutAnOnlineSession() = runBlocking {
        val file = java.nio.file.Files.createTempFile("lyrics-offline-", ".json").toFile()
        file.writeText("""{"status":"ready","synced":false,"lines":[{"text":"Cached"}],"source":"LRCLIB","revision":"1"}""")
        val session = LyricsSession(this, null, Song("id", "Track", "Artist"), file)
        try { val result = withTimeout(3000) { session.state.first { !it.loading } }; assertEquals("Cached", result.lyrics!!.lines.single().text); assertNull(result.error) }
        finally { session.close(); file.delete() }
    }
    @Test fun ambiguousUploadFailureDoesNotReplayThePost() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"native-token"}"""))
            server.enqueue(MockResponse().setResponseCode(408)); server.enqueue(MockResponse().setBody("{}"))
            val api = ProfileApi(Credentials(server.url("/").toString(), "friend", "secret"), allowLocalHttpForTests = true)
            var opens = 0
            assertThrows(UploadRejected::class.java) { api.upload("track.mp3", 3, { opens++; byteArrayOf(1, 2, 3).inputStream() }) { _, _ -> } }
            assertEquals(1, opens); assertEquals(2, server.requestCount)
        }
    }
    @Test fun seekingAndGapsUseLastTimestampWithoutInventingPlainTiming() {
        val lyrics = Lyrics.parse(JSONObject("""{"status":"ready","synced":true,"lines":[{"startMs":1000,"text":"A"},{"startMs":3000,"text":"B"}]}"""))
        assertEquals(-1, lyrics.activeLine(999)); assertEquals(0, lyrics.activeLine(1000)); assertEquals(0, lyrics.activeLine(2999)); assertEquals(1, lyrics.activeLine(3000)); assertEquals(0, lyrics.activeLine(1200))
        assertFalse(Lyrics.parse(JSONObject("""{"status":"ready","synced":true,"lines":[{"text":"plain"}]}""")).synced)
        assertFalse(Lyrics.parse(JSONObject("""{"status":"ready","synced":true,"lines":[{"startMs":3000,"text":"B"},{"startMs":1000,"text":"A"}]}""")).synced)
    }
    @Test fun unsafeTimingIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { Lyrics.parse(JSONObject("""{"status":"ready","lines":[{"startMs":-1,"text":"bad"}]}""")) }
    }
    @Test fun nativeUploadAndLyricsUseOnlyMusicOriginWithoutASecondPasswordLogin() {
        MockWebServer().use { server ->
            listOf("""{"token":"native-token"}""", """{"schema":1,"uploads":{"mode":"navidrome","path":"/dedsec/profile/v1/uploads"}}""", "{}", """{"status":"not_found","lines":[]}""").forEach { server.enqueue(MockResponse().setBody(it)) }
            val api = ProfileApi(Credentials(server.url("/").toString(), "friend", "secret"), allowLocalHttpForTests = true)
            assertTrue(api.capabilities())
            api.upload("song.mp3", 3, { byteArrayOf(1, 2, 3).inputStream() }) { _, _ -> }
            assertEquals("not_found", api.lyrics("id").getString("status"))
            assertEquals("/auth/login", server.takeRequest().path)
            server.takeRequest()
            val upload = server.takeRequest()
            assertEquals("/dedsec/profile/v1/uploads?path=%2Fsong.mp3", upload.path)
            assertEquals("Bearer native-token", upload.getHeader("Authorization")); assertNull(upload.getHeader("X-Password")); assertEquals(3L, upload.bodySize)
            assertEquals("/dedsec/profile/v1/lyrics/id", server.takeRequest().path)
            assertThrows(IllegalArgumentException::class.java) { api.lyrics("../private") }
        }
    }
}
