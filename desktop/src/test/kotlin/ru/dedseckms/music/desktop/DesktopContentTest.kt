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
            assertEquals(listOf(clean, clean), c.state.value.queue)
            assertEquals(1, c.state.value.cursor)
            c.play(listOf(explicit, clean), 0)
            assertEquals(ContentPolicy.BLOCKED_MESSAGE, c.state.value.message)
            assertEquals(clean, c.state.value.now); assertEquals(1, c.state.value.cursor)
            c.playCollection(listOf(explicit, unknown, clean), "Collection")
            assertEquals(listOf(clean), c.state.value.queue)
            c.shuffle(); c.repeat(); c.skip(true)
            assertTrue(c.state.value.queue.all { it.contentRating == ContentRating.CLEAN })
            c.downloadAll(listOf(explicit, unknown))
            assertTrue(c.state.value.downloadIds.isEmpty())
            assertEquals(ContentPolicy.BLOCKED_MESSAGE, c.state.value.message)
            c.logout()
            assertFalse(c.state.value.childMode); assertNull(c.state.value.now)
        } finally { c.close() }
    }
    @Test fun legacyOfflineMetadataStaysUnknownAndFilesSurviveModeChange() {
        val c = controller()
        try {
            val local = c.store!!; Files.createDirectories(local.audio(unknown).parent)
            Files.write(local.audio(unknown), byteArrayOf(1, 2, 3)); local.downloaded(unknown, 3)
            val cached = local.offline().single()
            assertEquals(ContentRating.UNKNOWN, cached.contentRating)
            c.childMode(true); c.play(listOf(cached))
            assertNull(c.state.value.now); assertTrue(Files.exists(local.audio(cached)))
            assertEquals(ContentPolicy.BLOCKED_MESSAGE, c.state.value.message)
        } finally { c.close() }
    }
    @Test fun manualCleanRatingAllowsUnknownAndExplicitResultInvalidatesChildPlayback() {
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
            c.applyContentRating(unknown.id, ContentRating.EXPLICIT, true)
            assertTrue(oldPlay.isCancelled); assertNull(c.state.value.now); assertTrue(c.state.value.queue.isEmpty())
            assertEquals(ContentRating.EXPLICIT, c.state.value.songs.single().contentRating)
            c.applyContentRating(unknown.id, ContentRating.UNKNOWN, false)
            assertFalse(c.state.value.songs.single().contentRatingOverride)
            c.play(c.state.value.songs)
            assertNull(c.state.value.now); assertEquals(ContentPolicy.BLOCKED_MESSAGE, c.state.value.message)
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

}
