package ru.dedseckms.music

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import ru.dedseckms.music.data.Song
import ru.dedseckms.music.ui.FullPlayer

class DownloadStateTest {
    @get:Rule val compose = createComposeRule()
    @Test fun verifiedOfflineSongShowsDownloadedAndCannotBeDownloadedAgain() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApp
        val vm = MusicViewModel(app)
        val song = Song("offline-test", "Saved song", "Artist")
        compose.setContent { MaterialTheme { FullPlayer(AppState(now = song, offline = listOf(song)), vm) { } } }
        compose.onNodeWithTag("download-from-player").assertIsNotEnabled()
        compose.onNodeWithText("Скачано").assertIsDisplayed()
        compose.onNodeWithText("Скачать").assertDoesNotExist()
    }
}
