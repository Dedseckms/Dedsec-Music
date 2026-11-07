package ru.dedseckms.music.desktop

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.*
import androidx.compose.ui.graphics.toPixelMap
import ru.dedseckms.music.data.Credentials
import ru.dedseckms.music.data.NavApi

class DesktopSafetyTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun downloadedCoversRemainReadableAndAreReusedWithoutNetwork() = runBlocking {
        MockWebServer().use { server ->
            val photo = java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB)
            photo.setRGB(0, 0, java.awt.Color.RED.rgb)
            val output = java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(photo, "png", output)
            server.enqueue(MockResponse().setBody(okio.Buffer().write(output.toByteArray())))
            val api = NavApi(Credentials(server.url("/").toString(), "fixture", "fixture"), allowLocalHttpForTests = true)
            val covers = DesktopCovers(api, temp.newFolder().toPath())
            val cover = covers.get("one", 384)
            assertNotNull(cover); assertEquals(4, cover.width)
            assertTrue(cover.toPixelMap()[0, 0].red > .9f)
            assertSame(cover, covers.get("one", 384)); assertEquals(1, server.requestCount)
        }
    }
    @Test fun cancelledTransfersCloseBlockingNetworkReads() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch { transfer(OkHttpClient()) { client -> client.newCall(Request.Builder().url(server.url("/audio")).build()).execute().use { it.body?.string() } } }
            val received = withContext(Dispatchers.IO) { server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS) }
            assertNotNull(received)
            job.cancel()
            withTimeout(2000) { job.join() }
            assertTrue(job.isCancelled)
        }
    }
    @Test fun coverDiskCacheEvictsOldestEntriesBeforeAcceptingNewData() {
        val dir = temp.newFolder().toPath()
        val oldest = dir.resolve("old.jpg"); Files.write(oldest, ByteArray(60)); Files.setLastModifiedTime(oldest, java.nio.file.attribute.FileTime.fromMillis(1))
        val newer = dir.resolve("new.jpg"); Files.write(newer, ByteArray(30)); Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(2))
        trimCoverCache(dir, incoming = 40, limit = 100)
        assertFalse(Files.exists(oldest)); assertTrue(Files.exists(newer))
    }
    @Test fun photoAndCoverHeadersAreBoundedBeforeDecode() {
        val photo = java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val output = java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(photo, "png", output)
        val good = output.toByteArray(); assertTrue(safeCoverGeometry(good)); assertEquals(4, decodePhoto(good).width)
        val huge = good.copyOf(); java.nio.ByteBuffer.wrap(huge).putInt(16, 20000).putInt(20, 20000)
        val crc = java.util.zip.CRC32(); crc.update(huge, 12, 17); java.nio.ByteBuffer.wrap(huge).putInt(29, crc.value.toInt())
        assertFalse(safeCoverGeometry(huge)); assertFails { decodePhoto(huge) }
        assertFalse(safeCoverGeometry(byteArrayOf(0, 1))); assertFails { decodePhoto(ByteArray(5_000_001)) }
    }
}
