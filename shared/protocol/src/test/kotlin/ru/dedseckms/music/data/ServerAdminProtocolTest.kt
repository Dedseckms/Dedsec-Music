package ru.dedseckms.music.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ServerAdminProtocolTest {
    @Test fun systemAndStorageUseNativeAdminOriginAndCopyHasExplicitPlanHash() {
        MockWebServer().use { server ->
            listOf("""{"token":"native-admin"}""", """{"sampledAt":1000,"scope":"host","cpuCount":2,"latest":{"cpuPercent":null},"history":[],"processes":[]}""",
                """{"volumes":[],"shards":[],"activeJob":null}""",
                """{"id":"plan1","planHash":"hash","files":3,"bytes":4096,"expiresAt":2000}""",
                """{"id":"move1","state":"queued","phase":"copying"}""").forEach { server.enqueue(MockResponse().setBody(it)) }
            val api = ProfileApi(Credentials(server.url("/").toString(), "owner", "private-password"), allowLocalHttpForTests = true)
            assertNull(api.adminSystem().latest.cpu)
            assertTrue(api.adminStorage().volumes.isEmpty())
            val plan = api.adminStoragePlan("tracks", "second")
            assertEquals("queued", api.adminStorageStart(plan.id, plan.hash).state)
            server.takeRequest()
            val stats = server.takeRequest()
            assertEquals("/dedsec/profile/v1/admin/system?windowSeconds=3600&points=240", stats.path)
            assertEquals("Bearer native-admin", stats.getHeader("Authorization"))
            server.takeRequest()
            val preview = server.takeRequest()
            assertEquals("POST", preview.method)
            assertEquals("tracks", org.json.JSONObject(preview.body.readUtf8()).getString("sourceShardId"))
            val move = server.takeRequest()
            assertEquals("hash", org.json.JSONObject(move.body.readUtf8()).getString("planHash"))
            assertFalse(move.path!!.contains("password"))
        }
    }
}
