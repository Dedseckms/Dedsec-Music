package ru.dedseckms.music.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.abs

/** Native paging keeps the adjacent cover under the finger and commits only a completed user swipe. */
@Composable
internal fun SwipeCover(currentIndex: Int, keys: List<String>, select: (Int) -> Unit,
                        modifier: Modifier = Modifier, content: @Composable (Int) -> Unit) {
    if (keys.isEmpty()) return
    val selected by rememberUpdatedState(select)
    val actualIndex by rememberUpdatedState(currentIndex)
    val latestKeys by rememberUpdatedState(keys)
    val currentKey = keys.getOrNull(currentIndex)
    var swipedKey by remember { mutableStateOf<String?>(null) }
    val pager = rememberPagerState(initialPage = currentIndex.coerceIn(keys.indices), pageCount = { keys.size })
    LaunchedEffect(pager) {
        var settle: Job? = null
        pager.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start, is DragInteraction.Cancel -> { settle?.cancel(); settle = null }
                is DragInteraction.Stop -> {
                    settle?.cancel()
                    settle = launch {
                        snapshotFlow { pager.isScrollInProgress }.first { !it }
                        val page = pager.settledPage
                        if (page in latestKeys.indices && page != actualIndex) { swipedKey = latestKeys[page]; selected(page) }
                    }
                }
            }
        }
    }
    // Buttons, automatic track changes and a new queue move the artwork without sending another seek.
    LaunchedEffect(currentKey, currentIndex, keys) {
        snapshotFlow { pager.isScrollInProgress }.first { !it }
        val target = latestKeys.indexOf(currentKey)
        if (target >= 0 && currentKey == swipedKey) {
            swipedKey = null
            pager.requestScrollToPage(target)
        } else if (target >= 0 && target != pager.currentPage) {
            if (abs(target - pager.currentPage) == 1) pager.animateScrollToPage(target, animationSpec = tween(240, easing = FastOutSlowInEasing))
            else pager.scrollToPage(target)
        }
    }
    HorizontalPager(state = pager, modifier = modifier.testTag("player-cover"), key = { keys[it] },
        beyondViewportPageCount = 1, pageSpacing = 12.dp) { index -> content(index) }
}

/** Downward scroll collapses only after the content has reached its top. Horizontal seeking stays native. */
@Composable
internal fun SwipePlayerSurface(scroll: ScrollState, dismiss: () -> Unit, content: @Composable () -> Unit) {
    var offset by remember { mutableFloatStateOf(0f) }
    val onDismiss by rememberUpdatedState(dismiss)
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }
    val velocityThreshold = with(density) { 850.dp.toPx() }
    var height by remember { mutableFloatStateOf(2000f) }
    val connection = remember(scroll, threshold, velocityThreshold) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            if ((available.y > 0 && scroll.value == 0) || offset > 0) {
                val previous = offset
                offset = (offset + available.y).coerceAtLeast(0f)
                return Offset(0f, offset - previous)
            }
            return Offset.Zero
        }
        override suspend fun onPreFling(available: Velocity): Velocity {
            if (offset <= 0) return Velocity.Zero
            val collapse = offset >= threshold || (offset > threshold / 4 && available.y > velocityThreshold)
            animate(offset, if (collapse) height else 0f, animationSpec = if (collapse) tween(180) else spring()) { value, _ -> offset = value }
            if (collapse) onDismiss()
            return available
        }
    } }
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize().testTag("full-player").graphicsLayer {
            translationY = offset
            height = size.height
        }.nestedScroll(connection).scrollable(rememberScrollableState { 0f }, Orientation.Vertical)) { ThemeBackdrop(LocalAmoled.current) { content() } }
    }
}

/** Material-style active wave. Pausing gently flattens it; the Slider retains its own accessibility and touch handling. */
@Composable
internal fun WavyTrack(progress: Float, playing: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "playback-wave")
    val phase = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(5600, easing = LinearEasing)), label = "wave-phase")
    val amplitude = animateFloatAsState(if (playing) 1f else 0f, tween(260), label = "wave-amplitude")
    Canvas(modifier) {
        val fraction = progress.coerceIn(0f, 1f)
        val end = size.width * fraction
        val middle = size.height / 2
        val gap = if (compact) 0f else 7.dp.toPx()
        val stroke = (if (compact) 2.dp else 3.dp).toPx()
        val waveHeight = (if (compact) 1.8.dp else 3.dp).toPx() * amplitude.value
        if (end + gap < size.width) drawLine(colors.onSurfaceVariant.copy(alpha = .22f), Offset(end + gap, middle), Offset(size.width, middle), stroke, StrokeCap.Round)
        val activeEnd = (end - gap).coerceAtLeast(0f)
        if (activeEnd > 0) {
            val path = Path()
            var x = 0f
            while (x < activeEnd) {
                val envelope = min(1f, (activeEnd - x) / 10.dp.toPx()).coerceAtLeast(0f)
                val y = middle + sin((x / 24.dp.toPx() - phase.value) * 2 * PI).toFloat() * waveHeight * envelope
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += 1.5.dp.toPx()
            }
            path.lineTo(activeEnd, middle)
            drawPath(path, colors.primary, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}
