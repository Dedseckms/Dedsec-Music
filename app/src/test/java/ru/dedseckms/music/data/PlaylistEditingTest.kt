package ru.dedseckms.music.data

import java.io.IOException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PlaylistEditingTest {
    private fun detail(ids: List<String>, owner: String = "anna", readOnly: Boolean = false) = MockResponse().setBody(
        """{"subsonic-response":{"status":"ok","playlist":{"id":"p1","name":"Mine","owner":"$owner","public":false,"readonly":$readOnly,"entry":[${ids.joinToString { """{"id":"$it","title":"$it","artist":"Artist"}""" }}]}}}"""
    )
    private fun api(server: MockWebServer) = NavApi(Credentials(server.url("/").toString(), "anna", "secret"), allowLocalHttpForTests = true)

    @Test fun removesOnlyTheSelectedDuplicateAndPreservesTheRemainingOrder() {
        MockWebServer().use { server ->
            server.enqueue(detail(listOf("a", "b", "a", "c")))
            server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok"}}"""))
            val result = api(server).removeFromPlaylist("p1", 2, listOf("a", "b", "a", "c"))
            assertEquals(listOf("a", "b", "c"), result.songs.map(Song::id))
            assertEquals(3, result.playlist.songCount)
            assertFalse(result.playlist.public)
            assertEquals("/rest/getPlaylist.view", server.takeRequest().requestUrl!!.encodedPath)
            val update = server.takeRequest().requestUrl!!
            assertEquals("/rest/updatePlaylist.view", update.encodedPath)
            assertEquals("2", update.queryParameter("songIndexToRemove"))
            assertNull(update.queryParameter("songIdToAdd"))
            assertNull(update.queryParameter("public"))
            assertNull(update.queryParameter("name"))
        }
    }
    @Test fun aChangedOrderRefreshesTheSnapshotWithoutSendingARemoval() {
        MockWebServer().use { server ->
            server.enqueue(detail(listOf("b", "a", "a")))
            val error = assertThrows(PlaylistChangedException::class.java) { api(server).removeFromPlaylist("p1", 1, listOf("a", "b", "a")) }
            assertEquals(listOf("b", "a", "a"), error.latest.songs.map(Song::id))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun foreignAndReadOnlyListsCannotBeRemovedFrom() {
        for ((owner, readOnly) in listOf("boris" to false, "anna" to true)) MockWebServer().use { server ->
            server.enqueue(detail(listOf("a"), owner, readOnly))
            assertThrows(IOException::class.java) { api(server).removeFromPlaylist("p1", 0, listOf("a")) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun invalidPositionsAreRejectedBeforeAnyRequest() {
        MockWebServer().use { server ->
            val api = api(server)
            for (index in listOf(-1, 1)) assertThrows(IOException::class.java) { api.removeFromPlaylist("p1", index, listOf("a")) }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun allOwnListsAreRetainedAndFreshReadonlyFlagsReplaceOldOnes() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","playlists":{"playlist":[{"id":"1","name":"One","owner":"anna","readonly":false},{"id":"2","name":"Two","owner":"Anna","readonly":false},{"id":"3","name":"Locked","owner":"anna","readonly":true},{"id":"4","name":"Foreign","owner":"boris","readonly":false}]}}}"""))
            val lists = api(server).playlists()
            assertEquals(listOf("1", "2", "3"), lists.map(MusicPlaylist::id))
            assertEquals(listOf(false, false, true), lists.map(MusicPlaylist::readOnly))
        }
    }
    @Test fun aServerRejectionDoesNotReturnAFakeSuccessfulRemoval() {
        MockWebServer().use { server ->
            server.enqueue(detail(listOf("a")))
            server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"failed","error":{"code":50}}}"""))
            assertThrows(MusicApiException::class.java) { api(server).removeFromPlaylist("p1", 0, listOf("a")) }
            assertEquals(2, server.requestCount)
        }
    }
}
