@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.dedseckms.music.ui

import android.os.Build
import kotlinx.coroutines.flow.first
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import coil.request.ImageRequest
import ru.dedseckms.music.*
import ru.dedseckms.music.data.*
import kotlin.math.sin

@Composable
fun DedSecMusic(vm: MusicViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.onAppResume() }
    val dark = when (state.theme) { "dark", "amoled" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val base = if (state.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else accentScheme(dark, state.palette)
    val colors = styledScheme(base, state.visualStyle, state.theme == "amoled")
    val radius = if (state.visualStyle == "mono") 8.dp else if (state.visualStyle == "glass") 36.dp else 24.dp
    CompositionLocalProvider(LocalVisualStyle provides state.visualStyle, LocalAmoled provides (state.theme == "amoled"), LocalProfilePhoto provides (state.avatarPath to state.avatarVersion)) {
        MaterialTheme(colorScheme = colors, shapes = Shapes(small = RoundedCornerShape(radius / 2), medium = RoundedCornerShape(radius), large = RoundedCornerShape(radius), extraLarge = RoundedCornerShape(radius)),
            typography = Typography(
                headlineLarge = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = if (state.visualStyle == "mono") 0.sp else (-1).sp),
                headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp),
                titleLarge = TextStyle(fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold))) {
            ThemeBackdrop(state.theme == "amoled") {
                if (!state.loggedIn) LoginScreen(state, vm::login, vm::demo) else MainScreen(state, vm)
            }
        }
    }
}

@Composable
private fun LoginScreen(state: AppState, login: (String, String, String) -> Unit, demo: () -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(DEFAULT_MUSIC_SERVER) }
    var showServer by rememberSaveable { mutableStateOf(false) }
    var visible by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 28.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(24.dp))
        Surface(shape = RoundedCornerShape(30.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(82.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.GraphicEq, "DedSec Music", Modifier.size(46.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        Spacer(Modifier.height(12.dp))
        Text("Твоя музыка.\nСвои правила.", style = MaterialTheme.typography.headlineLarge)
        Text("DedSec Music", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("Домашняя библиотека, персональные миксы и музыка с собой.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(username, { username = it }, label = { Text("Логин") }, leadingIcon = { Icon(Icons.Rounded.Person, null) },
            singleLine = true, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, leadingIcon = { Icon(Icons.Rounded.Lock, null) },
            singleLine = true, shape = RoundedCornerShape(20.dp), visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton({ visible = !visible }) { Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Показать пароль") } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        TextButton({ showServer = !showServer }) { Icon(Icons.Rounded.Dns, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Адрес сервера") }
        if (showServer) OutlinedTextField(server, { server = it }, label = { Text("Музыкальный сервер") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth())
        state.error?.let { ErrorCard(it) }
        Button({ login(server, username, password); password = "" }, enabled = !state.loading && username.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(24.dp)) {
            if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else { Text("Подключиться", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.width(10.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) }
        }
        Text("Плейлисты и история принадлежат твоему аккаунту.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(demo, enabled = !state.loading, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Посмотреть интерфейс без входа") }
        Spacer(Modifier.height(20.dp))
    }
}

private data class PlaylistRemoval(val page: CollectionPage, val index: Int)

@Composable
private fun MainScreen(state: AppState, vm: MusicViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val savedTabs = rememberSaveableStateHolder()
    val glass = LocalVisualStyle.current == "glass"
    val navShape = panelShape(24)
    val border = panelBorder()
    var settings by rememberSaveable { mutableStateOf(false) }
    var player by rememberSaveable { mutableStateOf(false) }
    var mix by rememberSaveable { mutableStateOf(false) }
    var createPlaylist by rememberSaveable { mutableStateOf(false) }
    var songMenu by remember { mutableStateOf<Song?>(null) }
    var menuEntry by remember { mutableStateOf<PlaylistRemoval?>(null) }
    var confirmRemoval by remember { mutableStateOf<PlaylistRemoval?>(null) }
    fun showSongMenu(song: Song, page: CollectionPage? = null, index: Int = -1) {
        songMenu = song
        menuEntry = page?.takeIf { it.kind == "playlist" && index in it.songs.indices }?.let { PlaylistRemoval(it, index) }
    }
    var addSong by remember { mutableStateOf<Song?>(null) }
    var uploadLogin by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.upload(it) }
    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it) } }
    BackHandler(state.page != null || state.artistPage != null) { vm.dismissPage() }
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(state.now != null) {
        if (state.now != null && Build.VERSION.SDK_INT >= 33) {
            val preferences = context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
            if (!preferences.getBoolean("notification_permission_asked", false)) {
                preferences.edit().putBoolean("notification_permission_asked", true).apply()
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                state.now?.let { song -> MiniPlayer(song, state, { player = true }, vm::togglePlay, vm::next) }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp,
                    modifier = if (glass) Modifier.padding(horizontal = 12.dp, vertical = 6.dp).liquidGlass().clip(navShape).then(border?.let { Modifier.border(it, navShape) } ?: Modifier) else Modifier) {
                    val tabs = listOf("Главная" to Icons.Rounded.Home, "Поиск" to Icons.Rounded.Search,
                        "Музыка" to Icons.Rounded.LibraryMusic, "Добавить" to Icons.Rounded.CloudUpload)
                    tabs.forEachIndexed { index, (title, icon) -> NavigationBarItem(selected = state.tab == index,
                        onClick = { vm.tab(index) }, icon = { Icon(icon, title) }, label = { Text(title, maxLines = 1, fontSize = 11.sp) }) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.demo) Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Science, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                    Text("Демо интерфейса · без подключения", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(vm::logout) { Text("Войти") }
                }
            }
            savedTabs.SaveableStateProvider(state.tab) {
                val destinations = rememberSaveableStateHolder()
                var savedDestinationKeys by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
                val key = state.page?.let { "collection:${it.kind}:${it.id}" }
                    ?: state.artistPage?.let { "artist:${it.artist.id}" } ?: "root"
                val keep = setOfNotNull("root", state.page?.let { "collection:${it.kind}:${it.id}" }, state.artistPage?.let { "artist:${it.artist.id}" })
                LaunchedEffect(key, keep) {
                    savedDestinationKeys.filterNot { it in keep }.forEach(destinations::removeState)
                    savedDestinationKeys = ArrayList((savedDestinationKeys + key).filter { it in keep }.distinct())
                }
                destinations.SaveableStateProvider(key) {
                    if (state.page != null) CollectionScreen(state.page, state, vm) { song, index -> showSongMenu(song, state.page, index) }
                    else if (state.artistPage != null) ArtistScreen(state.artistPage, state, vm, { showSongMenu(it) })
                    else when (state.tab) {
                        0 -> HomeScreen(state, vm, { settings = true }, { mix = true }, { showSongMenu(it) })
                        1 -> SearchScreen(state, vm, { showSongMenu(it) })
                        2 -> LibraryScreen(state, vm, { settings = true }, { createPlaylist = true }, { showSongMenu(it) })
                        else -> UploadScreen(state, vm, { uploadLogin = true }, { if (state.demo) vm.toast("Подключи свой сервер") else picker.launch(arrayOf("audio/*")) })
                    }
                }
            }
        }
    }
    if (settings) SettingsSheet(state, vm) { settings = false }
    if (player && state.now != null) FullPlayer(state, vm, { addSong = it }) { player = false }
    if (mix) WaveSettingsSheet(state.mixMode, state.mixMood, { mode, mood -> vm.wave(mode, mood); mix = false }) { mix = false }
    if (createPlaylist) CreatePlaylistDialog({ vm.createPlaylist(it); createPlaylist = false }) { createPlaylist = false }
    songMenu?.let { song ->
        val entry = menuEntry
        val editable = entry != null && state.playlists.any { it.id == entry.page.id && it.owner.equals(state.username, true) && !it.readOnly }
        SongActions(song, state, vm, { addSong = song; songMenu = null },
            remove = if (editable && !state.playlistBusy) ({ confirmRemoval = entry; songMenu = null }) else null) { songMenu = null }
    }
    confirmRemoval?.let { entry ->
        AlertDialog(onDismissRequest = { confirmRemoval = null }, title = { Text("Убрать из плейлиста?") },
            text = { Text("«${entry.page.songs[entry.index].title}» будет убран из «${entry.page.title}». Сам трек останется в библиотеке.") },
            confirmButton = { TextButton({ vm.removeFromPlaylist(entry.page, entry.index); confirmRemoval = null }, enabled = !state.playlistBusy) { Text("Убрать") } },
            dismissButton = { TextButton({ confirmRemoval = null }) { Text("Отмена") } })
    }
    addSong?.let { song ->
        LaunchedEffect(song.id) { vm.refreshPlaylists() }
        AddToPlaylistSheet(song, state.playlists, state.playlistsLoading, state.playlistsError, state.playlistBusy, vm::refreshPlaylists,
            { vm.addToPlaylist(it, song); addSong = null }, { title -> vm.createPlaylist(title, song); addSong = null }) { addSong = null }
    }
    if (uploadLogin) UploadLoginDialog(state, vm) { uploadLogin = false }
}

@Composable
private fun PageHeader(title: String, subtitle: String, username: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(subtitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        FilledTonalIconButton(action, shape = CircleShape, modifier = Modifier.size(44.dp)) {
            AccountAvatar(username, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun HomeScreen(state: AppState, vm: MusicViewModel, settings: () -> Unit, mix: () -> Unit, menu: (Song) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { PageHeader("Для тебя", "DEDSEC MUSIC", state.username, settings) }
        state.error?.let { item { ErrorCard(it, vm::refresh) } }
        item { WaveCard(state.mixLoading, state.waveActive && state.playing, vm::toggleWave, mix) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickCard("Мне нравится", "${state.favoriteIds.size} треков", Icons.Rounded.Favorite,
                    MaterialTheme.colorScheme.secondaryContainer, Modifier.weight(1f), vm::openFavorites)
                QuickCard("С собой", "${state.offline.size} скачано", Icons.Rounded.DownloadDone,
                    MaterialTheme.colorScheme.tertiaryContainer, Modifier.weight(1f), vm::openOffline)
            }
        }
        if (state.albums.isNotEmpty()) {
            item { SectionHeader("Недавно добавлено", "Альбомы из твоей библиотеки") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.albums, key = Album::id) { album -> AlbumCard(album) { vm.openAlbum(album) } }
                }
            }
        }
        if (state.history.isNotEmpty()) {
            item { SectionHeader("Снова в настроение", "Недавно слушал") }
            items(state.history.take(3), key = { "history:${it.song.id}" }) { entry ->
                SongRow(entry.song, state, { vm.play(state.history.map { it.song }, state.history.indexOf(entry), source = "Недавно слушали") }, { menu(entry.song) })
            }
        }
        item { SectionHeader("Твоя музыка", "Своя коллекция, всегда рядом") }
        if (state.loading && state.songs.isEmpty()) item { LoadingCard() }
        if (!state.loading && state.songs.isEmpty()) item { EmptyCard("Библиотека собирается", "Музыка появится после сканирования сервера.", Icons.Rounded.LibraryMusic) }
        items(state.songs.take(8), key = { "home:${it.id}" }) { song -> SongRow(song, state, { vm.play(state.songs, state.songs.indexOf(song), source = "Вся музыка") }, { menu(song) }) }
        item { TextButton({ vm.tab(2) }, modifier = Modifier.fillMaxWidth()) { Text("Вся музыка"); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(start = 8.dp)) } }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun WaveCard(loading: Boolean, playing: Boolean, play: () -> Unit, settings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Reverse), label = "breath")
    Surface(shape = panelShape(38), border = panelBorder(), color = colors.primaryContainer, modifier = Modifier.fillMaxWidth().height(270.dp).liquidGlass()) {
        Box {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width * (0.76f + phase * 0.04f), size.height * 0.36f)
                drawCircle(colors.primary.copy(alpha = .17f), size.width * .44f, center)
                drawCircle(colors.primary.copy(alpha = .15f), size.width * .32f, center)
                drawCircle(colors.primary.copy(alpha = .20f), size.width * .21f, center)
                for (i in 0..12) {
                    val x = size.width * .52f + i * size.width * .027f
                    val height = (18 + (sin(i * .75f + phase * 3f) + 1) * 38) * density
                    drawLine(colors.onPrimaryContainer.copy(alpha = .27f), Offset(x, center.y - height / 2), Offset(x, center.y + height / 2),
                        strokeWidth = 4 * density, cap = StrokeCap.Round)
                }
            }
            Column(Modifier.fillMaxSize().padding(26.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = colors.onPrimaryContainer.copy(alpha = .10f), shape = CircleShape) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(14.dp), tint = colors.onPrimaryContainer)
                            Spacer(Modifier.width(6.dp)); Text("Под твой вкус", style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(settings) { Icon(Icons.Rounded.Tune, "Настроить микс", tint = colors.onPrimaryContainer) }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("Моя\nволна", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 42.sp, lineHeight = 43.sp), color = colors.onPrimaryContainer)
                        Spacer(Modifier.height(8.dp))
                        Text("Из твоей музыки", style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer.copy(alpha = .75f))
                    }
                    FilledIconButton(play, enabled = !loading, shape = RoundedCornerShape(25.dp), modifier = Modifier.size(70.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.onPrimaryContainer, contentColor = colors.primaryContainer)) {
                        if (loading) CircularProgressIndicator(Modifier.size(28.dp), color = colors.primaryContainer, strokeWidth = 3.dp)
                        else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Приостановить волну" else "Слушать волну", Modifier.size(40.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun QuickCard(title: String, subtitle: String, icon: ImageVector, color: Color, modifier: Modifier, click: () -> Unit) {
    Surface(onClick = click, shape = panelShape(28), border = panelBorder(), color = color, modifier = modifier.height(132.dp).liquidGlass()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, Modifier.size(26.dp))
            Column { Text(title, style = MaterialTheme.typography.titleSmall); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
@Composable internal fun SectionHeader(title: String, subtitle: String = "") {
    Column { Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp)) }
}
@Composable internal fun AlbumCard(album: Album, click: () -> Unit) {
    Column(Modifier.width(148.dp).clickable(onClick = click).padding(bottom = 4.dp)) {
        Artwork(album.coverArt, album.name, Modifier.size(148.dp), 24)
        Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
        Text(album.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Artwork(coverArt: String, seed: String, modifier: Modifier = Modifier, corners: Int = 16) {
    val app = LocalContext.current.applicationContext as MusicApp
    val account = app.credentials.activeId()
    BoxWithConstraints(modifier.clip(RoundedCornerShape(corners.dp))) {
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val requested = maxWidth.value * density
        val imageSize = if (requested <= 192) 192 else if (requested <= 384) 384 else 768
        var cached by remember(account, coverArt, imageSize) { mutableStateOf(account?.let { app.covers.peek(it, coverArt, imageSize) }) }
        var preview by remember(account, coverArt) { mutableStateOf(account?.let { app.covers.peek(it, coverArt, 192) }) }
        LaunchedEffect(account, coverArt, imageSize) {
            if (account != null && coverArt.isNotBlank()) {
                try {
                    if (cached == null && imageSize > 192) preview = app.covers.file(account, coverArt, 192)
                    cached = app.covers.file(account, coverArt, imageSize)
                }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { /* Keep the inexpensive local placeholder. */ }
            }
        }
        val palettes = listOf(Color(0xFF465C9B) to Color(0xFF171F40), Color(0xFF8D5849) to Color(0xFF321C30),
            Color(0xFF508779) to Color(0xFF193C3A), Color(0xFF8171A1) to Color(0xFF2A2446), Color(0xFFB38D4D) to Color(0xFF493322))
        val pair = palettes[Math.floorMod(seed.hashCode(), palettes.size)]
        Canvas(Modifier.matchParentSize().background(Brush.linearGradient(listOf(pair.first, pair.second)))) {
            val radius = size.minDimension * .34f
            drawCircle(Color.White.copy(alpha = .14f), radius, Offset(size.width * .65f, size.height * .37f))
            drawCircle(Color.White.copy(alpha = .20f), radius * .74f, Offset(size.width * .65f, size.height * .37f), style = Stroke(size.minDimension * .025f))
            drawCircle(Color.White.copy(alpha = .22f), radius * .12f, Offset(size.width * .65f, size.height * .37f))
            drawLine(Color.White.copy(alpha = .24f), Offset(size.width * .13f, size.height * .78f), Offset(size.width * .70f, size.height * .78f), size.minDimension * .04f, StrokeCap.Round)
        }
        val sourceSize = if (cached != null) imageSize else 192
        val placeholderSize = if (app.images.memoryCache?.get(coil.memory.MemoryCache.Key("$account:$coverArt:768")) != null) 768 else 192
        if (cached != null || preview != null) AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(cached ?: preview)
            .memoryCacheKey("$account:$coverArt:$sourceSize").placeholderMemoryCacheKey("$account:$coverArt:$placeholderSize").size(sourceSize).crossfade(false).build(), imageLoader = app.images,
            contentDescription = "Обложка: $seed", modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun SongRow(song: Song, state: AppState, play: () -> Unit, menu: () -> Unit) {
    val active = state.now?.id == song.id
    val background by animateColorAsState(if (active) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .55f) else Color.Transparent, label = "active")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(background).clickable(onClick = play).padding(vertical = 7.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Artwork(song.coverArt, song.album.ifBlank { song.title }, Modifier.size(54.dp), 14)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.offline.any { it.id == song.id }) { Icon(Icons.Rounded.DownloadDone, "Скачано", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp)) }
                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.downloads[song.id]?.let { transfer ->
                Text(if (transfer.state == WorkInfo.State.FAILED) "Не удалось скачать" else if (transfer.state == WorkInfo.State.RUNNING) "Скачивание ${transfer.progress}%" else "Ожидает скачивания",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (song.id in state.favoriteIds) Icon(Icons.Rounded.Favorite, "Нравится", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        IconButton(menu) { Icon(Icons.Rounded.MoreVert, "Действия с треком") }
    }
}

@Composable private fun LibraryScreen(state: AppState, vm: MusicViewModel, settings: () -> Unit, create: () -> Unit, menu: (Song) -> Unit) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val scrollStates = listOf(rememberLazyListState(), rememberLazyListState(), rememberLazyListState())
    LaunchedEffect(filter, state.songs.size, state.moreAvailable) {
        if (filter == 0 && state.moreAvailable && !state.demo && state.songs.isNotEmpty()) {
            snapshotFlow { (scrollStates[0].layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.songs.size - 8 }
                .first { it }
            vm.loadMore()
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        PageHeader("Музыка", "ВСЯ КОЛЛЕКЦИЯ", state.username, settings)
        Text(state.libraryTotal?.let { "$it треков на сервере · твои плейлисты и офлайн" } ?: "Треки сервера, твои плейлисты и музыка для офлайна.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == 0, { filter = 0 }, { Text("Треки") })
            FilterChip(filter == 1, { filter = 1 }, { Text("Альбомы") })
            FilterChip(filter == 2, { filter = 2 }, { Text("Плейлисты") })
            Spacer(Modifier.weight(1f))
            IconButton(vm::refresh) { Icon(Icons.Rounded.Refresh, "Обновить медиатеку") }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(state = scrollStates[filter], verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp)) {
            if (filter == 0) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(vm::openFavorites, { Text("Любимое") }, leadingIcon = { Icon(Icons.Rounded.Favorite, null, Modifier.size(17.dp)) })
                        AssistChip(vm::openOffline, { Text("Скачанное") }, leadingIcon = { Icon(Icons.Rounded.DownloadDone, null, Modifier.size(17.dp)) })
                    }
                }
                items(state.songs, key = Song::id) { song -> SongRow(song, state, { vm.play(state.songs, state.songs.indexOf(song), source = "Вся музыка") }, { menu(song) }) }
                if (state.moreAvailable && !state.demo) item { OutlinedButton(vm::loadMore, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Показать ещё") } }
            } else if (filter == 1) {
                items(state.albums.chunked(2), key = { it.first().id }) { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { album -> Column(Modifier.weight(1f).clickable { vm.openAlbum(album) }) {
                        Artwork(album.coverArt, album.name, Modifier.fillMaxWidth().aspectRatio(1f), 26)
                        Text(album.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 9.dp))
                        Text(album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                } }
            } else {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Плейлисты твоего аккаунта", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(start = 6.dp))
                        FilledTonalIconButton(create) { Icon(Icons.Rounded.Add, "Создать плейлист") }
                    }
                }
                if (state.playlists.isEmpty()) item { EmptyCard("Начни свою коллекцию", "Создай первый приватный плейлист и добавь любимые треки.", Icons.AutoMirrored.Rounded.PlaylistAdd) }
                items(state.playlists, key = MusicPlaylist::id) { playlist -> PlaylistRow(playlist) { vm.openPlaylist(playlist) } }
            }
        }
    }
}

@Composable private fun PlaylistRow(playlist: MusicPlaylist, click: () -> Unit) {
    ListItem(headlineContent = { Text(playlist.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${playlist.songCount} треков · ${if (playlist.public) "публичный" else "приватный"}") },
        leadingContent = { Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(27.dp)) }
        } }, trailingContent = { Icon(Icons.Rounded.ChevronRight, null) }, modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = click),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}

@Composable private fun CollectionScreen(page: CollectionPage, state: AppState, vm: MusicViewModel, menu: (Song, Int) -> Unit) {
    val scroll = rememberLazyListState()
    SwipeBackSurface({ !scroll.canScrollBackward }, vm::dismissPage) {
    LazyColumn(state = scroll, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { IconButton(vm::dismissPage) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(page.songs.firstOrNull()?.coverArt.orEmpty(), page.title, Modifier.size(112.dp), 30)
                Column(Modifier.padding(start = 20.dp).weight(1f)) {
                    Text(page.title, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("${page.songs.size} треков", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ vm.play(page.songs, source = collectionSource(page.kind, page.title)) }, enabled = page.songs.isNotEmpty(), modifier = Modifier.weight(1f).height(52.dp)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Слушать") }
                FilledTonalButton({ vm.play(page.songs.shuffled(), source = collectionSource(page.kind, page.title)) }, enabled = page.songs.isNotEmpty(), modifier = Modifier.height(52.dp)) { Icon(Icons.Rounded.Shuffle, "Перемешать") }
            }
        }
        if (page.songs.isEmpty()) item { EmptyCard("Здесь пока тихо", "Добавь треки из поиска или медиатеки.", Icons.Rounded.MusicNote) }
        itemsIndexed(page.songs, key = { index, song -> "$index:${song.id}" }) { index, song -> SongRow(song, state, { vm.play(page.songs, index, source = collectionSource(page.kind, page.title)) }, { menu(song, index) }) }
        if (page.kind == "genre" && state.pageMore) item { OutlinedButton(vm::moreGenre, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Ещё треки") } }
    }
}
}

@Composable internal fun EmptyCard(title: String, subtitle: String, icon: ImageVector) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(78.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary) }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
@Composable internal fun ErrorCard(text: String, refresh: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            refresh?.let { TextButton(it) { Text("Повторить") } } }
    }
}
@Composable internal fun LoadingCard() { Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }

fun bytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.forLanguageTag("ru"), "%.1f ГБ", value / (1024.0 * 1024 * 1024))
    value >= 1024 * 1024 -> String.format(java.util.Locale.forLanguageTag("ru"), "%.1f МБ", value / (1024.0 * 1024))
    else -> "${value / 1024} КБ"
}
internal fun time(value: Long): String { val seconds = value.coerceAtLeast(0) / 1000; return "%d:%02d".format(seconds / 60, seconds % 60) }

@Composable private fun MiniPlayer(song: Song, state: AppState, expand: () -> Unit, play: () -> Unit, next: () -> Unit) {
    Surface(shape = panelShape(22), border = panelBorder(), color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp).fillMaxWidth().liquidGlass().clickable(onClick = expand)) {
        Column {
            Row(Modifier.padding(start = 9.dp, end = 7.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.coverArt, song.album.ifBlank { song.title }, Modifier.size(44.dp), 14)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .7f))
                }
                IconButton(play) {
                    if (state.buffering) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Пауза" else "Продолжить", Modifier.size(29.dp))
                }
                IconButton(next) { Icon(Icons.Rounded.SkipNext, "Следующий трек", Modifier.size(28.dp)) }
            }
            WavyTrack(if (state.duration > 0) state.position.toFloat() / state.duration else 0f, state.playing,
                modifier = Modifier.fillMaxWidth().height(8.dp), compact = true)
        }
    }
}

@Composable private fun SettingsSheet(state: AppState, vm: MusicViewModel, dismiss: () -> Unit) {
    ModalBottomSheet(dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Настройки", style = MaterialTheme.typography.headlineMedium)
            ProfileControls(state, vm)
            AppearanceControls(state, vm)
            HorizontalDivider()
            PreferenceSwitch("Скачивать по Wi-Fi", "Для сохранения музыки на телефон", state.wifiOnly, vm::wifiOnly)
            Text("${state.offline.size} треков доступны офлайн", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ListItem(headlineContent = { Text("Android Auto") }, supportingContent = { Text("Плейлисты, любимое и моя волна") }, leadingContent = { Icon(Icons.Rounded.DirectionsCar, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            HorizontalDivider()
            SystemPlayerSettings()
            HorizontalDivider()
            UpdateControls(vm)
            Text("DedSec Music · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton({ vm.logout(); dismiss() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.AutoMirrored.Rounded.Logout, null); Spacer(Modifier.width(8.dp)); Text(if (state.demo) "Войти в свой аккаунт" else "Выйти из аккаунта") }
        }
    }
}
@Composable private fun PreferenceSwitch(title: String, subtitle: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(value, change)
    }
}

@Composable private fun SongActions(song: Song, state: AppState, vm: MusicViewModel, add: () -> Unit, remove: (() -> Unit)? = null, dismiss: () -> Unit) {
    var adjusting by remember { mutableStateOf(false) }
    var gain by remember(song.id) { mutableFloatStateOf(vm.trackGain(song).toFloat()) }
    ModalBottomSheet(dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                Artwork(song.coverArt, song.title, Modifier.size(64.dp), 18)
                Column(Modifier.weight(1f).padding(start = 14.dp)) { Text(song.title, style = MaterialTheme.typography.titleMedium); Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            ActionRow(if (song.id in state.favoriteIds) "Убрать из любимого" else "Мне нравится", Icons.Rounded.Favorite) { vm.favorite(song); dismiss() }
            ActionRow("Добавить в плейлист", Icons.AutoMirrored.Rounded.PlaylistAdd, add)
            remove?.let { ActionRow("Убрать из этого плейлиста", Icons.Rounded.PlaylistRemove, it) }
            ActionRow("Громкость трека", Icons.AutoMirrored.Rounded.VolumeUp) { adjusting = true }
            val genres = song.genres.ifEmpty { listOf(song.genre) }.filter(String::isNotBlank)
            if (genres.isNotEmpty()) Text("Теги жанра: ${genres.joinToString()}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            ActionRow("Открыть исполнителя", Icons.Rounded.Person) { vm.openArtist(song); dismiss() }
            if (state.offline.any { it.id == song.id }) ActionRow("Удалить офлайн-копию", Icons.Rounded.DeleteOutline) { vm.removeOffline(song); dismiss() }
            else ActionRow("Скачать на телефон", Icons.Rounded.Download) { vm.download(song); dismiss() }
        }
    }
    if (adjusting) AlertDialog(onDismissRequest = { adjusting = false }, title = { Text("Громкость трека") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Тише или громче только этот трек. Настройка сохраняется в твоём аккаунте на этом телефоне.")
            Text("${if (gain > 0) "+" else ""}${gain.toInt()} дБ", style = MaterialTheme.typography.titleLarge)
            Slider(gain, { gain = it; vm.trackGain(song, it.toInt()) }, valueRange = -6f..6f, steps = 11)
            Text("Усиление использует системный ограничитель пиков и зависит от поддержки телефона.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton({ adjusting = false }) { Text("Готово") } },
        dismissButton = { TextButton({ gain = 0f; vm.trackGain(song, 0) }) { Text("Сбросить") } })
}
@Composable private fun ActionRow(title: String, icon: ImageVector, click: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = click),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}
@Composable private fun CreatePlaylistDialog(create: (String) -> Unit, dismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, icon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }, title = { Text("Новый плейлист") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Будет доступен только твоему аккаунту."); OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true) } },
        confirmButton = { TextButton({ create(name.trim()) }, enabled = name.isNotBlank()) { Text("Создать") } }, dismissButton = { TextButton(dismiss) { Text("Отмена") } })
}
@Composable internal fun AddToPlaylistSheet(song: Song, playlists: List<MusicPlaylist>, loading: Boolean, error: String?, busy: Boolean,
    retry: () -> Unit, add: (MusicPlaylist) -> Unit, create: (String) -> Unit, dismiss: () -> Unit) {
    var createNew by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).testTag("playlist-picker")) {
            item { Text("В какой плейлист?", style = MaterialTheme.typography.titleLarge) }
            item { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { TextButton({ createNew = true }, enabled = !busy) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Создать новый") } }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { item { ErrorCard(it, retry) } }
            if (!loading && error == null && playlists.isEmpty()) item { Text("Пока нет своих плейлистов. Создай первый.", modifier = Modifier.padding(vertical = 12.dp)) }
            items(playlists, key = MusicPlaylist::id) { playlist ->
                ListItem(headlineContent = { Text(playlist.name) },
                    supportingContent = { Text(if (playlist.readOnly) "Только просмотр" else "${playlist.songCount} треков") },
                    leadingContent = { Icon(if (playlist.readOnly) Icons.Rounded.Lock else Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(enabled = !busy && !playlist.readOnly) { add(playlist) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
    }
    if (createNew) CreatePlaylistDialog(create) { createNew = false }
}
@Composable private fun UploadLoginDialog(state: AppState, vm: MusicViewModel, dismiss: () -> Unit) {
    var server by rememberSaveable { mutableStateOf(DEFAULT_UPLOAD_SERVER) }
    var username by rememberSaveable { mutableStateOf(if (state.demo) "" else state.username) }
    var password by remember { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, icon = { Icon(Icons.Rounded.CloudUpload, null) }, title = { Text("Подключить загрузку") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Нужен аккаунт сервера загрузки. Администратор создаёт его отдельно от музыкального аккаунта.")
            OutlinedTextField(username, { username = it }, label = { Text("Логин загрузки") }, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("Пароль загрузки") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
            TextButton({ advanced = !advanced }) { Text("Адрес сервера") }
            if (advanced) OutlinedTextField(server, { server = it }, label = { Text("Сервер загрузки") }, singleLine = true)
        } }, confirmButton = { TextButton({
            if (state.demo) vm.toast("Подключи музыкальный аккаунт") else vm.connectUpload(server, username, password)
            password = ""; dismiss()
        }, enabled = username.isNotBlank() && password.isNotEmpty()) { Text("Подключить") } }, dismissButton = { TextButton(dismiss) { Text("Отмена") } })
}
