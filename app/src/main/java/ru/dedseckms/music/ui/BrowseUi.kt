@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.dedseckms.music.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import ru.dedseckms.music.*
import ru.dedseckms.music.data.*

@Composable
internal fun SearchScreen(state: AppState, vm: MusicViewModel, menu: (Song) -> Unit) {
    var searchFocused by remember { mutableStateOf(false) }
    val scrollStates = SearchCategory.entries.map { rememberLazyListState() }
    val category = state.searchCategory
    val artists = if (state.query.isBlank()) state.artists else state.searchArtists
    val genres = state.genres.filter { state.query.isBlank() || it.name.contains(state.query, true) }
    val songs = if (state.query.isBlank()) state.songs else state.search
    val waiting = if (state.query.isNotBlank()) state.searching else when (category) {
        SearchCategory.ARTISTS -> state.artistsLoading
        SearchCategory.GENRES -> state.genresLoading
        SearchCategory.ALL -> state.catalogLoading
        SearchCategory.SONGS -> false
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Поиск", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(state.query, vm::query, placeholder = { if (!searchFocused) Text("Трек, исполнитель или жанр", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (state.query.isNotBlank()) IconButton({ vm.query("") }) { Icon(Icons.Rounded.Close, "Очистить поиск") } },
            singleLine = true, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.isFocused })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(listOf(SearchCategory.ALL to "Всё", SearchCategory.SONGS to "Треки", SearchCategory.ARTISTS to "Исполнители", SearchCategory.GENRES to "Жанры")) { (value, label) ->
                FilterChip(category == value, { vm.searchCategory(value) }, { Text(label) })
            }
        }
        if (waiting) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), state = scrollStates[category.ordinal], verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (category == SearchCategory.ALL && artists.isNotEmpty()) {
                item { SectionHeader("Исполнители") }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                    items(artists.take(12), key = Artist::id) { artist ->
                        Column(Modifier.width(94.dp).padding(bottom = 4.dp).clickable { vm.openArtist(artist) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            ArtistAvatar(artist, Modifier.size(74.dp))
                            Text(artist.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                } }
            }
            if (category == SearchCategory.ARTISTS) {
                items(artists, key = Artist::id) { artist ->
                    ListItem(headlineContent = { Text(artist.name) }, supportingContent = { Text("Исполнитель · ${artist.albumCount} альбомов") },
                        leadingContent = { ArtistAvatar(artist, Modifier.size(54.dp)) }, trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { vm.openArtist(artist) })
                }
            }
            if ((category == SearchCategory.ALL || category == SearchCategory.GENRES) && genres.isNotEmpty()) {
                item { SectionHeader("Жанры", "По тегам файлов: они могут отличаться от звучания") }
                items(if (category == SearchCategory.ALL) genres.take(6) else genres, key = Genre::name) { genre ->
                    ListItem(headlineContent = { Text(genre.name, fontWeight = FontWeight.Medium) }, supportingContent = { Text("${genre.songCount} треков") },
                        leadingContent = { Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { vm.openGenre(genre) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
                }
            }
            if (category == SearchCategory.SONGS || (category == SearchCategory.ALL && state.query.isNotBlank())) {
                if (songs.isNotEmpty()) item { SectionHeader("Треки") }
                items(songs, key = Song::id) { song -> SongRow(song, state, { vm.play(songs, songs.indexOf(song), source = "Поиск") }, { menu(song) }) }
            }
            val empty = when (category) {
                SearchCategory.ALL -> artists.isEmpty() && genres.isEmpty() && (state.query.isBlank() || songs.isEmpty())
                SearchCategory.ARTISTS -> artists.isEmpty()
                SearchCategory.GENRES -> genres.isEmpty()
                SearchCategory.SONGS -> songs.isEmpty()
            }
            state.catalogError?.takeIf { state.query.isBlank() && category != SearchCategory.SONGS }?.let { error ->
                item { ErrorCard(error, vm::loadCatalog) }
            }
            if (empty && !waiting && state.catalogError == null) item {
                EmptyCard(if (state.query.isBlank()) "Каталог пока пуст" else "Ничего не нашлось", "Ищи треки, открывай исполнителей или выбирай жанр.", Icons.Rounded.TravelExplore)
            }
        }
    }
}

@Composable private fun ArtistAvatar(artist: Artist, modifier: Modifier) {
    if (artist.coverArt.isNotBlank()) Artwork(artist.coverArt, artist.name, modifier.clip(CircleShape), 100)
    else Surface(modifier = modifier, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment = Alignment.Center) { Text(artist.name.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSecondaryContainer) }
    }
}

@Composable
internal fun ArtistScreen(profile: ArtistProfile, state: AppState, vm: MusicViewModel, menu: (Song) -> Unit) {
    val scroll = rememberLazyListState()
    SwipeBackSurface({ !scroll.canScrollBackward }, vm::dismissPage) {
    LazyColumn(state = scroll, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { IconButton(vm::dismissPage) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад к поиску") } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ArtistAvatar(profile.artist, Modifier.size(112.dp))
                Column(Modifier.weight(1f)) {
                    Text("ИСПОЛНИТЕЛЬ", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary)
                    Text(profile.artist.name, style = MaterialTheme.typography.headlineMedium)
                    Text(if (profile.loading) "Собираем дискографию…" else "${profile.songs.size} треков · ${profile.albums.size} альбомов", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Button({ vm.play(profile.songs, source = collectionSource("artist", profile.artist.name)) }, enabled = profile.songs.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Слушать исполнителя")
        } }
        if (profile.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        profile.error?.let { error -> item { ErrorCard(error) { vm.openArtist(profile.artist) } } }
        if (profile.albums.isNotEmpty()) {
            item { SectionHeader("Альбомы") }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(profile.albums, key = Album::id) { album -> AlbumCard(album) { vm.openAlbum(album) } }
            } }
        }
        if (profile.songs.isNotEmpty()) item { SectionHeader("Треки исполнителя") }
        items(profile.songs, key = Song::id) { song -> SongRow(song, state, { vm.play(profile.songs, profile.songs.indexOf(song), source = collectionSource("artist", profile.artist.name)) }, { menu(song) }) }
        if (!profile.loading && profile.error == null && profile.songs.isEmpty()) item { EmptyCard("Треков пока нет", "Здесь появится музыка этого исполнителя из коллекции сервера.", Icons.Rounded.Person) }
    }
}
}

@Composable
internal fun UploadScreen(state: AppState, vm: MusicViewModel, connect: () -> Unit, pick: () -> Unit) {
    LaunchedEffect(state.username) { vm.refreshImport() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { YandexImportCard(state.importStatus, !state.demo, vm::startImport, vm::cancelImport, vm::refreshImport) }
        item {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Добавить музыку", style = MaterialTheme.typography.headlineMedium)
            Text("Аудиофайлы с телефона в общую коллекцию сервера.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("До 100 МБ и до 20 минут на файл", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Button(if (state.uploadConnected) pick else connect, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(if (state.uploadConnected) Icons.Rounded.Add else Icons.Rounded.Link, null)
                Spacer(Modifier.width(8.dp)); Text(if (state.uploadConnected) "Выбрать файлы" else "Подключить загрузку")
            }
        }
        }
            if (state.uploads.isNotEmpty()) {
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Мои загрузки", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (state.checkingUploads) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else IconButton(vm::checkUploads) { Icon(Icons.Rounded.Refresh, "Проверить появление треков") }
                } }
                state.uploadCheckError?.let { item { ErrorCard(it, vm::checkUploads) } }
                items(state.uploads, key = { it.id.ifBlank { it.name } }) { transfer ->
                    val finished = transfer.state == WorkInfo.State.SUCCEEDED
                    val song = transfer.indexedSong
                    val text = when (transfer.state) {
                        WorkInfo.State.SUCCEEDED -> if (song != null) "Готово · доступен для прослушивания" else if (state.scanActive) "Файл на сервере · добавляется в каталог" else "Файл на сервере · проверяем появление в музыке"
                        WorkInfo.State.RUNNING -> "Передаётся на сервер · ${transfer.progress}%"
                        WorkInfo.State.FAILED -> transfer.error.ifBlank { "Не удалось загрузить" }
                        WorkInfo.State.CANCELLED -> "Отменено"
                        else -> "В очереди отправки"
                    }
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(if (song != null) Icons.Rounded.CheckCircle else Icons.Rounded.CloudUpload, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(transfer.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (transfer.state == WorkInfo.State.RUNNING) LinearProgressIndicator(progress = { transfer.progress / 100f }, modifier = Modifier.fillMaxWidth())
                            if (song != null) FilledTonalButton({ vm.play(listOf(song), source = "Мои загрузки") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.PlayArrow, null); Text("Слушать") }
                            else if (finished) Text("Обычно это занимает несколько минут. Сервер проверяет новые файлы каждые 5 минут; приложение обновляет статус автоматически.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else item { EmptyCard("Добавь первый трек", "После отправки здесь будут прогресс и кнопка прослушивания.", Icons.Rounded.DriveFolderUpload) }
            state.serverDisk?.let { disk -> item {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Storage, null); Spacer(Modifier.width(10.dp)); Text("Место на сервере", modifier = Modifier.weight(1f))
                            IconButton(vm::refreshDisk) { Icon(Icons.Rounded.Refresh, "Обновить место на сервере") }
                        }
                        LinearProgressIndicator(progress = { if (disk.total > 0) (disk.used.toDouble() / disk.total).toFloat().coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                        Text("${bytes(disk.used)} из ${bytes(disk.total)} · свободно ${bytes(disk.free)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } }
            item { Text("MP3, FLAC, M4A, AAC, OGG, OPUS и WAV. Существующие файлы сохраняются.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable internal fun SystemPlayerSettings() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Системный плеер", style = MaterialTheme.typography.titleMedium)
        Text("Название трека, обложка и управление передаются в системную панель музыки, на экран блокировки и совместимые устройства.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (Build.MANUFACTURER.lowercase() in setOf("realme", "oppo", "oneplus")) Text("Живые оповещения: в realme UI 6.0 список поддерживаемых музыкальных приложений задаёт прошивка. Разрешение уведомлений само по себе не гарантирует показ в островке.", style = MaterialTheme.typography.bodySmall)
        if (Build.MANUFACTURER.equals("samsung", true)) Text("Для Now Bar проверь: Настройки телефона → Экран блокировки и AOD → Now Bar → Медиаплеер. Названия пунктов зависят от One UI.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) {
            Icon(Icons.Rounded.Notifications, null); Spacer(Modifier.width(8.dp)); Text("Уведомления приложения")
        }
    }
}
