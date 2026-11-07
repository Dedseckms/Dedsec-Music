package ru.dedseckms.music.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/** Downward pulls navigate back only at the top; list scrolling and horizontal albums stay native. */
@Composable
internal fun SwipeBackSurface(atTop: () -> Boolean, back: () -> Unit, content: @Composable () -> Unit) {
    val canGoBack by rememberUpdatedState(atTop)
    val onBack by rememberUpdatedState(back)
    var offset by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(2000f) }
    var leaving by remember { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val fast = with(LocalDensity.current) { 850.dp.toPx() }
    val connection = remember(threshold, fast) { object : NestedScrollConnection {
        private fun pull(delta: Float): Offset {
            val before = offset
            offset = (offset + delta).coerceIn(0f, height)
            return Offset(0f, offset - before)
        }
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (leaving || source != NestedScrollSource.UserInput) return Offset.Zero
            return if (offset > 0 || available.y > 0 && canGoBack()) pull(available.y) else Offset.Zero
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            return if (!leaving && source == NestedScrollSource.UserInput && available.y > 0 && canGoBack()) pull(available.y) else Offset.Zero
        }
        override suspend fun onPreFling(available: Velocity): Velocity {
            if (leaving || offset <= 0) return Velocity.Zero
            val dismiss = offset >= threshold || offset > threshold / 4 && available.y > fast
            leaving = dismiss
            animate(offset, if (dismiss) height else 0f, animationSpec = if (dismiss) tween(180) else spring()) { value, _ -> offset = value }
            if (dismiss) onBack()
            return Velocity(0f, available.y)
        }
    } }
    Box(Modifier.fillMaxSize().testTag("swipe-back-page").graphicsLayer {
        translationY = offset
        height = size.height
    }.nestedScroll(connection).scrollable(rememberScrollableState { 0f }, Orientation.Vertical)) { content() }
}
