@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package ru.dedseckms.music

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.dedseckms.music.data.*
import ru.dedseckms.music.playback.MusicService

@RunWith(AndroidJUnit4::class)
class DeviceIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as MusicApp

    @Test fun credentialsAreEncryptedAndLogoutRemovesBothLogins() {
        val credentials = Credentials("https://offline.example.test", "test-${UUID.randomUUID()}", "device-secret-${UUID.randomUUID()}")
        val store = CredentialStore(app)
        try {
            store.save(credentials)
            store.saveUpload(credentials.accountId, credentials)
            assertEquals(credentials.password, store.active()!!.password)
            val raw = app.getSharedPreferences("secure_sessions", Context.MODE_PRIVATE).all.toString()
            assertFalse(raw.contains(credentials.password))
            assertFalse(raw.contains(credentials.username))
        } finally { store.logout() }
        assertNull(store.active())
        assertNull(store.load(credentials.accountId))
        assertNull(store.upload(credentials.accountId))
    }

    @Test fun simultaneousDownloadsAndHistoryUpdatesDoNotLoseEntries() {
        val account = sha256(UUID.randomUUID().toString()).take(32)
        val other = LocalStore(app, sha256(UUID.randomUUID().toString()).take(32))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 16).map { index -> pool.submit {
                val store = LocalStore(app, account)
                val song = Song("track-$index", "Track $index", "Test")
                store.audioFile(song.id).writeBytes(byteArrayOf(1, 2, 3))
                store.downloaded(song, 3)
                store.record(song)
            } }
            jobs.forEach { it.get(20, TimeUnit.SECONDS) }
            val store = LocalStore(app, account)
            assertEquals(16, store.offline().size)
            assertEquals(16, store.history().size)
            assertTrue(other.offline().isEmpty())
            assertTrue(other.history().isEmpty())
            store.audioFile("track-0").appendBytes(byteArrayOf(4))
            assertEquals(15, store.offline().size)
        } finally {
            pool.shutdownNow()
            LocalStore(app, account).offline().forEach { LocalStore(app, account).removeDownload(it.id) }
        }
    }

    @Test fun mediaLibraryExposesCarSectionsAndAccountScopedOfflineTracks() {
        val credentials = Credentials("https://offline.example.test", "car-test-${UUID.randomUUID()}", "test-only-password")
        val store = LocalStore(app, credentials.accountId)
        val song = Song("car-track", "Offline car track", "Test Artist")
        store.audioFile(song.id).writeBytes(byteArrayOf(1, 2, 3))
        store.downloaded(song, 3)
        app.credentials.save(credentials)
        var browser: MediaBrowser? = null
        try {
            lateinit var pending: com.google.common.util.concurrent.ListenableFuture<MediaBrowser>
            instrumentation.runOnMainSync {
                pending = MediaBrowser.Builder(app, SessionToken(app, ComponentName(app, MusicService::class.java))).buildAsync()
            }
            browser = pending.get(20, TimeUnit.SECONDS)
            lateinit var sections: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<androidx.media3.common.MediaItem>>>
            instrumentation.runOnMainSync { sections = browser!!.getChildren("root", 0, 20, null) }
            assertEquals(listOf("wave", "favorites", "playlists", "offline"), sections.get(20, TimeUnit.SECONDS).value!!.map { it.mediaId })
            instrumentation.runOnMainSync { sections = browser!!.getChildren("offline", 0, 20, null) }
            val offline = sections.get(20, TimeUnit.SECONDS).value!!
            assertEquals(listOf(song.id), offline.map { it.mediaId })
            assertEquals(song.title, offline.single().mediaMetadata.title.toString())
            assertNull(offline.single().localConfiguration)
            app.credentials.logout()
            app.accountChanges.tryEmit(Unit)
            instrumentation.runOnMainSync { sections = browser!!.getChildren("root", 0, 20, null) }
            assertEquals("login", sections.get(20, TimeUnit.SECONDS).value!!.single().mediaId)
        } finally {
            instrumentation.runOnMainSync { browser?.release() }
            app.credentials.logout()
            store.removeDownload(song.id)
        }
    }
}
