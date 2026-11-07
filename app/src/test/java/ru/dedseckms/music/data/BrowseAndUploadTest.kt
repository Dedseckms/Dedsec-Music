package ru.dedseckms.music.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BrowseAndUploadTest {
    @Test fun searchReturnsAnArtistAndTheirTracksAsSeparateResults() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","searchResult3":{"artist":[{"id":"artist-7","name":"Stint","albumCount":2}],"song":[{"id":"track-9","title":"Example","artist":"Stint","artistId":"artist-7"}]}}}"""))
        server.start()
        try {
            val result = NavApi(Credentials(server.url("/").toString(), "user", "test-only"), allowLocalHttpForTests = true).search("Stint")
            assertEquals("artist-7", result.artists.single().id)
            assertEquals("artist-7", result.songs.single().artistId)
            val request = server.takeRequest().requestUrl!!
            assertEquals("Stint", request.queryParameter("query"))
            assertEquals("50", request.queryParameter("artistCount"))
        } finally { server.shutdown() }
    }

    @Test fun artistIndexIncludesAllGroupsWithoutDuplicateIds() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","artists":{"index":[{"name":"A","artist":[{"id":"a","name":"Artist A"}]},{"name":"B","artist":[{"id":"b","name":"Artist B"},{"id":"a","name":"Artist A"}]}]}}}"""))
        server.start()
        try {
            val artists = NavApi(Credentials(server.url("/").toString(), "user", "test-only"), allowLocalHttpForTests = true).artists()
            assertEquals(listOf("a", "b"), artists.map { it.id })
        } finally { server.shutdown() }
    }

    @Test fun artistAlbumsAndGenrePagesUseTheirOwnServerIds() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","artist":{"id":"artist-7","name":"Stint","album":[{"id":"album-3","name":"A","songCount":5}]}}}"""))
        server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","songsByGenre":{"song":[{"id":"track-9","title":"Example","artist":"Stint","path":"uploads/Example.mp3"}]}}}"""))
        server.start()
        try {
            val api = NavApi(Credentials(server.url("/").toString(), "user", "test-only"), allowLocalHttpForTests = true)
            assertEquals("album-3", api.artist("artist-7").albums.single().id)
            assertEquals("artist-7", server.takeRequest().requestUrl!!.queryParameter("id"))
            assertEquals("Example.mp3", api.genreSongs("Hip-Hop / Rap", 100).single().sourceFilename)
            val request = server.takeRequest().requestUrl!!
            assertEquals("Hip-Hop / Rap", request.queryParameter("genre"))
            assertEquals("100", request.queryParameter("offset"))
        } finally { server.shutdown() }
    }

    @Test fun uploadedFileMustActuallyMatchTheCatalogEntry() {
        val unrelated = Song("one", "My song", "Artist", duration = 180, sourceFilename = "other.mp3")
        assertNull(UploadMatch.find("upload.mp3", "My song", "Artist", 180_000, listOf(unrelated)))
        val uploaded = unrelated.copy(id = "two", sourceFilename = "upload.mp3")
        assertEquals("two", UploadMatch.find("UPLOAD.mp3", "", "", 0, listOf(unrelated, uploaded))!!.id)
    }

    @Test fun metadataFallbackRequiresArtistTitleAndDurationTogether() {
        val song = Song("one", "My song", "Artist", duration = 180)
        assertNull(UploadMatch.find("x.mp3", "My song", "", 180_000, listOf(song)))
        assertNull(UploadMatch.find("x.mp3", "My song", "Artist", 200_000, listOf(song)))
        assertEquals(song, UploadMatch.find("x.mp3", "My song", "Artist", 180_200, listOf(song)))
    }

    @Test fun separateApiObjectsReuseTheHttpsConnectionPool() {
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok"}}""")) }
        server.start()
        try {
            val credentials = Credentials(server.url("/").toString(), "user", "test-only")
            repeat(2) { NavApi(credentials, allowLocalHttpForTests = true).ping() }
            assertEquals(0, server.takeRequest().sequenceNumber)
            assertEquals(1, server.takeRequest().sequenceNumber)
        } finally { server.shutdown() }
    }
}
