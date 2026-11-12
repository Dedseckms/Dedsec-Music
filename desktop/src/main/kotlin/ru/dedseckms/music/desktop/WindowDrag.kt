package ru.dedseckms.music.desktop

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import kotlin.math.abs
import kotlin.math.roundToInt

/** Keep the same horizontal grab point when pulling a maximized window back into a window. */
internal fun restoreDragBounds(restored: Rectangle, maximized: Rectangle, press: Point, cursor: Point, titleHeight: Int): Rectangle {
    val fraction = ((press.x - maximized.x).toDouble() / maximized.width.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    val top = (press.y - maximized.y).coerceIn(0, titleHeight.coerceAtLeast(1))
    return Rectangle(cursor.x - (restored.width * fraction).roundToInt(), cursor.y - top, restored.width, restored.height)
}

/** One pointer recognizer handles click, double click and drag; child click handlers must not consume its press. */
@Composable internal fun WindowScope.WindowDragArea(state: WindowState, maximize: () -> Unit, content: @Composable () -> Unit) {
    val onMaximize by rememberUpdatedState(maximize)
    val restored = remember(window) { arrayOf(Rectangle(window.bounds)) }
    DisposableEffect(window, state) {
        val listener = object : ComponentAdapter() {
            private fun capture() {
                if (state.placement == WindowPlacement.Floating && ((window as? Frame)?.extendedState ?: 0) and Frame.MAXIMIZED_BOTH == 0 &&
                    window.width >= window.minimumSize.width && window.height >= window.minimumSize.height) restored[0] = Rectangle(window.bounds)
            }
            override fun componentMoved(event: ComponentEvent) = capture()
            override fun componentResized(event: ComponentEvent) = capture()
        }
        window.addComponentListener(listener)
        onDispose { window.removeComponentListener(listener) }
    }
    Box(Modifier.pointerInput(window) {
        var lastClick = 0L; var lastClickAt = Point()
        val interval = (Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int ?: 500).toLong()
        awaitEachGesture {
            val press = awaitFirstDown(requireUnconsumed = true)
            if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            val pointer = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
            val initial = Rectangle(window.bounds)
            val wasMaximized = state.placement != WindowPlacement.Floating
            val floating = Rectangle(restored[0]).also {
                it.width = it.width.coerceAtLeast(window.minimumSize.width)
                it.height = it.height.coerceAtLeast(window.minimumSize.height)
            }
            var dragged = false; var anchor = Rectangle(initial); var origin = Point(pointer)
            do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == press.id } ?: break
                if (!change.pressed) break
                val cursor = MouseInfo.getPointerInfo()?.location ?: continue
                if (!dragged && abs(cursor.x - pointer.x) + abs(cursor.y - pointer.y) >= 5) {
                    dragged = true; lastClick = 0
                    if (wasMaximized) {
                        anchor = restoreDragBounds(floating, initial, pointer, cursor, 38)
                        origin = Point(cursor)
                        state.placement = WindowPlacement.Floating
                        state.size = DpSize(anchor.width.dp, anchor.height.dp)
                    }
                }
                if (dragged) {
                    change.consume()
                    state.position = WindowPosition.Absolute((anchor.x + cursor.x - origin.x).dp, (anchor.y + cursor.y - origin.y).dp)
                }
            } while (true)
            if (!dragged) {
                val now = System.currentTimeMillis()
                if (now - lastClick in 1..interval && abs(pointer.x - lastClickAt.x) + abs(pointer.y - lastClickAt.y) < 8) {
                    lastClick = 0; onMaximize()
                } else { lastClick = now; lastClickAt = pointer }
            }
        }
    }) { content() }
}
