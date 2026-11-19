package ru.dedseckms.music.desktop
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.dedseckms.music.data.*
import java.io.File

@Composable private fun LyricsContent(song: Song, position: Long, api: ProfileApi?, cache: File?, seek: (Long) -> Unit, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session = remember(api, song.id) { LyricsSession(scope, api, song, cache) }
    DisposableEffect(session) { onDispose { session.close() } }
    val state by session.state.collectAsState()
    val lyrics = state.lyrics
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var follow by remember(song.id) { mutableStateOf(true) }
    var offset by remember(song.id) { mutableLongStateOf(0L) }
    val active = lyrics?.activeLine(position + offset) ?: -1
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(active, follow) { if (follow && active >= 0) list.animateScrollToItem((active - 1).coerceAtLeast(0)) }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(song.title, style = MaterialTheme.typography.titleLarge, maxLines = 2); Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(session::refresh) { Icon(Icons.Rounded.Refresh, "Обновить текст") }
                IconButton(dismiss) { Icon(Icons.Rounded.Close, "Закрыть текст") }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            if (lyrics?.synced == true) Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ offset = (offset - 250).coerceAtLeast(-10000) }) { Text("−0,25 с") }
                Text("${offset} мс", style = MaterialTheme.typography.labelSmall)
                TextButton({ offset = (offset + 250).coerceAtMost(10000) }) { Text("+0,25 с") }
                Spacer(Modifier.weight(1f))
                if (!follow) TextButton({ follow = true }) { Text("К строке") }
            }
            if (lyrics != null && lyrics.lines.isNotEmpty()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    itemsIndexed(lyrics.lines) { index, line ->
                        Text(line.text.ifBlank { "♪" }, style = MaterialTheme.typography.headlineSmall,
                            fontWeight = if (active == index) FontWeight.Bold else FontWeight.Medium,
                            color = if (!lyrics.synced || index == active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f),
                            modifier = Modifier.fillMaxWidth().clickable(enabled = line.startMs != null) { line.startMs?.let { seek((it - offset).coerceAtLeast(0)); follow = true } })
                    }
                }
            } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(when (lyrics?.status) { "instrumental" -> "Инструментальная композиция"; "not_found" -> "Текст пока не найден"; "pending" -> "Ищем текст в интернете…"; else -> if (state.loading) "Загружаем текст…" else "Текст недоступен" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            lyrics?.source?.takeIf { it.isNotBlank() }?.let { Text("Источник: $it${if (!lyrics.synced) " · без синхронизации" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
@Composable internal fun LyricsDialog(song: Song, position: Long, api: ProfileApi?, cache: File?, seek: (Long) -> Unit, dismiss: () -> Unit) {
    androidx.compose.ui.window.DialogWindow(onCloseRequest = dismiss, title = "Текст песни", state = androidx.compose.ui.window.rememberDialogState(width = 640.dp, height = 720.dp)) {
        LyricsContent(song, position, api, cache, seek, dismiss)
    }
}