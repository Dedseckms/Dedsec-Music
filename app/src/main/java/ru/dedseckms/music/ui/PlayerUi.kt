@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.dedseckms.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import ru.dedseckms.music.AppState
import ru.dedseckms.music.MusicViewModel
import ru.dedseckms.music.data.Song
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd

@Composable internal fun FullPlayer(state: AppState, vm: MusicViewModel, addToPlaylist: (Song) -> Unit = {}, dismiss: () -> Unit) {
    val song = state.now ?: return
    var queue by remember { mutableStateOf(false) }
    val queueMatches = state.coverQueue.any { it.queueIndex == state.queueIndex && it.song.id == song.id }
    val covers = if (queueMatches) state.coverQueue else listOf(ru.dedseckms.music.QueueCover(-1, song))
    val coverIndex = covers.indexOfFirst { it.queueIndex == state.queueIndex }.coerceAtLeast(0)
    val coverKeys = remember(covers) { covers.map { "${it.queueIndex}:${it.song.id}" } }
    val scroll = rememberScrollState()
    val fontScale = LocalDensity.current.fontScale
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        SwipePlayerSurface(scroll, dismiss) {
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 6.dp)) {
                val compact = maxHeight < 480.dp
                Column(Modifier.fillMaxWidth().height(minOf(maxHeight, maxWidth + (386 * fontScale).dp)).align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(dismiss) { Icon(Icons.Rounded.KeyboardArrowDown, "Свернуть плеер", Modifier.size(30.dp)) }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (!compact) Text("DEDSEC MUSIC", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 2.sp)
                            Text(state.playbackSource, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton({ queue = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Очередь воспроизведения") }
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val coverSide = minOf(maxWidth, (maxHeight - (112 * fontScale).dp).coerceAtLeast(0.dp))
                        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)) {
                            if (coverSide > 32.dp) SwipeCover(coverIndex, coverKeys, { if (queueMatches) vm.playQueueIndex(covers[it].queueIndex) }, Modifier.size(coverSide)) { index ->
                                val coverSong = covers[index].song
                                Artwork(coverSong.coverArt, coverSong.album.ifBlank { coverSong.title }, Modifier.fillMaxSize(), 30)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(song.artist, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp).clickable { vm.openArtist(song); dismiss() })
                                }
                                IconButton({ vm.favorite(song) }) { Icon(if (song.id in state.favoriteIds) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    "Нравится", Modifier.size(27.dp), tint = MaterialTheme.colorScheme.primary) }
                            }
                        }
                    }
                    // Transport and secondary actions are outside the scrolling artwork area.
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        var dragging by remember(song.id) { mutableStateOf<Float?>(null) }
                        val duration = state.duration.coerceAtLeast(1).toFloat()
                        val position = (dragging ?: state.position.toFloat()).coerceIn(0f, duration)
                        Slider(position, { dragging = it }, onValueChangeFinished = { dragging?.let { vm.seek(it.toLong()) }; dragging = null },
                            valueRange = 0f..duration, modifier = Modifier.fillMaxWidth().testTag("player-seek"),
                            track = { WavyTrack(position / duration, state.playing, Modifier.fillMaxWidth().height(28.dp)) })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(time(position.toLong()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(time(state.duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 2.dp else 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(vm::shuffle) { Icon(Icons.Rounded.Shuffle, "Перемешать", tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                            IconButton(vm::previous) { Icon(Icons.Rounded.SkipPrevious, "Предыдущий трек", Modifier.size(36.dp)) }
                            FilledIconButton(vm::togglePlay, shape = panelShape(24), modifier = Modifier.size(if (compact) 56.dp else 70.dp)) {
                                if (state.buffering) CircularProgressIndicator(Modifier.size(30.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
                                else Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Пауза" else "Продолжить", Modifier.size(42.dp))
                            }
                            IconButton(vm::next) { Icon(Icons.Rounded.SkipNext, "Следующий трек", Modifier.size(36.dp)) }
                            IconButton(vm::repeat) { Icon(Icons.Rounded.Repeat, "Повтор", tint = if (state.repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(vm::dislike, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.ThumbDownOffAlt, "Не моё", Modifier.size(20.dp)) }
                            FilledTonalButton({ addToPlaylist(song) }, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.weight(1.15f).testTag("player-add-playlist")) {
                                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, Modifier.size(20.dp)); Spacer(Modifier.width(5.dp)); Text("В плейлист", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val downloaded = state.offline.any { it.id == song.id }
                            val transfer = state.downloads[song.id]
                            val pending = transfer?.state in setOf(androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.RUNNING, androidx.work.WorkInfo.State.BLOCKED)
                            TextButton({ vm.download(song) }, enabled = !downloaded && !pending, contentPadding = PaddingValues(horizontal = 5.dp), modifier = Modifier.weight(1f).testTag("download-from-player")) {
                                Icon(if (downloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                                Text(if (downloaded) "Скачано" else if (pending) if (transfer?.state == androidx.work.WorkInfo.State.RUNNING) "${transfer.progress}%" else "В очереди" else "Скачать")
                            }
                        }
                    }
                }
            }
        }
        if (queue) ModalBottomSheet({ queue = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
                item { Text("Очередь воспроизведения", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp)) }
                itemsIndexed(state.queue, key = { index, track -> "$index:${track.id}" }) { index, track ->
                    SongRow(track, state, { vm.playQueued(index); queue = false }, { vm.favorite(track) })
                }
            }
        }
    }
}
