package ru.dedseckms.music

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.dedseckms.music.data.MusicPlaylist
import ru.dedseckms.music.data.Song
import ru.dedseckms.music.ui.AddToPlaylistSheet

class PlaylistPickerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun thePickerScrollsToAllListsAndShowsLockedListsExplicitly() {
        var selected: String? = null
        val lists = (0..29).map { MusicPlaylist("$it", "List $it", "anna", 5, "", false, readOnly = it == 20) }
        compose.setContent {
            MaterialTheme { AddToPlaylistSheet(Song("s", "Song", "Artist"), lists, false, null, false, {}, { selected = it.id }, {}, {}) }
        }
        compose.onNodeWithTag("playlist-picker").performScrollToNode(hasText("List 29"))
        compose.onNodeWithText("List 29").performClick()
        compose.runOnIdle { assertEquals("29", selected) }
        compose.onNodeWithTag("playlist-picker").performScrollToNode(hasText("List 20"))
        compose.onNodeWithText("Только просмотр").assertIsDisplayed()
    }
}
