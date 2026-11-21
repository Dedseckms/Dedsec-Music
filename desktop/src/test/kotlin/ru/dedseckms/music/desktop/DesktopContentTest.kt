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
            assertEquals(explicit, c.state.value.now)
            c.childMode(true)
            assertTrue(pendingPlay.isCancelled)
            assertNull(c.state.value.now); assertTrue(c.queue.songs.isEmpty())
            assertTrue(c.state.value.queue.isEmpty()); assertFalse(c.audio.state.value.playing)
            DesktopController::class.java.getDeclaredMethod("onCrossfaded", String::class.java).apply { isAccessible = true }.invoke(c, "$oldEpoch:1")
            DesktopController::class.java.getDeclaredMethod("onEnded").apply { isAccessible = true }.invoke(c)
            c.toggle(); c.skip(true); c.select(0)
            assertNull(c.state.value.now)
            assertTrue(c.store!!.childMode())
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
}
