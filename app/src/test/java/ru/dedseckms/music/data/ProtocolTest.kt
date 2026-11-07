package ru.dedseckms.music.data

import java.security.MessageDigest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun readOnlyPlaylistsRemainVisibleButCannotBeSubmittedForEditing() {
        val playlist = MusicPlaylist.parse(org.json.JSONObject("""{"id":"synced","name":"Imported","owner":"anna","readonly":true}"""))
        assertTrue(playlist.readOnly)
        assertEquals(listOf(playlist), ownPlaylists(listOf(playlist), "anna"))
        MockWebServer().use { server ->
            val api = NavApi(Credentials(server.url("/").toString(), "anna", "test"), allowLocalHttpForTests = true)
            org.junit.Assert.assertThrows(java.io.IOException::class.java) { api.appendToPlaylist(playlist, Song("id", "Song", "Artist")) }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun authenticationUsesSaltedTokenAndNeverSendsPassword() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""))
        server.start()
        try {
            val credentials = Credentials(server.url("/music/").toString().trimEnd('/'), "anna", "private-password")
            NavApi(credentials, allowLocalHttpForTests = true).ping()
            val request = server.takeRequest()
            assertEquals("/music/rest/ping.view", request.requestUrl!!.encodedPath)
            assertNull(request.requestUrl!!.queryParameter("p"))
            assertFalse(request.path!!.contains("private-password"))
            val salt = request.requestUrl!!.queryParameter("s")!!
            val expected = MessageDigest.getInstance("MD5").digest(("private-password" + salt).toByteArray()).joinToString("") { "%02x".format(it) }
            assertEquals(expected, request.requestUrl!!.queryParameter("t"))
            assertFalse(credentials.toString().contains("private-password"))
        } finally { server.shutdown() }
    }
    @Test fun httpAndCredentialsInServerAddressAreRejected() {
        for (value in listOf("http://example.org", "https://user:secret@example.org", "https://example.org?token=abc", "https://example.org/#secret")) {
            try { ServerAddress.normalize(value); fail("Accepted unsafe address") } catch (_: java.io.IOException) { }
        }
        assertEquals("https://music.example.org/base", ServerAddress.normalize("music.example.org/base/"))
    }
    @Test fun privatePlaylistsAreIsolatedByOwner() {
        val values = listOf(MusicPlaylist("1", "Mine", "anna", 2, "", false), MusicPlaylist("2", "Shared", "boris", 3, "", true))
        assertEquals(listOf("1"), ownPlaylists(values, "Anna").map { it.id })
        assertEquals(listOf("2"), ownPlaylists(values, "boris").map { it.id })
        assertNotEquals(Credentials("https://host", "anna", "a").accountId, Credentials("https://host", "boris", "b").accountId)
        assertNotEquals(Credentials("https://host", "anna", "a").accountId, Credentials("https://other", "anna", "a").accountId)
    }
    @Test fun newlyCreatedPlaylistIsMadePrivateBeforeSongsAreAdded() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","playlist":{"id":"p1","name":"Personal","owner":"anna","public":true}}}"""))
        repeat(2) { server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok"}}""")) }
        server.start()
        try {
            val api = NavApi(Credentials(server.url("/").toString(), "anna", "password"), allowLocalHttpForTests = true)
            val playlist = api.createPlaylist("Personal", listOf("s1", "s2"))
            assertFalse(playlist.public)
            assertNull(server.takeRequest().requestUrl!!.queryParameter("songId"))
            val privacy = server.takeRequest().requestUrl!!
            assertEquals("false", privacy.queryParameter("public"))
            assertNull(privacy.queryParameter("songIdToAdd"))
            assertEquals(listOf("s1", "s2"), server.takeRequest().requestUrl!!.queryParameterValues("songIdToAdd"))
        } finally { server.shutdown() }
    }
    @Test fun otherAccountsPlaylistsCannotBeReadOrModified() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","playlist":{"id":"foreign","owner":"boris"}}}"""))
        server.start()
        try {
            val api = NavApi(Credentials(server.url("/").toString(), "anna", "password"), allowLocalHttpForTests = true)
            try { api.playlistSongs("foreign"); fail() } catch (_: java.io.IOException) { }
            try { api.appendToPlaylist(MusicPlaylist("foreign", "Other", "boris", 0, "", true), Song("s1", "Song", "Artist")); fail() } catch (_: java.io.IOException) { }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun serverErrorMessageCannotEchoCredentialsIntoTheUi() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"https://secret.example/?p=private"}}}"""))
        server.start()
        try {
            try { NavApi(Credentials(server.url("/").toString(), "anna", "private"), allowLocalHttpForTests = true).ping(); fail() }
            catch (error: MusicApiException) { assertEquals("Проверь логин и пароль", error.message); assertFalse(error.message!!.contains("private")) }
        } finally { server.shutdown() }
    }
    @Test fun uploadNamesStayInsideTheSourceAndAreAudioOnly() {
        assertEquals("song.flac", UploadApi.safeFilename("../../song.flac"))
        assertEquals("song.mp3", UploadApi.safeFilename("C:\\music\\song.mp3"))
        for (value in listOf(".token", "settings.json", "song.exe", "..", "song\n.mp3")) {
            try { UploadApi.safeFilename(value); fail("Accepted unsafe file") } catch (_: java.io.IOException) { }
        }
    }
    @Test fun songMetadataRoundTripsForOfflineStorage() {
        val song = Song("id-1", "Тёплый свет", "Artist", duration = 123, size = 4500, starred = true, rating = 4)
        assertEquals(song, Song.parse(song.json()))
    }
}
