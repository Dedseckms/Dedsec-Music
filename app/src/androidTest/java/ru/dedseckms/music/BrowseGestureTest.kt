package ru.dedseckms.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.dedseckms.music.ui.SwipeBackSurface

class BrowseGestureTest {
    @get:Rule val compose = createComposeRule()
    @Test fun downAtTopNavigatesBackOnceAndHorizontalDoesNot() {
        var back = 0
        compose.setContent {
            MaterialTheme {
                val list = rememberLazyListState()
                SwipeBackSurface({ !list.canScrollBackward }, { back++ }) {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) { items(100) { Text("Track $it", Modifier.height(60.dp)) } }
                }
            }
        }
        compose.onNodeWithTag("swipe-back-page").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(0, back) }
        compose.onNodeWithTag("swipe-back-page").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, back) }
    }
    @Test fun downWithinTheListScrollsWithoutNavigatingBack() {
        var back = 0
        compose.setContent {
            MaterialTheme {
                val list = rememberLazyListState()
                SwipeBackSurface({ !list.canScrollBackward }, { back++ }) {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("tracks")) { items(100) { Text("Track $it", Modifier.height(60.dp)) } }
                }
            }
        }
        compose.onNodeWithTag("tracks").performScrollToIndex(40)
        compose.onNodeWithTag("swipe-back-page").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, back) }
    }
}
