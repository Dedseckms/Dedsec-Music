package ru.dedseckms.music.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImportProtocolTest {
    private fun job(phase: String = "download", state: String = "running") = JSONObject("""{
        "id":"job-123", "state":"$state", "phase":"$phase", "discovered":12, "total":10,
        "downloaded":2, "skipped":4, "failed":1, "unavailable":1, "excluded":2, "processed":8,
        "totalBytes":1234, "current":{"title":"Song", "artist":"Artist"}, "errorCode":null,
        "playlistCount":3,"completedPlaylistCount":1,"pendingPlaylistCount":2,"missingPlaylistTracks":0
    }""")
    private fun api(server: MockWebServer) = ProfileApi(Credentials(server.url("/").toString(), "anna", "test-password"), allowLocalHttpForTests = true)
    @Test fun progressUsesEligibleTracksAndDiscoveryNeverPretendsToKnowTheTotal() {
        assertEquals(.8f, ImportJob.parse(job()).fraction!!, .0001f)
        assertNull(ImportJob.parse(job("inventory")).fraction)
        assertNull(ImportJob.parse(job("classification")).fraction)
        assertTrue(ImportJob.parse(job("playlists")).syncingPlaylists)
        assertEquals(1L, ImportJob.parse(job()).completedPlaylistCount)
        assertTrue(ImportJob.parse(job("paused", "paused_auth")).resumable)
        assertThrows(IllegalArgumentException::class.java) { ImportJob.parse(job().put("id", "../foreign-job")) }
        assertThrows(IllegalArgumentException::class.java) { ImportJob.parse(job().put("total", -1)) }
    }
    @Test fun tokenOnlyAppearsInPostBodyAndRedirectNeverReceivesCredentials() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"native-test"}"""))
            server.enqueue(MockResponse().setBody(JSONObject().put("job", job()).toString()).setResponseCode(202))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://untrusted.invalid/secret"))
            val client = api(server)
            assertEquals("job-123", client.startImport("source-test-secret").id)
            assertEquals("/auth/login", server.takeRequest().path)
            val imported = server.takeRequest()
            assertEquals("/dedsec/import/v1/yandex/jobs", imported.path)
            assertEquals("Bearer native-test", imported.getHeader("Authorization"))
            assertEquals("source-test-secret", JSONObject(imported.body.readUtf8()).getString("oauthToken"))
            assertThrows(IOException::class.java) { client.currentImport() }
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun closingTheClientObserverNeverCancelsTheServerTask() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"native-test"}"""))
            server.enqueue(MockResponse().setBody(JSONObject().put("job", job()).toString()))
            val session = YandexImportSession(this, api(server))
            session.refresh()
            withContext(Dispatchers.IO) { repeat(2) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) } }
            withTimeout(3000) { while (session.state.value.job == null) delay(10) }
            session.close(); delay(50)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun errorBodiesCannotEchoSourceSecretsIntoUi() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"native-test"}"""))
            server.enqueue(MockResponse().setResponseCode(422).setBody("source-test-secret"))
            val error = assertThrows(IOException::class.java) { api(server).startImport("source-test-secret") }
            assertFalse(error.message!!.contains("source-test-secret"))
        }
    }
}
