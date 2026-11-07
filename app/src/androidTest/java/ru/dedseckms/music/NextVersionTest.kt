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
}
