package ru.dedseckms.music.desktop

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Rectangle

internal fun resizeBounds(initial: Rectangle, dx: Int, dy: Int, edge: Int, minWidth: Int, minHeight: Int): Rectangle {
    val west = edge and 1 != 0; val east = edge and 2 != 0; val north = edge and 4 != 0; val south = edge and 8 != 0
    val width = (initial.width + if (west) -dx else if (east) dx else 0).coerceAtLeast(minWidth)
    val height = (initial.height + if (north) -dy else if (south) dy else 0).coerceAtLeast(minHeight)
    return Rectangle(if (west) initial.x + initial.width - width else initial.x, if (north) initial.y + initial.height - height else initial.y, width, height)
}

/** Explicit edge handles keep an undecorated Compose window resizable. */
@Composable internal fun WindowScope.WindowResizeEdges(enabled: Boolean) {
    if (!enabled) return
    Box(Modifier.fillMaxSize()) {
        for (edge in listOf(1, 2, 4, 8, 5, 6, 9, 10)) {
            val corner = edge in listOf(5, 6, 9, 10)
            val alignment = when (edge) { 1 -> Alignment.CenterStart; 2 -> Alignment.CenterEnd; 4 -> Alignment.TopCenter; 8 -> Alignment.BottomCenter; 5 -> Alignment.TopStart; 6 -> Alignment.TopEnd; 9 -> Alignment.BottomStart; else -> Alignment.BottomEnd }
            val cursor = when (edge) { 1 -> Cursor.W_RESIZE_CURSOR; 2 -> Cursor.E_RESIZE_CURSOR; 4 -> Cursor.N_RESIZE_CURSOR; 8 -> Cursor.S_RESIZE_CURSOR; 5 -> Cursor.NW_RESIZE_CURSOR; 6 -> Cursor.NE_RESIZE_CURSOR; 9 -> Cursor.SW_RESIZE_CURSOR; else -> Cursor.SE_RESIZE_CURSOR }
            val size = if (corner) Modifier.size(10.dp) else if (edge in listOf(1, 2)) Modifier.width(5.dp).fillMaxHeight() else Modifier.height(5.dp).fillMaxWidth()
            Box(size.align(alignment).pointerHoverIcon(PointerIcon(Cursor(cursor))).pointerInput(edge) {
                var initial = Rectangle(); var start = java.awt.Point()
                detectDragGestures(onDragStart = { initial = window.bounds; start = MouseInfo.getPointerInfo().location }) { change, _ ->
                    change.consume()
                    val mouse = MouseInfo.getPointerInfo().location
                    window.bounds = resizeBounds(initial, mouse.x - start.x, mouse.y - start.y, edge, window.minimumSize.width, window.minimumSize.height)
                }
            })
        }
    }
}
