package ru.dedseckms.music.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class LyricsBatchTest {
    @Test fun alignmentProgressIsOptionalAndControlsRemainOnOwnerEndpoint() {
        MockWebServer().use { server ->
            val stats = """{"total":10,"checked":7,"synced":3,"plain":1,"instrumental":1,"notFound":1,"errors":1,"pending":3,"running":true,"discovering":false}"""
            assertNull(LyricsBatchProgress.parse(JSONObject(stats)).alignment)
            val nested = JSONObject("""{"enabled":true,"total":4,"pending":1,"done":1,"skipped":1,"errors":0,"active":1}""")
            server.enqueue(MockResponse().setBody("""{"token":"native-token"}"""))
            server.enqueue(MockResponse().setBody(JSONObject(stats).put("alignment", nested).toString()))
            val api = ProfileApi(Credentials(server.url("/").toString(), "owner", "secret"), allowLocalHttpForTests = true)
            val alignment = api.lyricsBatch("align_start").alignment!!
            assertTrue(alignment.enabled); assertEquals(1, alignment.active); assertEquals(1, alignment.skipped)
            server.takeRequest(); val request = server.takeRequest()
            assertEquals("/dedsec/profile/v1/lyrics-batch", request.path)
            assertEquals("align_start", JSONObject(request.body.readUtf8()).getString("action"))
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
        }
    }
    @Test fun completingPollPreservesQueuedActionAndIgnoresDuplicateClick() = runBlocking {
        MockWebServer().use { server ->
            val stats = """{"total":10,"checked":0,"synced":0,"plain":0,"instrumental":0,"notFound":0,"errors":0,"pending":10,"running":false,"discovering":false}"""
            server.enqueue(MockResponse().setBody("""{"token":"native-token"}"""))
            server.enqueue(MockResponse().setBody(stats).setBodyDelay(300, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody(JSONObject(stats).put("running", true).toString()).setBodyDelay(500, TimeUnit.MILLISECONDS))
            val api = ProfileApi(Credentials(server.url("/").toString(), "owner", "secret"), allowLocalHttpForTests = true)
            val session = LyricsBatchSession(this, api)
            try {
                withTimeout(3000) { while (server.requestCount < 2) delay(10) }
                session.action("start")
                withTimeout(3000) { session.state.first { it.progress != null && it.acting } }
                session.action("pause")
                val completed = withTimeout(3000) { session.state.first { it.progress?.running == true && !it.acting } }
                assertNull(completed.error); assertEquals(3, server.requestCount)
                server.takeRequest(); server.takeRequest()
                assertEquals("start", JSONObject(server.takeRequest().body.readUtf8()).getString("action"))
            } finally { session.close() }
        }
    }
    @Test fun progressAndPauseUseSameMusicOriginAndNativeAuthorization() {
        MockWebServer().use { server ->
            val stats = """{"total":10,"checked":7,"synced":3,"plain":1,"instrumental":1,"notFound":1,"errors":1,"pending":3,"running":true,"discovering":false}"""
            server.enqueue(MockResponse().setBody("""{"token":"native-token"}"""))
            server.enqueue(MockResponse().setBody(stats))
            server.enqueue(MockResponse().setBody(JSONObject(stats).put("running", false).toString()))
            val api = ProfileApi(Credentials(server.url("/").toString(), "owner", "secret"), allowLocalHttpForTests = true)
            val progress = api.lyricsBatch()
            assertEquals(7, progress.checked); assertEquals(3, progress.synced); assertTrue(progress.running)
            assertFalse(api.lyricsBatch("pause").running)
            server.takeRequest()
            val get = server.takeRequest(); assertEquals("/dedsec/profile/v1/lyrics-batch", get.path); assertEquals("GET", get.method)
            val post = server.takeRequest(); assertEquals(get.path, post.path); assertEquals("Bearer native-token", post.getHeader("Authorization"))
            assertEquals("pause", JSONObject(post.body.readUtf8()).getString("action"))
            assertThrows(IllegalArgumentException::class.java) { api.lyricsBatch("../../admin") }
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun forbiddenProgressNeverReturnsOtherUsersLibraryCounts() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"native-token"}""")); server.enqueue(MockResponse().setResponseCode(403))
            val api = ProfileApi(Credentials(server.url("/").toString(), "friend", "secret"), allowLocalHttpForTests = true)
            assertThrows(java.io.IOException::class.java) { api.lyricsBatch() }
        }
    }
}
