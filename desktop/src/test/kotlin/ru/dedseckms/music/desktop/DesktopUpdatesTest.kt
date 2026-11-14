package ru.dedseckms.music.desktop

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files
import java.io.IOException
import kotlin.test.*

class DesktopUpdatesTest {
    @Test fun cancellingBeforeRegistrationOrDuringRedirectPreventsNextNetworkCall() {
        for (duringRedirect in listOf(false, true)) {
            val folder = Files.createTempDirectory("dedsec-cancel-test-")
            val requests = AtomicInteger()
            lateinit var updater: UpdateClient
            val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
                requests.incrementAndGet()
                updater.cancelDownload()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                    .header("Location", "https://release-assets.githubusercontent.com/cancelled.zip").body("".toResponseBody()).build()
            }.build()
            updater = UpdateClient(client = client, downloadRoot = folder)
            if (!duringRedirect) updater.cancelDownload()
            val offer = UpdateOffer("9.0.0", "https://github.com/Dedseckms/Dedsec-Music/releases/download/test/cancel.zip", "a".repeat(64), 100)
            assertFailsWith<IOException> { updater.download(offer) }
            assertEquals(if (duringRedirect) 1 else 0, requests.get())
            Files.list(folder.resolve(offer.version)).use { assertEquals(0L, it.count()) }
            Files.delete(folder.resolve(offer.version)); Files.delete(folder)
        }
    }
    @Test fun appUpdateOfferIsRetainedAndStartupChecksAreCoalesced() = runBlocking {
        val checks = AtomicInteger()
        val offer = UpdateOffer("9.0.0", "https://github.com/Dedseckms/Dedsec-Music/releases/download/test/package.zip", "a".repeat(64), 100)
        val updater = DesktopUpdates(this, { null }) { checks.incrementAndGet(); offer }
        updater.check()
        withTimeout(3000) { while (updater.state.value.checking) delay(10) }
        repeat(3) { updater.check() }
        assertEquals(1, checks.get()); assertEquals(offer, updater.state.value.offer)
        updater.check(force = true)
        withTimeout(3000) { while (updater.state.value.checking) delay(10) }
        assertEquals(2, checks.get())
    }
    @Test fun startupNetworkFailureIsVisibleAndManualRetryCanSucceed() = runBlocking {
        var fail = true
        val updater = DesktopUpdates(this, { null }) { if (fail) error("private server detail"); null }
        updater.check()
        withTimeout(3000) { while (updater.state.value.checking) delay(10) }
        assertTrue(updater.state.value.message.contains("Не удалось"))
        assertFalse(updater.state.value.message.contains("private"))
        fail = false; updater.check(force = true)
        withTimeout(3000) { while (updater.state.value.checking) delay(10) }
        assertTrue(updater.state.value.message.contains("Обновлений нет"))
    }
    @Test fun healthySlowArchiveIsNotAbortedByTheMetadataWallBudget() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("payload".repeat(100)).throttleBody(100, 80, TimeUnit.MILLISECONDS))
            val metadata = OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).followRedirects(false).build()
            UpdateClient.archiveClient(metadata).newCall(Request.Builder().url(server.url("/archive")).build()).execute().use {
                assertEquals("payload".repeat(100), it.body!!.string())
            }
        }
    }
}
