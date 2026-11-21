package ru.dedseckms.music.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.dedseckms.music.data.*
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopContentTest {
    @get:Rule val temp = TemporaryFolder()
    private val clean = Song("clean", "Clean", "Artist", contentRating = ContentRating.CLEAN)
    private val explicit = Song("explicit", "Explicit", "Artist", contentRating = ContentRating.EXPLICIT)
    private val unknown = Song("unknown", "Legacy cache", "Artist")

    // Delayed playback jobs let the policy change race with an unresolved stream without native VLC.
    private class PendingDispatcher : CoroutineDispatcher() {
        val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
    }
    private fun field(controller: DesktopController, name: String) =
        DesktopController::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun state(controller: DesktopController): MutableStateFlow<DesktopState> =
        field(controller, "mutable").get(controller) as MutableStateFlow<DesktopState>
    private fun controller(): DesktopController {
        val scope = CoroutineScope(SupervisorJob() + PendingDispatcher())
        return DesktopController(scope, DesktopPreferences(temp.newFolder().toPath())).also {
            val credentials = Credentials("https://music.example", "test", "test")
            field(it, "api").set(it, NavApi(credentials))
            field(it, "store").set(it, DesktopStore(credentials, temp.newFolder().toPath()))
            state(it).value = DesktopState(loggedIn = true)
        }
    }
    @Test fun preferenceIsPerAccountDefaultsOffAndSurvivesReopening() {
        val a = temp.newFolder().toPath(); val b = temp.newFolder().toPath()
        val credentials = Credentials("https://music.example", "a", "test")
        assertFalse(DesktopStore(credentials, a).childMode())
        DesktopStore(credentials, a).childMode(true)
        assertTrue(DesktopStore(credentials, a).childMode())
        assertFalse(DesktopStore(Credentials(credentials.server, "b", "test"), b).childMode())
    }
    @Test fun enablingStopsPendingPlaybackDropsQueueAndRejectsStaleCrossfade() {
        val c = controller()
        try {
            c.play(listOf(explicit, clean))
            val pendingPlay = field(c, "playJob").get(c) as Job
            val oldEpoch = field(c, "queueEpoch").getLong(c)
            val pendingPrepare = c.scope.launch { delay(Long.MAX_VALUE) }
            val pendingMix = c.scope.launch { delay(Long.MAX_VALUE) }
            field(c, "prepareJob").set(c, pendingPrepare)
            field(c, "mixJob").set(c, pendingMix)
            assertEquals(explicit, c.state.value.now)
            c.childMode(true)
            assertTrue(pendingPlay.isCancelled); assertTrue(pendingPrepare.isCancelled); assertTrue(pendingMix.isCancelled)
            assertNull(c.state.value.now); assertTrue(c.queue.songs.isEmpty())
            assertTrue(c.state.value.queue.isEmpty()); assertFalse(c.audio.state.value.playing)
            DesktopController::class.java.getDeclaredMethod("onCrossfaded", String::class.java).apply { isAccessible = true }.invoke(c, "$oldEpoch:1")
            DesktopController::class.java.getDeclaredMethod("onEnded").apply { isAccessible = true }.invoke(c)
            c.toggle(); c.skip(true); c.select(0)
            assertNull(c.state.value.now)
            assertTrue(c.store!!.childMode())
            c.play(listOf(clean, clean.copy(id = "second")))
            DesktopController::class.java.getDeclaredMethod("onCrossfaded", String::class.java).apply { isAccessible = true }.invoke(c, "$oldEpoch:1")
            assertEquals(0, c.state.value.cursor); assertEquals(clean, c.state.value.now)
            c.childMode(false)
            assertNull(c.state.value.now); assertTrue(c.state.value.queue.isEmpty())
        } finally { c.close() }
    }
    @Test fun manualSelectionKeepsOccurrenceFiltersQueueAndBlockedTapDoesNotSwitchSong() {
        val c = controller()
        try {
            c.childMode(true)
            c.play(listOf(explicit, clean, unknown, clean), 3)
            assertEquals(listOf(clean, unknown, clean), c.state.value.queue)
            assertEquals(2, c.state.value.cursor)
            c.play(listOf(explicit, clean), 0)
            assertEquals(ContentPolicy.BLOCKED_MESSAGE, c.state.value.message)
            assertEquals(clean, c.state.value.now); assertEquals(2, c.state.value.cursor)
            c.playCollection(listOf(explicit, unknown, clean), "Collection")
            assertEquals(listOf(unknown, clean), c.state.value.queue)
            c.shuffle(); c.repeat(); c.skip(true)
            assertTrue(c.state.value.queue.none { it.contentRating == ContentRating.EXPLICIT })
            c.downloadAll(listOf(explicit, unknown))
            assertEquals(setOf(unknown.id), c.state.value.downloadIds)
            assertEquals(ContentPolicy.BLOCKED_MESSAGE, c.state.value.message)
            c.logout()
            assertFalse(c.state.value.childMode); assertNull(c.state.value.now)
        } finally { c.close() }
    }
    @Test fun legacyOfflineMetadataStaysUnknownAndRemainsPlayableWithoutDeletingFiles() {
        val c = controller()
        try {
            val local = c.store!!; Files.createDirectories(local.audio(unknown).parent)
            Files.write(local.audio(unknown), byteArrayOf(1, 2, 3)); local.downloaded(unknown, 3)
            val cached = local.offline().single()
            assertEquals(ContentRating.UNKNOWN, cached.contentRating)
            c.childMode(true); c.play(listOf(cached))
            assertEquals(cached, c.state.value.now); assertTrue(Files.exists(local.audio(cached)))
        } finally { c.close() }
    }
    @Test fun manualRatingChangesKeepAllowedPlaybackAndExplicitResultInvalidatesChildQueue() {
        val c = controller()
        try {
            state(c).value = DesktopState(loggedIn = true, isAdmin = true, songs = listOf(unknown), favorites = listOf(unknown),
                offline = listOf(unknown), page = DesktopPage("album", "Album", listOf(unknown)),
                search = SearchResults(listOf(unknown), emptyList()))
            c.childMode(true)
            c.applyContentRating(unknown.id, ContentRating.CLEAN, true)
            assertTrue(c.state.value.songs.single().contentRatingOverride)
            assertEquals(ContentRating.CLEAN, c.state.value.favorites.single().contentRating)
            assertEquals(ContentRating.CLEAN, c.state.value.offline.single().contentRating)
            assertEquals(ContentRating.CLEAN, c.state.value.page!!.songs.single().contentRating)
            assertEquals(ContentRating.CLEAN, c.state.value.search.songs.single().contentRating)
            c.play(c.state.value.songs)
            assertEquals(unknown.id, c.state.value.now?.id)
            val oldPlay = field(c, "playJob").get(c) as Job
            c.applyContentRating(unknown.id, ContentRating.UNKNOWN, false)
            assertFalse(oldPlay.isCancelled); assertEquals(unknown.id, c.state.value.now?.id)
            c.applyContentRating(unknown.id, ContentRating.EXPLICIT, true)
            assertTrue(oldPlay.isCancelled); assertNull(c.state.value.now); assertTrue(c.state.value.queue.isEmpty())
            assertEquals(ContentRating.EXPLICIT, c.state.value.songs.single().contentRating)
            c.applyContentRating(unknown.id, ContentRating.UNKNOWN, false)
            assertFalse(c.state.value.songs.single().contentRatingOverride)
            c.play(c.state.value.songs)
            assertEquals(ContentRating.UNKNOWN, c.state.value.now?.contentRating)
        } finally { c.close() }
    }
    @Test fun manualRatingUpdatesCachedMetadataWithoutDeletingAudioOrChangingAnotherAccount() {
        val credentials = Credentials("https://music.example", "test", "test")
        val local = DesktopStore(credentials, temp.newFolder().toPath())
        val other = DesktopStore(Credentials(credentials.server, "other", "test"), temp.newFolder().toPath())
        for (store in listOf(local, other)) {
            Files.createDirectories(store.audio(unknown).parent); Files.write(store.audio(unknown), byteArrayOf(1, 2, 3))
            store.downloaded(unknown, 3); store.record(unknown); store.queueDownloads(listOf(unknown))
        }
        local.updateContentRating(unknown.id, ContentRating.CLEAN, true)
        for (song in listOf(local.offline().single(), local.history().single().song, local.pendingDownloads().single())) {
            assertEquals(ContentRating.CLEAN, song.contentRating); assertTrue(song.contentRatingOverride)
        }
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(local.audio(unknown)))
        assertEquals(ContentRating.UNKNOWN, other.offline().single().contentRating)
        val reopened = DesktopStore(credentials, local.directory)
        assertEquals(ContentRating.CLEAN, reopened.offline().single().contentRating)
        reopened.updateContentRating(unknown.id, ContentRating.UNKNOWN, false)
        assertFalse(reopened.offline().single().contentRatingOverride)
        assertTrue(Files.exists(local.audio(unknown)))
    }

    @Test fun delayedRatingResponseCannotOvertakeTheNextServerWriteOrCachedResult() = runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val firstReceived = java.util.concurrent.CountDownLatch(1)
            val releaseFirst = java.util.concurrent.CountDownLatch(1)
            val secondReceived = java.util.concurrent.CountDownLatch(1)
            val writes = java.util.concurrent.atomic.AtomicInteger()
            val serverRating = java.util.concurrent.atomic.AtomicReference("")
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    if (request.path == "/auth/login") return okhttp3.mockwebserver.MockResponse().setBody("""{"token":"fixture"}""")
                    if (request.path != "/dedsec/profile/v1/content-ratings/override") return okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                    val status = org.json.JSONObject(request.body.readUtf8()).getString("status")
                    serverRating.set(status)
                    if (writes.incrementAndGet() == 1) {
                        firstReceived.countDown()
                        releaseFirst.await(3, java.util.concurrent.TimeUnit.SECONDS)
                    } else secondReceived.countDown()
                    return okhttp3.mockwebserver.MockResponse().setBody("""{"ratings":{"unknown":"$status"},"overrides":{"unknown":"$status"}}""")
                }
            }
            val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val c = DesktopController(CoroutineScope(SupervisorJob() + dispatcher), DesktopPreferences(temp.newFolder().toPath()))
            try {
                val credentials = Credentials(server.url("/").toString(), "admin", "fixture")
                val local = DesktopStore(credentials, temp.newFolder().toPath())
                Files.createDirectories(local.audio(unknown).parent); Files.write(local.audio(unknown), byteArrayOf(1, 2, 3))
                local.downloaded(unknown, 3)
                field(c, "api").set(c, NavApi(credentials, allowLocalHttpForTests = true))
                field(c, "profile").set(c, ProfileApi(credentials, allowLocalHttpForTests = true))
                field(c, "store").set(c, local)
                state(c).value = DesktopState(loggedIn = true, isAdmin = true, songs = listOf(unknown), offline = listOf(unknown))
                c.setContentRating(unknown, ContentRating.CLEAN)
                assertTrue(firstReceived.await(3, java.util.concurrent.TimeUnit.SECONDS))
                c.setContentRating(unknown, ContentRating.EXPLICIT)
                assertFalse(secondReceived.await(200, java.util.concurrent.TimeUnit.MILLISECONDS), "The second mutation must wait for the first response and cache write")
                releaseFirst.countDown()
                @Suppress("UNCHECKED_CAST")
                val tasks = (field(c, "accountTasks").get(c) as Set<Job>).toList()
                withTimeout(5000) { tasks.joinAll() }
                assertEquals(2, writes.get()); assertEquals("explicit", serverRating.get())
                assertEquals(ContentRating.EXPLICIT, c.state.value.songs.single().contentRating)
                assertEquals(ContentRating.EXPLICIT, local.offline().single().contentRating)
                assertTrue(local.offline().single().contentRatingOverride)
                assertTrue(Files.exists(local.audio(unknown)))
            } finally { releaseFirst.countDown(); c.close(); dispatcher.close() }
        }
    }

    @Test fun durableRatingGuardRejectsStaleCallbacksAndDoesNotCarryCleanToChangedAudio() {
        val credentials = Credentials("https://music.example", "test", "fixture")
        val local = DesktopStore(credentials, temp.newFolder().toPath())
        val staleClean = unknown.copy(contentRating = ContentRating.CLEAN, contentRatingOverride = true)
        Files.createDirectories(local.audio(unknown).parent); Files.write(local.audio(unknown), byteArrayOf(1, 2, 3))
        local.rememberContentRating(unknown, ContentRating.EXPLICIT, true)
        local.downloaded(staleClean, 3); local.record(staleClean); local.queueDownloads(listOf(staleClean))
        val reopened = DesktopStore(credentials, local.directory)
        for (song in listOf(reopened.offline().single(), reopened.history().single().song, reopened.pendingDownloads().single())) {
            assertEquals(ContentRating.EXPLICIT, song.contentRating)
        }
        local.rememberContentRating(unknown, ContentRating.CLEAN, true)
        assertEquals(ContentRating.UNKNOWN, reopened.canonicalRating(unknown.copy(size = 999)).contentRating)
        local.rememberContentRating(unknown, ContentRating.UNKNOWN, false)
        assertEquals(ContentRating.UNKNOWN, reopened.canonicalRating(staleClean).contentRating)
        assertTrue(Files.exists(local.audio(unknown)))
        val c = controller()
        try {
            state(c).value = DesktopState(loggedIn = true, songs = listOf(staleClean))
            c.childMode(true); c.applyContentRating(unknown.id, ContentRating.EXPLICIT, true)
            c.play(listOf(staleClean)); c.download(staleClean)
            assertNull(c.state.value.now); assertTrue(c.state.value.downloadIds.isEmpty())
        } finally { c.close() }
    }
    @Test fun laterServerRatingReplacesManualSnapshotAndStillProtectsAgainstStaleWriters() {
        val local = DesktopStore(Credentials("https://music.example", "test", "fixture"), temp.newFolder().toPath())
        val old = unknown.copy(contentRating = ContentRating.CLEAN, contentRatingOverride = true)
        local.rememberContentRating(old, ContentRating.CLEAN, true)
        val fresh = unknown.copy(contentRating = ContentRating.EXPLICIT, contentRatingCheckedAt = System.currentTimeMillis() + 1000)
        assertEquals(ContentRating.EXPLICIT, local.canonicalRating(fresh).contentRating)
        assertFalse(ContentPolicy.allowed(local.canonicalRating(fresh), true))
        Files.createDirectories(local.audio(unknown).parent); Files.write(local.audio(unknown), byteArrayOf(1, 2, 3))
        local.downloaded(old, 3); local.record(old); local.queueDownloads(listOf(old))
        val reopened = DesktopStore(local.credentials, local.directory)
        assertEquals(ContentRating.EXPLICIT, reopened.offline().single().contentRating)
        assertEquals(ContentRating.EXPLICIT, reopened.history().single().song.contentRating)
        assertEquals(ContentRating.EXPLICIT, reopened.pendingDownloads().single().contentRating)
        assertEquals(ContentRating.EXPLICIT, reopened.canonicalRating(old.copy(contentRatingCheckedAt = 1)).contentRating)
    }

    @Test fun ratingAFutureTrackExplicitCancelsPendingPlaybackAndKeepsUnrelatedDownloadRunning() {
        val c = controller()
        try {
            c.childMode(true); c.play(listOf(clean, unknown))
            val playback = field(c, "playJob").get(c) as Job
            val unrelatedDownload = c.scope.launch { delay(Long.MAX_VALUE) }
            field(c, "downloadJob").set(c, unrelatedDownload)
            field(c, "activeDownloadId").set(c, clean.id)
            c.applyContentRating(unknown.id, ContentRating.EXPLICIT, true)
            assertNull(c.state.value.now); assertTrue(c.state.value.queue.isEmpty())
            assertTrue(playback.isCancelled); assertFalse(unrelatedDownload.isCancelled)
            c.play(listOf(clean))
            val freshPlayback = field(c, "playJob").get(c) as Job
            c.applyContentRating(unknown.id, ContentRating.UNKNOWN, false)
            assertEquals(clean, c.state.value.now); assertFalse(freshPlayback.isCancelled)
        } finally { c.close() }
    }

}
