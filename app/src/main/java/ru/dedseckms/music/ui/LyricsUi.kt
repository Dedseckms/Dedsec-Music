package ru.dedseckms.music.ui
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import ru.dedseckms.music.data.*
import java.io.File

@Composable internal fun LyricsOverlay(song: Song, position: Long, api: ProfileApi?, cache: File?, seek: (Long) -> Unit, scrolled: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val session = remember(api, song.id) { LyricsSession(scope, api, song, cache) }
    DisposableEffect(session) { onDispose { session.close() } }
    val state by session.state.collectAsState()
    val lyrics = state.lyrics
    val list = rememberLazyListState()
    val onScrolled by rememberUpdatedState(scrolled)
    LaunchedEffect(list) { snapshotFlow { list.canScrollBackward }.collect { onScrolled(it) } }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var follow by remember(song.id) { mutableStateOf(true) }
    var timingDialog by remember(song.id) { mutableStateOf(false) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var offset by remember(song.id, lyrics?.revision, lyrics?.timingOffsetMs) { mutableLongStateOf(lyrics?.timingOffsetMs ?: 0L) }
    val active = lyrics?.activeLine(position + offset) ?: -1
    LaunchedEffect(dragged) {
        if (dragged) follow = false
        else { delay(3500); follow = true }
    }
    LaunchedEffect(active, follow, viewportHeight) {
        if (follow && active >= 0 && viewportHeight > 0) {
            val height = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }?.size ?: viewportHeight
            list.animateScrollToItem(active, -((viewportHeight - height).coerceAtLeast(0) / 2))
        }
    }
    if (timingDialog && lyrics?.canEditTiming == true) AlertDialog(
        onDismissRequest = { if (!state.savingTiming) timingDialog = false },
        title = { Text("Синхронизация текста") },
        text = { Column {
            Text("Поправка применяется всем слушателям. Положительный сдвиг показывает строку раньше.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ offset = (offset - 250).coerceAtLeast(-10000) }, enabled = !state.savingTiming) { Text("−0,25 с") }
                Text("${offset} мс", style = MaterialTheme.typography.labelMedium)
                TextButton({ offset = (offset + 250).coerceAtMost(10000) }, enabled = !state.savingTiming) { Text("+0,25 с") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton({ session.saveTiming(offset) }, enabled = !state.loading && !state.savingTiming && offset != lyrics.timingOffsetMs) {
            Text(if (state.savingTiming) "Сохраняем…" else if (offset == lyrics.timingOffsetMs) "Сохранено" else "Сохранить для всех")
        } },
        dismissButton = { TextButton({ timingDialog = false }, enabled = !state.savingTiming) { Text("Готово") } }
    )
    Surface(shape = MaterialTheme.shapes.extraLarge, color = androidx.compose.ui.graphics.Color.Transparent) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (lyrics?.synced == true) "В такт музыке" else "Текст песни", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!follow) IconButton({ follow = true }) { Icon(Icons.Rounded.MyLocation, "К текущей строке") }
                if (lyrics?.canEditTiming == true) IconButton({ timingDialog = true }) { Icon(Icons.Rounded.Tune, "Синхронизация текста") }
                IconButton(session::refresh, enabled = !state.savingTiming) { Icon(Icons.Rounded.Refresh, "Обновить текст") }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            if (lyrics != null && lyrics.lines.isNotEmpty()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewportHeight = it.height }, state = list,
                    contentPadding = PaddingValues(top = 16.dp, bottom = with(androidx.compose.ui.platform.LocalDensity.current) { (viewportHeight / 2).toDp() }.coerceAtLeast(24.dp)), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(lyrics.lines) { index, line ->
                        val highlighted = !lyrics.synced || index == active
                        val color by animateColorAsState(if (highlighted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f), tween(350), label = "lyric-color")
                        val scale by animateFloatAsState(if (highlighted) 1f else .95f, tween(350), label = "lyric-emphasis")
                        Text(line.text.ifBlank { "♪" }, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold, color = color,
                            modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, .5f) }.clickable(enabled = line.startMs != null) { line.startMs?.let { seek((it - offset).coerceAtLeast(0)); follow = true } })
                    }
                }
            } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(when (lyrics?.status) { "instrumental" -> "Инструментальная композиция"; "not_found" -> "Текст пока не найден"; "pending" -> "Ищем текст в интернете…"; else -> if (state.loading) "Загружаем текст…" else "Текст недоступен" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            lyrics?.source?.takeIf { it.isNotBlank() }?.let { Text("Источник: $it${if (!lyrics.synced) " · без синхронизации" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
