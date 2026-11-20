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
    @Test fun savedTimingSurvivesSessionReopenWithoutGrantingCachedWriteAccess() = runBlocking {
        MockWebServer().use { server ->
            val file = java.nio.file.Files.createTempFile("lyrics-persisted-", ".json").toFile().apply { delete() }
            val original = JSONObject().put("status", "ready").put("synced", true).put("revision", "a".repeat(64))
                .put("canEditTiming", true).put("timingOffsetMs", 0).put("lines", org.json.JSONArray("""[{"startMs":1000,"text":"A"}]"""))
            server.enqueue(MockResponse().setBody("""{"token":"native-token"}"""))
            server.enqueue(MockResponse().setBody(original.toString()))
            server.enqueue(MockResponse().setBody(JSONObject(original.toString()).put("timingOffsetMs", 500).toString()))
            val api = ProfileApi(Credentials(server.url("/").toString(), "owner", "secret"), allowLocalHttpForTests = true)
            val session = LyricsSession(this, api, Song("id", "Track", "Artist"), file)
            try {
                withTimeout(3000) { session.state.first { !it.loading } }
                session.saveTiming(500)
                val result = withTimeout(3000) { session.state.first { !it.savingTiming && it.lyrics?.timingOffsetMs == 500L } }
                assertTrue(result.lyrics!!.canEditTiming)
            } finally { session.close() }
            val reopened = LyricsSession(this, null, Song("id", "Track", "Artist"), file)
            try {
                val saved = withTimeout(3000) { reopened.state.first { !it.loading } }.lyrics!!
                assertEquals(500L, saved.timingOffsetMs); assertFalse(saved.canEditTiming)
            } finally { reopened.close(); file.delete() }
        }
    }
    @Test fun serverTimingDefaultsToReadOnlyAndRejectsUnsafeOffsets() {
        val old = Lyrics.parse(JSONObject("""{"status":"ready","lines":[]}"""))
        assertEquals(0L, old.timingOffsetMs); assertFalse(old.canEditTiming)
        val corrected = Lyrics.parse(JSONObject("""{"status":"ready","synced":true,"timingOffsetMs":750,"canEditTiming":true,"lines":[{"startMs":1000,"text":"A"}]}"""))
        assertEquals(0, corrected.activeLine(250 + corrected.timingOffsetMs)); assertTrue(corrected.canEditTiming)
        assertThrows(IllegalArgumentException::class.java) { Lyrics.parse(JSONObject("""{"status":"ready","timingOffsetMs":10001}""")) }
    }
    @Test fun timingSaveUsesFixedOriginAndPropagatesRevisionConflict() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"native-token"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"ready","timingOffsetMs":500,"lines":[]}"""))
            server.enqueue(MockResponse().setResponseCode(409))
            val api = ProfileApi(Credentials(server.url("/").toString(), "owner", "secret"), allowLocalHttpForTests = true)
            val revision = "a".repeat(64)
            assertEquals(500, api.saveLyricsTiming("song-id", 500, revision).getInt("timingOffsetMs"))
            server.takeRequest(); val post = server.takeRequest()
            assertEquals("POST", post.method); assertEquals("/dedsec/profile/v1/lyrics/song-id/timing", post.path)
            assertEquals("Bearer native-token", post.getHeader("Authorization"))
            val body = JSONObject(post.body.readUtf8()); assertEquals(500, body.getInt("offsetMs")); assertEquals(revision, body.getString("revision"))
            assertThrows(java.io.IOException::class.java) { api.saveLyricsTiming("song-id", 750, revision) }
            assertThrows(IllegalArgumentException::class.java) { api.saveLyricsTiming("../private", 0, revision) }
            assertThrows(IllegalArgumentException::class.java) { api.saveLyricsTiming("song-id", 10001, revision) }
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun cachedOwnerCapabilityNeverGrantsOfflineEditing() = runBlocking {
        val file = java.nio.file.Files.createTempFile("lyrics-owner-offline-", ".json").toFile()
        file.writeText("""{"status":"ready","synced":true,"timingOffsetMs":500,"canEditTiming":true,"lines":[{"startMs":1000,"text":"A"}]}""")
        val session = LyricsSession(this, null, Song("id", "Track", "Artist"), file)
        try {
            val result = withTimeout(3000) { session.state.first { !it.loading } }
            assertEquals(500L, result.lyrics!!.timingOffsetMs); assertFalse(result.lyrics.canEditTiming)
            session.saveTiming(750); assertFalse(session.state.value.savingTiming)
        } finally { session.close(); file.delete() }
    }
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
