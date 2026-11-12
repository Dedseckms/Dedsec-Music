package ru.dedseckms.music.data

import java.io.IOException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdminProtocolTest {
    private fun api(server: MockWebServer) = ProfileApi(Credentials(server.url("/").toString(), "owner", "login-password"), allowLocalHttpForTests = true)
    @Test fun credentialsOnlyTravelInAuthenticatedCreateBodyAndUploadsArePaged() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"admin-test"}"""))
            server.enqueue(MockResponse().setBody("""{"user":{"id":"new-user"},"uploadReady":true}"""))
            server.enqueue(MockResponse().setBody("""{"items":[{"name":"Track.mp3","source":"upload","size":1024}],"total":51}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            val client = api(server)
            assertTrue(client.adminCreate("friend", "a-long-new-password"))
            val uploads = client.adminUploads("new-user", 50)
            client.adminDelete("new-user")
            server.takeRequest()
            val create = server.takeRequest()
            assertEquals("/dedsec/profile/v1/admin/users", create.path)
            assertEquals("Bearer admin-test", create.getHeader("Authorization"))
            assertEquals("a-long-new-password", JSONObject(create.body.readUtf8()).getString("password"))
            val page = server.takeRequest()
            assertEquals("/dedsec/profile/v1/admin/users/new-user/uploads?offset=50&limit=50", page.path)
            assertEquals(51, uploads.total); assertEquals("Track.mp3", uploads.items.single().name)
            assertEquals("DELETE", server.takeRequest().method)
            assertThrows(IllegalArgumentException::class.java) { client.adminUploads("../other") }
            assertEquals(4, server.requestCount)
        }
    }
    @Test fun serverDenialNeverLeaksPrivateResponseBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"admin-test"}"""))
            server.enqueue(MockResponse().setResponseCode(403).setBody("private-owner-record"))
            val error = assertThrows(IOException::class.java) { api(server).adminUsers() }
            assertTrue(error.message!!.contains("администратору"))
            assertFalse(error.message!!.contains("private-owner-record"))
        }
    }
}
