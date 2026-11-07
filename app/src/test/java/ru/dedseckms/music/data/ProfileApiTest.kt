package ru.dedseckms.music.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class ProfileApiTest {
    @Test fun cancellingASlowProfileBodyReleasesTheNextOperation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"test"}"""))
            server.enqueue(MockResponse().setBody(" ".repeat(5000) + me).throttleBody(5, 1, TimeUnit.SECONDS))
            server.enqueue(MockResponse().setBody(me))
            val client = api(server)
            val slow = launch { client.await { me() } }
            withContext(Dispatchers.IO) { repeat(2) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) } }
            withTimeout(2000) { slow.cancelAndJoin() }
            assertEquals("anna", withTimeout(3000) { client.await { me() } }.userId)
        }
    }
    private val me = """{"userId":"anna","avatarVersion":"v1","avatarUrl":"https://untrusted.invalid/secret","librarySongCount":10124}"""
    private fun api(server: MockWebServer) = ProfileApi(Credentials(server.url("/").toString(), "anna", "test-password"), allowLocalHttpForTests = true)
    @Test fun nativeTokenIsReusedAndAvatarAlwaysUsesTheConfiguredOrigin() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"native-test-token"}"""))
            server.enqueue(MockResponse().setBody(me))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody("image"))
            val client = api(server)
            assertEquals(10124, client.me().librarySongCount)
            assertArrayEquals("image".toByteArray(), client.avatar())
            val login = server.takeRequest(); val profile = server.takeRequest(); val image = server.takeRequest()
            assertEquals("/auth/login", login.path)
            assertNull(login.getHeader("Authorization"))
            assertEquals("Bearer native-test-token", profile.getHeader("Authorization"))
            assertEquals("/dedsec/profile/v1/avatar", image.path)
            assertFalse(image.path!!.contains("password"))
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun expiredTokenRetriesAuthenticationOnlyOnce() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"old"}"""))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"token":"new"}"""))
            server.enqueue(MockResponse().setBody(me))
            assertEquals("anna", api(server).me().userId)
            repeat(3) { server.takeRequest() }
            assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun uploadsUseRawJpegAndRedirectsAreNeverFollowedWithCredentials() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"test"}"""))
            server.enqueue(MockResponse().setBody(me))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://untrusted.invalid/secret"))
            val client = api(server)
            client.upload(byteArrayOf(1, 2, 3))
            server.takeRequest()
            val uploaded = server.takeRequest()
            assertEquals("PUT", uploaded.method)
            assertEquals("image/jpeg", uploaded.getHeader("Content-Type"))
            assertArrayEquals(byteArrayOf(1, 2, 3), uploaded.body.readByteArray())
            assertThrows(java.io.IOException::class.java) { client.avatar() }
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun oversizedResponsesAreRejectedBeforeAllocationCanGrowWithoutBound() {
        assertThrows(java.io.IOException::class.java) { readBounded(ByteArray(65537).inputStream(), 65536) }
        assertEquals(65536, readBounded(ByteArray(65536).inputStream(), 65536).size)
    }
}
