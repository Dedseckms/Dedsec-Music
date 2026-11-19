package ru.dedseckms.music.desktop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.dedseckms.music.data.*
import java.nio.file.Files

class OfflineStoreTest {
    @Test fun retryingPersistenceAfterPartialDiskFailureRetainsTheWholePendingBatch() {
        val root = Files.createTempDirectory("dedsec-queue-retry-")
        val credentials = Credentials("https://example.org", "fixture", "not-saved")
        val store = DesktopStore(credentials, root)
        val songs = listOf(Song("a", "A", "Artist"), Song("b", "B", "Artist"))
        val obstacle = root.resolve("download-queue/${sha256("b")}.json")
        Files.createDirectories(obstacle); Files.writeString(obstacle.resolve("retain"), "fixture")
        assertThrows(java.io.IOException::class.java) { store.queueDownloads(songs) }
        assertEquals(listOf("a"), store.pendingDownloads().map(Song::id))
        Files.delete(obstacle.resolve("retain")); Files.delete(obstacle)
        store.queueDownloads(songs)
        assertEquals(setOf("a", "b"), DesktopStore(credentials, root).pendingDownloads().map(Song::id).toSet())
    }
    @Test fun newDownloadsDoNotRewriteTheLegacyLibraryAndOldAudioRemainsAvailable() {
        val root = Files.createTempDirectory("dedsec-offline-test-")
        val store = DesktopStore(Credentials("https://example.org", "fixture", "not-saved"), root)
        val old = Song("old", "Legacy", "Artist")
        Files.createDirectories(store.audio(old).parent); Files.write(store.audio(old), byteArrayOf(1, 2))
        val legacy = JSONObject().put("items", JSONArray().put(JSONObject().put("song", old.json()).put("bytes", 2))).toString()
        Files.writeString(root.resolve("offline.json"), legacy)
        for (i in 0 until 120) { val song = Song("new-$i", "Track $i", "Artist"); Files.write(store.audio(song), byteArrayOf(1)); store.downloaded(song, 1) }
        assertEquals(legacy, Files.readString(root.resolve("offline.json")))
        assertEquals(121, store.offline().size)
        store.removeOffline(old)
        assertEquals(120, store.offline().size)
        assertEquals(legacy, Files.readString(root.resolve("offline.json")))
    }
    @Test fun interruptedQueueRestoresOnlyUnfinishedMetadataAndPreferencesStayInAccountDirectory() {
        val root = Files.createTempDirectory("dedsec-queue-test-")
        val credentials = Credentials("https://example.org", "fixture", "not-saved")
        val store = DesktopStore(credentials, root)
        val songs = (0..2).map { Song("$it", "Track $it", "Artist") }
        store.queueDownloads(songs + songs)
        store.offlinePreferences(OfflinePreferences(true, false))
        store.finishDownload(songs[0].id)
        val restored = DesktopStore(credentials, root)
        assertEquals(setOf("1", "2"), restored.pendingDownloads().map(Song::id).toSet())
        assertEquals(OfflinePreferences(true, false), restored.offlinePreferences())
        val other = DesktopStore(Credentials(credentials.server, "other", "not-saved"), Files.createTempDirectory("dedsec-other-test-"))
        assertTrue(other.pendingDownloads().isEmpty()); assertEquals(OfflinePreferences(), other.offlinePreferences())
    }
}
