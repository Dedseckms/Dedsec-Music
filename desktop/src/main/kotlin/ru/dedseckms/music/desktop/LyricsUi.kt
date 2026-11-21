package ru.dedseckms.music.desktop
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    var offset by remember(song.id, lyrics?.revision, lyrics?.timingOffsetMs) { mutableLongStateOf(lyrics?.timingOffsetMs ?: 0L) }
    val active = lyrics?.activeLine(position + offset) ?: -1
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(active, follow) { if (follow && active >= 0) list.animateScrollToItem((active - 1).coerceAtLeast(0)) }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Текст песни", style = MaterialTheme.typography.titleLarge); Text(if (lyrics?.synced == true) "В такт музыке" else "Слова твоего трека", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(session::refresh, enabled = !state.savingTiming) { Icon(Icons.Rounded.Refresh, "Обновить текст") }
                IconButton(dismiss) { Icon(Icons.Rounded.Close, "Закрыть текст") }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            if (lyrics?.synced == true && lyrics.canEditTiming) Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ offset = (offset - 250).coerceAtLeast(-10000) }, enabled = !state.savingTiming) { Text("−0,25 с") }
                Text("${offset} мс", style = MaterialTheme.typography.labelSmall)
                TextButton({ offset = (offset + 250).coerceAtMost(10000) }, enabled = !state.savingTiming) { Text("+0,25 с") }
            }
            if (lyrics?.synced == true) Row(verticalAlignment = Alignment.CenterVertically) {
                if (lyrics.canEditTiming) TextButton({ session.saveTiming(offset) }, enabled = !state.loading && !state.savingTiming && offset != lyrics.timingOffsetMs) {
                    Text(if (state.savingTiming) "Сохраняем…" else if (offset == lyrics.timingOffsetMs) "Сохранено на сервере" else "Сохранить для всех")
                } else Text("Синхронизация владельца · ${offset} мс", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (!follow) TextButton({ follow = true }) { Text("К строке") }
            }
            if (lyrics != null && lyrics.lines.isNotEmpty()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    itemsIndexed(lyrics.lines) { index, line ->
                        val highlighted = !lyrics.synced || index == active
                        val color by animateColorAsState(if (highlighted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f), tween(350), label = "lyric-color")
                        val scale by animateFloatAsState(if (highlighted) 1f else .95f, tween(350), label = "lyric-emphasis")
                        Text(line.text.ifBlank { "♪" }, style = MaterialTheme.typography.headlineSmall,
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
@Composable internal fun LyricsPlayer(controller: DesktopController, song: Song, position: Long, dismiss: () -> Unit) {
    LyricsStage(song, position, controller.lyricsApi(), controller.lyricsCache(song), controller.covers, controller.audio::seek, dismiss)
}
@Composable internal fun LyricsStage(song: Song, position: Long, api: ProfileApi?, cache: File?, covers: DesktopCovers?, seek: (Long) -> Unit, dismiss: () -> Unit) {
    val artwork by produceState<ImageBitmap?>(null, song.coverArt, covers) {
        value = covers?.get(song.coverArt, 384)
    }
    val fallback = MaterialTheme.colorScheme.primary
    val accent = remember(artwork, fallback) {
        artwork?.let { image ->
            val pixels = image.toPixelMap()
            val colors = (1..5).flatMap { x -> (1..5).map { y -> pixels[image.width * x / 6, image.height * y / 6] } }
            Color(colors.map { it.red }.average().toFloat(), colors.map { it.green }.average().toFloat(), colors.map { it.blue }.average().toFloat())
        } ?: fallback
    }
    val surface = MaterialTheme.colorScheme.surface
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(lerp(surface, accent, .28f), surface, lerp(surface, accent, .12f))))) {
        val roomy = maxWidth > 620.dp
        val coverLimit = (maxHeight - 230.dp).coerceAtLeast(80.dp)
        val showAlbum = maxHeight > 450.dp
        Row(Modifier.fillMaxSize().padding(if (roomy) 28.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(if (roomy) 28.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(if (roomy) .42f else .36f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                val cover = Modifier.widthIn(max = coverLimit).fillMaxWidth().aspectRatio(1f).shadow(22.dp, MaterialTheme.shapes.extraLarge).clip(MaterialTheme.shapes.extraLarge)
                if (artwork != null) Image(artwork!!, song.album.ifBlank { song.title }, cover, contentScale = ContentScale.Crop)
                else Cover(song.coverArt, song.album.ifBlank { song.title }, cover, 28)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExplicitBadge(song)
                    Text(song.title, style = if (roomy) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    if (showAlbum) Text(song.album, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
            Box(Modifier.weight(if (roomy) .58f else .64f).fillMaxHeight()) {
                key(song.id) { LyricsContent(song, position, api, cache, seek, dismiss) }
            }
        }
    }
}
