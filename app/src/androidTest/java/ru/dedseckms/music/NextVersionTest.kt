package ru.dedseckms.music

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.dedseckms.music.playback.sessionSkip

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NextVersionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun switchingTabsPreservesAnOpenedArtistAndLibraryFilter() {
        compose.onNodeWithText("Посмотреть интерфейс без входа").performScrollTo().performClick()
        compose.onNodeWithText("Поиск").performClick()
        compose.onNodeWithText("Исполнители").performClick()
        compose.onNodeWithText("Тихий сигнал").performClick()
        compose.onNodeWithText("Музыка").performClick()
        compose.onNodeWithText("Плейлисты").performClick()
        compose.onNodeWithText("Поиск").performClick()
        compose.onNodeWithText("Слушать исполнителя").assertIsDisplayed()
        compose.onNodeWithText("Музыка").performClick()
        compose.onNodeWithText("Плейлисты").assertIsSelected()
    }
    @Test fun theFirstSkipUsesTheOwningPlayersShuffledOrder() {
        compose.runOnUiThread {
            val player = ExoPlayer.Builder(compose.activity).build()
            try {
                player.setMediaItems((0..3).map { MediaItem.Builder().setMediaId("$it").setUri("file:///fixture-$it.mp3").build() })
                player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(intArrayOf(0, 3, 1, 2), 42L))
                player.shuffleModeEnabled = true
                sessionSkip(player, true)
                assertEquals(3, player.currentMediaItemIndex)
                sessionSkip(player, false)
                assertEquals(0, player.currentMediaItemIndex)
            } finally { player.release() }
        }
    }
    @Test fun theGlassShaderCompilesOnSupportedAndroidVersions() {
        if (Build.VERSION.SDK_INT >= 33) android.graphics.RuntimeShader(ru.dedseckms.music.ui.GLASS_SHADER)
    }
    @Test fun accountQueuesResolveOnThePlayersThreadForMusicAndWave() {
        val app = compose.activity.application as MusicApp
        val previous = app.credentials.active()
        val credentials = ru.dedseckms.music.data.Credentials("https://queue-test.invalid", "queue-test", "fixture-password")
        app.credentials.save(credentials)
        lateinit var connection: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>
        compose.runOnUiThread {
            val token = androidx.media3.session.SessionToken(compose.activity,
                android.content.ComponentName(compose.activity, ru.dedseckms.music.playback.MusicService::class.java))
            connection = androidx.media3.session.MediaController.Builder(compose.activity, token).buildAsync()
        }
        val controller = connection.get(10, java.util.concurrent.TimeUnit.SECONDS)
        try {
            val songs = (0..39).map { ru.dedseckms.music.data.Song("fixture-$it", "Track $it", "Artist") }
            for (wave in listOf(false, true, false)) {
                val request = app.playRequests.offer(credentials.accountId, songs, 0, wave, ru.dedseckms.music.data.MixMode.BALANCED)
                compose.runOnUiThread { controller.setMediaItem(MediaItem.Builder().setMediaId("queue:$request").build()) }
                compose.waitUntil(10_000) {
                    var resolved = false
                    compose.runOnUiThread { resolved = controller.mediaItemCount == 40 && controller.currentMediaItem?.mediaId == "fixture-0" }
                    resolved
                }
            }
        } finally {
            compose.runOnUiThread { controller.stop(); controller.clearMediaItems(); controller.release() }
            app.credentials.logout()
            previous?.let(app.credentials::save)
        }
    }
}
