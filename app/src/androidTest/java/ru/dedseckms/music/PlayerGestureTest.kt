package ru.dedseckms.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.dedseckms.music.ui.SwipePlayerSurface
import ru.dedseckms.music.ui.SwipeCover

class PlayerGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun artworkSwipesChangeExactlyOneTrackInTheExpectedDirectionAndDownStillCollapses() {
        var forward = 0
        var back = 0
        var collapsed = false
        compose.setContent {
            MaterialTheme {
                val scroll = rememberScrollState()
                SwipePlayerSurface(scroll, { collapsed = true }) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        var current by remember { mutableIntStateOf(1) }
                        val keys = listOf(current - 1, current, current + 1).map(Int::toString)
                        SwipeCover(1, keys, { index ->
                            val target = keys[index].toInt()
                            if (target > current) forward++ else if (target < current) back++
                            current = target
                        }, Modifier.size(250.dp)) { Text("Artwork ${keys[it]}") }
                        Spacer(Modifier.height(1000.dp))
                    }
                }
            }
        }
        compose.onNodeWithTag("player-cover").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, forward); assertEquals(0, back); assertFalse(collapsed) }
        compose.onNodeWithTag("player-cover").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(1, forward); assertEquals(1, back); assertFalse(collapsed) }
        compose.onNodeWithTag("player-cover").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, forward); assertEquals(1, back); assertTrue(collapsed) }
    }

    @Test fun buttonDrivenPageChangesAndQueueWindowUpdatesDoNotSeekAgain() {
        var selected = 0
        val current = mutableIntStateOf(1)
        val keys = mutableStateOf(listOf("previous", "current", "next"))
        compose.setContent {
            MaterialTheme { SwipeCover(current.intValue, keys.value, { selected++ }, Modifier.size(250.dp)) { Text(keys.value[it]) } }
        }
        compose.runOnIdle { current.intValue = 2 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, selected); keys.value = listOf("current", "next", "new-next"); current.intValue = 1 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, selected) }
    }

    @Test fun downSwipeCollapsesWhileHorizontalSwipeDoesNot() {
        var collapsed = false
        compose.setContent {
            MaterialTheme {
                val scroll = rememberScrollState()
                SwipePlayerSurface(scroll, { collapsed = true }) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        Text("Player")
                        Spacer(Modifier.height(1800.dp))
                    }
                }
            }
        }
        compose.onNodeWithTag("full-player").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertFalse(collapsed) }
        compose.onNodeWithTag("full-player").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(collapsed) }
    }
}
