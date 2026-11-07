package ru.dedseckms.music.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PerformanceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun api(server: MockWebServer) = NavApi(Credentials(server.url("/").toString(), "user", "test-only"), allowLocalHttpForTests = true)

    @Test fun listAndFullPlayerSizesShareOneEncodedNetworkSource() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("shared-image"))
        server.start()
        try {
            val nav = api(server)
            val cache = CoverStore(temporary.newFolder(), { nav.credentials }, { api(server) })
            val files = coroutineScope { listOf(192, 384, 768).map { size -> async { cache.file(nav.credentials.accountId, "same-cover", size) } }.awaitAll() }
            assertEquals(1, files.map { it.path }.distinct().size)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun updateReusesExistingLargeCoverWithoutDownloadingItAgain() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val nav = api(server)
            val directory = temporary.newFolder()
            val old = java.io.File(directory, "${sha256("${nav.credentials.accountId}:old-cover:768")}.img").apply { writeText("existing-large") }
            val cache = CoverStore(directory, { nav.credentials }, { api(server) })
            assertEquals(old, cache.file(nav.credentials.accountId, "old-cover", 192))
            assertEquals(old, cache.file(nav.credentials.accountId, "old-cover", 768))
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun cancellingSearchClosesTheHttpExchangeAndDoesNotWaitForItsReadTimeout() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val nav = api(server)
            val job = launch { nav.await { search("cancel me") } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            withTimeout(1500) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { server.shutdown() }
    }

    @Test fun missingAlbumArtistIndexFallsBackToSearchArtists() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"failed","error":{"code":70}}}"""))
        server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","searchResult3":{"artist":[{"id":"a","name":"Artist"}]}}}"""))
        server.start()
        try { assertEquals("a", api(server).artists().single().id) } finally { server.shutdown() }
    }

    @Test fun duplicateCoverLoadsShareOneRequestAndReuseDiskAfterAppRestart() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("image-data").setBodyDelay(100, TimeUnit.MILLISECONDS))
        server.start()
        try {
            val nav = api(server)
            val directory = temporary.newFolder()
            fun cache() = CoverStore(directory, { nav.credentials }, { api(server) })
            val cache = cache()
            assertNull(cache.peek(nav.credentials.accountId, "cover", 192))
            val results = coroutineScope { (1..20).map { async { cache.file(nav.credentials.accountId, "cover", 192) } }.awaitAll() }
            assertEquals(1, results.map { it.path }.distinct().size)
            assertEquals(results[0], cache.peek(nav.credentials.accountId, "cover", 192))
            assertNull(cache.peek("another-account", "cover", 192))
            assertEquals(results[0], cache.peek(nav.credentials.accountId, "cover", 768))
            assertEquals("image-data", cache().file(nav.credentials.accountId, "cover", 192).readText())
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun coverRequestsNeverExceedServerConcurrencyAndKeepAccountsSeparate() = runBlocking {
        val server = MockWebServer()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val current = active.incrementAndGet(); peak.updateAndGet { maxOf(it, current) }
                try { Thread.sleep(60) } finally { active.decrementAndGet() }
                return MockResponse().setHeader("Content-Type", "image/png").setBody("cover")
            }
        }
        server.start()
        try {
            val nav = api(server)
            val other = Credentials(nav.credentials.server, "other", "test-only")
            val cache = CoverStore(temporary.newFolder(), { if (it == other.accountId) other else nav.credentials }, { NavApi(it, allowLocalHttpForTests = true) })
            val results = coroutineScope { (0..7).map { i -> async { cache.file(if (i == 7) other.accountId else nav.credentials.accountId, "cover-${i % 7}", 192) } }.awaitAll() }
            assertTrue(peak.get() in 1..2)
            assertNotEquals(results[0].path, results[7].path)
            assertEquals(8, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun rejectedCoverResponseIsNotCachedOrRetriedInAStorm() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        server.start()
        try {
            val nav = api(server)
            val cache = CoverStore(temporary.newFolder(), { nav.credentials }, { api(server) })
            repeat(3) {
                try { cache.file(nav.credentials.accountId, "cover", 192); fail("Expected rate limiting") } catch (_: IOException) { }
            }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun scrollingAwayDuringACoverBodyReadCancelsTheSocketAndReleasesItsSlot() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("x".repeat(10000)).throttleBody(1, 1, TimeUnit.SECONDS))
        server.start()
        try {
            val nav = api(server)
            val directory = temporary.newFolder()
            val cache = CoverStore(directory, { nav.credentials }, { api(server) })
            val job = launch { cache.file(nav.credentials.accountId, "slow", 192) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            delay(150)
            withTimeout(1500) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { server.shutdown() }
    }

    @Test fun multiGenreTagsSurviveCacheAndDoNotDisappearWhenLegacyGenreIsAbsent() {
        val song = Song.parse(JSONObject("""{"id":"s","title":"Track","genres":[{"name":"Rock"},{"name":"Electronic"}]}"""))
        assertEquals(listOf("Rock", "Electronic"), song.genres)
        assertEquals("Rock", song.genre)
        assertEquals(song, Song.parse(song.json()))
    }
}
