@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package ru.dedseckms.music.desktop

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.Image as SkiaImage
import ru.dedseckms.music.data.*
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import javax.swing.JFileChooser
import kotlin.math.sin

private val Rounded: RoundedCornerShape @Composable get() = RoundedCornerShape(if (LocalAppearance.current.style == "minimal") 12.dp else 28.dp)
private val LocalCovers = staticCompositionLocalOf<DesktopCovers?> { null }
private val LocalSample = staticCompositionLocalOf { false }

@Composable internal fun BrandIcon(modifier: Modifier) {
    val image = remember { SkiaImage.makeFromEncoded(DesktopController::class.java.getResourceAsStream("/app-icon.png")!!.use { it.readBytes() }).use { it.toComposeImageBitmap() } }
    Image(image, "DedSec Music", modifier)
}

@Composable fun DesktopApp(controller: DesktopController, dragArea: @Composable (@Composable () -> Unit) -> Unit = { content -> content() },
    maximized: Boolean = false, minimize: () -> Unit = {}, maximize: () -> Unit = {}, close: () -> Unit = {}) {
    val s by controller.state.collectAsState()
    val audio by controller.audio.state.collectAsState()
    val appearance = Appearance(s.dark, s.palette, s.style, s.amoled)
    val colors = appearanceColors(appearance)
    CompositionLocalProvider(LocalAppearance provides appearance, LocalContentColor provides colors.onSurface) {
        MaterialTheme(colorScheme = colors, typography = Typography(headlineLarge = androidx.compose.ui.text.TextStyle(fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
            headlineMedium = androidx.compose.ui.text.TextStyle(fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)), shapes = Shapes(large = Rounded, extraLarge = Rounded)) {
            Box(Modifier.fillMaxSize().background(colors.background)) {
                AppearanceBackdrop()
                Column {
                    WindowChrome(dragArea, maximized, minimize, maximize, close)
                    Box(Modifier.weight(1f)) {
                        CompositionLocalProvider(LocalCovers provides controller.covers, LocalSample provides s.demo) {
                            if (!s.loggedIn) Login(controller, s) else Workspace(controller, s, audio)
                        }
                    }
                }
            }
            DesktopUpdateDialog(controller)
        }
    }
}

@Composable private fun Login(c: DesktopController, s: DesktopState) {
    var server by remember { mutableStateOf(DEFAULT_MUSIC_SERVER) }; var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var remember by remember { mutableStateOf(true) }
    Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Box(Modifier.weight(1.1f).fillMaxHeight().clip(RoundedCornerShape(36.dp)).background(Brush.linearGradient(listOf(lerp(MaterialTheme.colorScheme.primary, Color.Black, .56f), lerp(MaterialTheme.colorScheme.secondary, Color.Black, .35f), lerp(MaterialTheme.colorScheme.tertiary, Color.Black, .65f)))).padding(48.dp)) {
            Canvas(Modifier.matchParentSize()) { drawCircle(Color.White.copy(.08f), size.width * .55f, Offset(size.width, size.height * .4f)); drawCircle(Color.White.copy(.06f), size.width * .7f, Offset(0f, size.height)) }
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) { BrandIcon(Modifier.size(40.dp)); Spacer(Modifier.width(12.dp)); Text("DEDSEC MUSIC", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 3.sp) }
                Column { Text("Своя музыка.\nСвоя территория.", style = MaterialTheme.typography.headlineLarge, color = Color.White); Spacer(Modifier.height(20.dp)); Text("Любимые треки, личные плейлисты\nи волна под твоё настроение.", color = Color.White.copy(.8f), fontSize = 18.sp, lineHeight = 28.sp) }
                Text("YOUR LIBRARY. YOUR RHYTHM.", color = Color.White.copy(.65f), fontSize = 11.sp, letterSpacing = 3.sp)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.Center) {
            Text("Рады видеть", style = MaterialTheme.typography.headlineLarge)
            Text("Подключись к своей музыкальной библиотеке", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 28.dp))
            OutlinedTextField(server, { server = it }, label = { Text("Адрес сервера") }, leadingIcon = { Icon(Icons.Rounded.Dns, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp)); OutlinedTextField(username, { username = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp)); OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(remember, { remember = it }); Text("Запомнить вход на этом компьютере", style = MaterialTheme.typography.bodySmall) }
            Text("Сохранённый пароль защищён Windows", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            s.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp)) }
            Button({ c.login(server, username, password, remember); password = "" }, enabled = !s.busy && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(54.dp)) {
                if (s.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else { Text("Войти в музыку"); Spacer(Modifier.width(12.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) }
            }
            TextButton(c::demo, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp)) { Text("Посмотреть интерфейс без входа") }
        }
    }
}

@Composable private fun Workspace(c: DesktopController, s: DesktopState, audio: AudioState) {
    var add by remember { mutableStateOf<Song?>(null) }; var create by remember { mutableStateOf(false) }; var queueVisible by remember { mutableStateOf(true) }; var compactQueue by remember { mutableStateOf(false) }; var compact by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<Pair<DesktopPage, Int>?>(null) }; var waveSettings by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snack.showSnackbar(it); c.notice(null) } }
    Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snack) }, bottomBar = { BottomPlayer(c, s, audio, { add = it }, { if (compact) compactQueue = true else queueVisible = !queueVisible }) }) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            Sidebar(c, s, { create = true })
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val wide = maxWidth > 1020.dp
                SideEffect { compact = !wide }
                Row(Modifier.fillMaxSize().padding(top = 14.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().glassPanel(Rounded, MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Header(c, s)
                        if (s.busy || s.searching) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))
                        val songs = when { s.page != null -> s.page.songs; s.tab == "Любимое" -> s.favorites; s.tab == "Скачанное" -> s.offline; s.tab == "Поиск" && s.query.isNotBlank() -> s.search.songs; else -> s.songs }
                        val source = s.page?.let { collectionSource(it.kind, it.title) } ?: s.tab
                        when {
                            s.page != null -> Collection(c, s, songs, source, { add = it }, { index -> remove = s.page to index })
                            s.tab == "Главная" -> Home(c, s, { waveSettings = true }) { add = it }
                            s.tab == "Настройки" -> Settings(c, s)
                            s.tab == "Поиск" -> SearchScreen(c, s) { add = it }
                            s.tab == "Добавить" -> Upload(c, s)
                            else -> Library(c, s, songs, source, { add = it }, { create = true })
                        }
                    }
                    if (wide && queueVisible) QueuePanel(c, s, Modifier.width(278.dp))
                }
            }
        }
    }
    if (waveSettings) WaveSettingsDialog(c, s) { waveSettings = false }
    if (compactQueue) AlertDialog(onDismissRequest = { compactQueue = false }, title = { Text("Очередь") }, text = {
        LazyColumn(Modifier.width(400.dp).heightIn(max = 440.dp)) {
            if (s.queue.isEmpty()) item { Text("Выбери трек, чтобы собрать очередь") }
            itemsIndexed(s.queue, key = { index, song -> "$index:${song.id}" }) { index, song ->
                ListItem(headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(song.artist, maxLines = 1) },
                    leadingContent = { Icon(if (index == s.cursor) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote, null) },
                    modifier = Modifier.clickable { c.select(index); compactQueue = false })
            }
        }
    }, confirmButton = { TextButton({ compactQueue = false }) { Text("Закрыть") } })
    add?.let { song ->
        LaunchedEffect(song.id) { c.lists() }
        AlertDialog(onDismissRequest = { add = null }, title = { Text("В какой плейлист?") }, text = {
            Column { Text(song.title, color = MaterialTheme.colorScheme.onSurfaceVariant); TextButton({ create = true }) { Icon(Icons.Rounded.Add, null); Text("Создать новый") }
                LazyColumn(Modifier.heightIn(max = 380.dp)) { items(s.playlists, key = MusicPlaylist::id) { list ->
                    ListItem(headlineContent = { Text(list.name) }, supportingContent = { Text(if (list.readOnly) "Только просмотр" else "${list.songCount} треков") }, modifier = Modifier.clickable(enabled = !list.readOnly && !s.playlistBusy) { c.add(list, song); add = null })
                } }
            }
        }, confirmButton = {}, dismissButton = { TextButton({ add = null }) { Text("Закрыть") } })
    }
    if (create) { var name by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = { create = false }, title = { Text("Новый плейлист") },
        text = { Column { Text("Его увидит только твой аккаунт"); OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Название") }) } },
        confirmButton = { TextButton({ c.create(name, add); create = false; add = null }, enabled = name.isNotBlank() && !s.playlistBusy) { Text("Создать") } }, dismissButton = { TextButton({ create = false }) { Text("Отмена") } }) }
    remove?.let { (page, index) -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Убрать из плейлиста?") }, text = { Text("«${page.songs[index].title}» останется в библиотеке сервера.") },
        confirmButton = { TextButton({ c.remove(page, index); remove = null }, enabled = !s.playlistBusy) { Text("Убрать") } }, dismissButton = { TextButton({ remove = null }) { Text("Отмена") } }) }
}

@Composable private fun Sidebar(c: DesktopController, s: DesktopState, create: () -> Unit) {
    Column(Modifier.width(220.dp).fillMaxHeight().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { BrandIcon(Modifier.size(34.dp)); Spacer(Modifier.width(10.dp)); Column { Text("DEDSEC", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, letterSpacing = 2.sp); Text("M U S I C", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        listOf("Главная" to Icons.Rounded.Home, "Поиск" to Icons.Rounded.Search, "Музыка" to Icons.Rounded.LibraryMusic).forEach { (title, icon) -> SideItem(if (title == "Музыка") "Библиотека" else title, icon, s.tab == title) { c.tab(title) } }
        Text("МОЯ КОЛЛЕКЦИЯ", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 10.dp))
        listOf("Любимое" to Icons.Rounded.FavoriteBorder, "Скачанное" to Icons.Rounded.DownloadDone, "Добавить" to Icons.Rounded.CloudUpload).forEach { (title, icon) -> SideItem(if (title == "Музыка") "Библиотека" else title, icon, s.tab == title) { c.tab(title) } }
        TextButton(create, Modifier.padding(top = 16.dp)) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Новый плейлист", fontSize = 12.sp) }
        }
        if (s.demo) Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp)) { Text("Демо интерфейса", Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall) }
        HorizontalDivider(Modifier.padding(vertical = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().clip(Rounded).clickable { c.tab("Настройки") }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(s, Modifier.size(36.dp)); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(s.username, maxLines = 1, fontWeight = FontWeight.SemiBold); Text("Мой профиль", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(Icons.Rounded.Settings, "Настройки", Modifier.size(20.dp))
        }
    }
}
@Composable private fun SideItem(title: String, icon: ImageVector, selected: Boolean, click: () -> Unit) {
    Surface(onClick = click, shape = RoundedCornerShape(18.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, title, Modifier.size(21.dp), tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(14.dp)); Text(title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }
    }
}
@Composable private fun Avatar(s: DesktopState, modifier: Modifier) {
    val image = remember(s.avatar) { s.avatar?.let { runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() } }
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { if (image != null) Image(image, "Аватарка", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text(s.username.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold) }
}
@Composable private fun Header(c: DesktopController, s: DesktopState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (s.page != null) FilledTonalIconButton(c::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
        OutlinedTextField(s.query, { if (s.tab != "Поиск") c.tab("Поиск"); if (s.page != null) c.back(); c.search(it) }, placeholder = { Text("Трек, исполнитель или жанр", maxLines = 1) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (s.query.isNotBlank()) IconButton({ c.search("") }) { Icon(Icons.Rounded.Close, "Очистить") } }, singleLine = true, shape = RoundedCornerShape(30.dp), modifier = Modifier.weight(1f).height(52.dp).onPreviewKeyEvent { if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) { c.rememberQuery(); true } else false })
        IconButton(c::refresh, enabled = !s.demo && !s.busy) { Icon(Icons.Rounded.Refresh, "Обновить библиотеку") }
    }
}
@Composable private fun Home(c: DesktopController, s: DesktopState, configureWave: () -> Unit, add: (Song) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 26.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { Column { Text("Поймай свой ритм", style = MaterialTheme.typography.headlineLarge); Text("Музыка, к которой хочется возвращаться", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth().height(210.dp)) {
                Box(Modifier.weight(1.6f).fillMaxHeight().clip(Rounded).background(Brush.linearGradient(listOf(lerp(MaterialTheme.colorScheme.primary, Color.Black, .48f), lerp(MaterialTheme.colorScheme.tertiary, Color.Black, .40f)))).padding(26.dp)) {
                    Canvas(Modifier.matchParentSize()) { for (i in 0..5) drawCircle(Color.White.copy(.055f), 40f + i * 28f, Offset(size.width * .85f, size.height * .43f), style = androidx.compose.ui.graphics.drawscope.Stroke(16f)) }
                    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                        Row { Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White); Spacer(Modifier.width(8.dp)); Text("ПЕРСОНАЛЬНЫЙ МИКС", color = Color.White.copy(.8f), fontSize = 10.sp, letterSpacing = 1.sp) }
                        Column { Text("Моя волна", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold); Text("Твоё настроение. Новые открытия.", color = Color.White.copy(.8f), fontSize = 12.sp) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilledTonalButton({ c.wave() }, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White, contentColor = lerp(MaterialTheme.colorScheme.primary, Color.Black, .50f))) { Icon(Icons.Rounded.PlayArrow, null); Text("Слушать") }; OutlinedButton(configureWave, border = BorderStroke(1.dp, Color.White.copy(.4f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("Настроить", fontSize = 12.sp) } }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallFeature("Любимые треки", "${s.favorites.size} в твоей коллекции", Icons.Rounded.Favorite, Modifier.weight(1f)) { c.tab("Любимое") }
                    SmallFeature("Музыка с собой", "${s.offline.size} скачано", Icons.Rounded.DownloadDone, Modifier.weight(1f)) { c.tab("Скачанное") }
                }
            }
        }
        if (s.albums.isNotEmpty()) {
            item { Section("Недавно в библиотеке", "АЛЬБОМЫ") }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(s.albums.take(12), key = Album::id) { album -> AlbumTile(album, Modifier.width(142.dp)) { c.album(album) } } } }
        }
        item { Section("Включи что-нибудь любимое", "ТВОЯ МУЗЫКА") }
        itemsIndexed(s.songs.take(10), key = { i, song -> "$i:${song.id}" }) { index, song -> TrackRow(song, index, s, { c.play(s.songs, index) }, { c.favorite(song) }, { add(song) }, { c.download(song) }, null) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
@Composable private fun SmallFeature(title: String, text: String, icon: ImageVector, modifier: Modifier, click: () -> Unit) {
    Surface(onClick = click, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp)); Column { Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(text, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
}
@Composable internal fun Section(title: String, eyebrow: String = "") { Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { if (eyebrow.isNotBlank()) Text(eyebrow, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold); Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) } }
@Composable private fun AlbumTile(album: Album, modifier: Modifier, click: () -> Unit) { Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = click)) { Cover(album.coverArt, album.name, Modifier.fillMaxWidth().aspectRatio(1f), 18); Text(album.name, Modifier.padding(top = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Text(album.artist, maxLines = 1, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable private fun Library(c: DesktopController, s: DesktopState, songs: List<Song>, source: String, add: (Song) -> Unit, create: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(if (s.tab == "Музыка") "Библиотека" else s.tab, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f)); if (s.tab == "Музыка") Text("${s.total ?: s.songs.size} треков", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
        Spacer(Modifier.height(16.dp))
        if (s.tab in listOf("Музыка", "Поиск")) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 14.dp)) { items(if (s.tab == "Поиск") listOf("Треки", "Исполнители", "Жанры") else listOf("Треки", "Альбомы", "Плейлисты")) { value -> FilterChip(s.filter == value, { c.filter(value) }, { Text(value, fontSize = 12.sp) }) } }
        when {
            s.filter == "Альбомы" && s.tab == "Музыка" -> LazyVerticalGrid(GridCells.Adaptive(148.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(bottom = 20.dp)) { items(s.albums, key = Album::id) { AlbumTile(it, Modifier.fillMaxWidth()) { c.album(it) } } }
            s.filter == "Плейлисты" && s.tab == "Музыка" -> LazyColumn { item { TextButton(create) { Icon(Icons.Rounded.Add, null); Text("Создать плейлист") } }; items(s.playlists, key = MusicPlaylist::id) { p -> ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text("${p.songCount} треков · только для тебя") }, leadingContent = { Cover(p.coverArt, p.name, Modifier.size(52.dp), 12) }, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { c.playlist(p) }) } }
            s.filter == "Исполнители" && s.tab in listOf("Поиск", "Музыка") -> LazyColumn { items(if (s.query.isBlank()) s.artists else s.search.artists, key = Artist::id) { a -> ListItem(headlineContent = { Text(a.name) }, supportingContent = { Text("${a.albumCount} альбомов") }, leadingContent = { Cover(a.coverArt, a.name, Modifier.size(52.dp), 26) }, modifier = Modifier.clickable { c.artist(a) }) } }
            s.filter == "Жанры" && s.tab in listOf("Поиск", "Музыка") -> LazyColumn { items(s.genres.filter { s.query.isBlank() || SearchText.matches(s.query, it.name) }, key = Genre::name) { g -> ListItem(headlineContent = { Text(g.name) }, supportingContent = { Text("${g.songCount} треков · по тегам файлов") }, leadingContent = { Icon(Icons.Rounded.GraphicEq, null) }, modifier = Modifier.clickable { c.genre(g) }) } }
            else -> LazyColumn {
                if (songs.isEmpty() && !s.busy && !s.searching) item { Empty("Здесь пока тихо", "Добавь музыку, измени запрос или создай свой плейлист") }
                itemsIndexed(songs, key = { index, song -> "$index:${song.id}" }) { index, song -> TrackRow(song, index, s, { c.play(songs, index, source) }, { c.favorite(song) }, { add(song) }, { c.download(song) }, null) }
                if (s.tab == "Музыка" && s.more) item { OutlinedButton(c::more, enabled = !s.busy, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("Ещё треки") } }
            }
        }
    }
}
@Composable private fun Collection(c: DesktopController, s: DesktopState, songs: List<Song>, source: String, add: (Song) -> Unit, remove: (Int) -> Unit) {
    val page = s.page ?: return
    LazyColumn(contentPadding = PaddingValues(horizontal = 26.dp, vertical = 10.dp)) {
        item { Row(Modifier.padding(bottom = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) { Cover(songs.firstOrNull()?.coverArt.orEmpty(), page.title, Modifier.size(138.dp), 28); Column { Text(when (page.kind) { "playlist" -> "ЛИЧНЫЙ ПЛЕЙЛИСТ"; "album" -> "АЛЬБОМ"; "artist" -> "ИСПОЛНИТЕЛЬ"; else -> "КОЛЛЕКЦИЯ" }, color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, letterSpacing = 2.sp); Text(page.title, style = MaterialTheme.typography.headlineMedium); Text("${songs.size} треков", color = MaterialTheme.colorScheme.onSurfaceVariant); Button({ c.play(songs, source = source) }, enabled = songs.isNotEmpty(), modifier = Modifier.padding(top = 12.dp)) { Icon(Icons.Rounded.PlayArrow, null); Text("Слушать") } } } }
        itemsIndexed(songs, key = { i, song -> "$i:${song.id}" }) { i, song -> TrackRow(song, i, s, { c.play(songs, i, source) }, { c.favorite(song) }, { add(song) }, { c.download(song) }, if (page.playlist?.readOnly == false) ({ remove(i) }) else null) }
    }
}
@Composable internal fun TrackRow(song: Song, index: Int, s: DesktopState, play: () -> Unit, favorite: () -> Unit, add: () -> Unit, download: () -> Unit, remove: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    val active = s.now?.id == song.id
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (active) MaterialTheme.colorScheme.primaryContainer.copy(.35f) else Color.Transparent).clickable(onClick = play).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (active) "♫" else (index + 1).toString().padStart(2, '0'), Modifier.width(22.dp), color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        Cover(song.coverArt, song.album.ifBlank { song.title }, Modifier.size(42.dp), 9)
        Column(Modifier.weight(1f)) { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text(song.artist, maxLines = 1, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(formatTime(song.duration * 1000L), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(favorite, Modifier.size(30.dp)) { Icon(if (s.favorites.any { it.id == song.id }) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Нравится", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary) }
        Box { IconButton({ menu = true }, Modifier.size(28.dp)) { Icon(Icons.Rounded.MoreHoriz, "Меню трека", Modifier.size(19.dp)) }; DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("В плейлист") }, { menu = false; add() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) })
            DropdownMenuItem({ Text(if (s.offline.any { it.id == song.id }) "Уже скачано" else "Скачать") }, { menu = false; download() }, enabled = s.offline.none { it.id == song.id }, leadingIcon = { Icon(Icons.Rounded.Download, null) })
            remove?.let { DropdownMenuItem({ Text("Убрать из плейлиста") }, { menu = false; it() }, leadingIcon = { Icon(Icons.Rounded.RemoveCircleOutline, null) }) }
        } }
    }
}

@Composable private fun QueuePanel(c: DesktopController, s: DesktopState, modifier: Modifier) {
    Column(modifier.fillMaxHeight().glassPanel(Rounded, MaterialTheme.colorScheme.surfaceContainerLow).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text("Сейчас играет", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        Spacer(Modifier.height(20.dp)); val song = s.now
        Cover(song?.coverArt.orEmpty(), song?.album ?: "Свой ритм", Modifier.fillMaxWidth().aspectRatio(1f), 25)
        Text(song?.title ?: "Твой следующий трек", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(top = 18.dp))
        Text(song?.artist ?: "Выбирай музыку и нажимай play", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
        HorizontalDivider(Modifier.padding(vertical = 22.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(if (song == null) "СВОЯ МУЗЫКА. БЕЗ ГРАНИЦ." else "ДАЛЬШЕ В ОЧЕРЕДИ", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
        if (song == null) { Spacer(Modifier.height(16.dp)); Text("Один сервер. Личные плейлисты. Любимые треки на всех твоих устройствах.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp) }
        LazyColumn(Modifier.padding(top = 12.dp)) { itemsIndexed(s.queue.drop(s.cursor + 1).take(15), key = { i, song -> "$i:${song.id}" }) { index, next -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { c.select(s.cursor + index + 1) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Cover(next.coverArt, next.album, Modifier.size(35.dp), 8); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(next.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp); Text(next.artist, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }
    }
}
@Composable private fun BottomPlayer(c: DesktopController, s: DesktopState, audio: AudioState, add: (Song) -> Unit, queue: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 28.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Row(Modifier.width(250.dp), verticalAlignment = Alignment.CenterVertically) { Cover(s.now?.coverArt.orEmpty(), s.now?.album ?: "DedSec", Modifier.size(58.dp), 16); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(s.now?.title ?: "Поймай свой ритм", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(s.now?.artist ?: "Выбери трек для воспроизведения", maxLines = 1, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(c::shuffle, enabled = s.now != null) { Icon(Icons.Rounded.Shuffle, "Перемешать", Modifier.size(19.dp), tint = if (s.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton({ c.skip(false) }, enabled = s.now != null) { Icon(Icons.Rounded.SkipPrevious, "Назад", Modifier.size(28.dp)) }
                    FilledIconButton(c::toggle, enabled = s.now != null, modifier = Modifier.size(46.dp), shape = RoundedCornerShape(18.dp)) { if (audio.buffering) CircularProgressIndicator(Modifier.size(23.dp), strokeWidth = 2.dp) else Icon(if (audio.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Воспроизведение", Modifier.size(30.dp)) }
                    IconButton({ c.skip(true) }, enabled = s.now != null) { Icon(Icons.Rounded.SkipNext, "Далее", Modifier.size(28.dp)) }
                    IconButton(c::repeat, enabled = s.now != null) { Icon(Icons.Rounded.Repeat, "Повтор", Modifier.size(19.dp), tint = if (s.repeat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val duration = audio.duration.takeIf { it > 0 } ?: (s.now?.duration ?: 0) * 1000L
                    var dragging by remember(s.now?.id) { mutableStateOf<Float?>(null) }
                    val position = if (s.ended) 0f else dragging ?: audio.position.toFloat()
                    Text(formatTime(position.toLong()), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(position, { dragging = it }, onValueChangeFinished = { dragging?.let { c.audio.seek(it.toLong()) }; dragging = null }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = s.now != null, modifier = Modifier.weight(1f).height(22.dp), track = { Wave(position / duration.coerceAtLeast(1), audio.playing) })
                    Text(formatTime(duration), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.width(220.dp), horizontalAlignment = Alignment.End) {
                Text(s.source, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) { IconButton({ s.now?.let(add) }, enabled = s.now != null) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "В плейлист", Modifier.size(20.dp)) }; IconButton(queue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Очередь", Modifier.size(20.dp)) }; Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, Modifier.size(17.dp)); var volume by remember { mutableFloatStateOf(.8f) }; Slider(volume, { volume = it; c.audio.volume(it) }, Modifier.width(80.dp)) }
            }
        }
    }
}
@Composable private fun Wave(fraction: Float, playing: Boolean) {
    val phase = rememberInfiniteTransition().animateFloat(0f, 6.283f, infiniteRepeatable(tween(7000, easing = LinearEasing)))
    val primary = MaterialTheme.colorScheme.primary; val muted = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(18.dp)) { val split = size.width * fraction.coerceIn(0f, 1f); drawLine(muted, Offset(split, size.height / 2), Offset(size.width, size.height / 2), 2f, StrokeCap.Round); val path = Path(); var x = 0f; while (x < split) { val y = size.height / 2 + if (playing) sin(x / 11f - phase.value) * 3f else 0f; if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y); x += 1f }; drawPath(path, primary, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f, cap = StrokeCap.Round)) }
}
@Composable internal fun Cover(id: String, title: String, modifier: Modifier, radius: Int) {
    val cache = LocalCovers.current
    val sample = LocalSample.current
    var image by remember(cache, id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cache, id) { image = cache?.get(id, 384) }
    val palettes = listOf(listOf(Color(0xFF276B9A), Color(0xFF19263C)), listOf(Color(0xFF996A86), Color(0xFF302342)), listOf(Color(0xFF658B82), Color(0xFF162F38)), listOf(Color(0xFF9A774D), Color(0xFF3A2A39)), listOf(Color(0xFF666EAA), Color(0xFF282D56)))
    val colors = palettes[Math.floorMod(title.hashCode(), palettes.size)]
    Box(modifier.clip(RoundedCornerShape(radius.dp)).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) {
        if (image != null) Image(image!!, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Canvas(Modifier.fillMaxSize()) { val min = size.minDimension; drawCircle(Color.White.copy(.12f), min * .31f, Offset(size.width * .58f, size.height * .42f)); drawCircle(Color.White.copy(.18f), min * .2f, Offset(size.width * .58f, size.height * .42f), style = androidx.compose.ui.graphics.drawscope.Stroke(min * .035f)); drawLine(Color.White.copy(.32f), Offset(min * .19f, min * .77f), Offset(min * .68f, min * .77f), min * .028f, StrokeCap.Round) }
    }
}
@Composable private fun Empty(title: String, detail: String) { Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) { Icon(Icons.Rounded.Headphones, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary); Text(title, style = MaterialTheme.typography.titleLarge); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } }
private fun formatTime(ms: Long) = "${ms.coerceAtLeast(0) / 60000}:${(ms.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}"

@Composable private fun Settings(c: DesktopController, s: DesktopState) {
    val updates by c.updates.collectAsState()
    var admin by remember { mutableStateOf(false) }
    if (admin && s.isAdmin) c.adminApi()?.let { api -> AdminDialog(api) { admin = false } }
    LazyColumn(contentPadding = PaddingValues(26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Section("Твоя территория", "НАСТРОЙКИ") }
        if (s.isAdmin) item { OutlinedButton({ admin = true }) { Icon(Icons.Rounded.ManageAccounts, null); Spacer(Modifier.width(8.dp)); Text("Пользователи и их загрузки") } }
        item { Surface(shape = Rounded, color = MaterialTheme.colorScheme.surfaceContainer) { Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically) { Avatar(s, Modifier.size(68.dp)); Spacer(Modifier.width(20.dp)); Column(Modifier.weight(1f)) { Text(s.username, style = MaterialTheme.typography.titleLarge); Text("Личные плейлисты · общая музыка", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; OutlinedButton({ val chooser = JFileChooser(); if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION && Files.size(chooser.selectedFile.toPath()) <= 5_000_000) c.avatar(Files.readAllBytes(chooser.selectedFile.toPath())) }) { Text("Аватарка") } } } }
        item { Section("Оформление") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(listOf("material" to "Material", "glass" to "Стекло", "aurora" to "Аврора", "minimal" to "Минимал")) { (value, title) ->
                    Surface(onClick = { c.style(value) }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(if (s.style == value) 2.dp else 1.dp, if (s.style == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.width(128.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(when (value) { "glass" -> Icons.Rounded.BlurOn; "aurora" -> Icons.Rounded.Gradient; "minimal" -> Icons.Rounded.DragHandle; else -> Icons.Rounded.Widgets }, null, tint = MaterialTheme.colorScheme.primary)
                            Text(title, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(when (value) { "glass" -> "Свет и глубина"; "aurora" -> "Цветное сияние"; "minimal" -> "Спокойные формы"; else -> "Мягкая геометрия" }, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(s.dark && !s.amoled, { c.amoled(false); c.theme(true, s.palette) }, { Text("Тёмная") }); FilterChip(!s.dark, { c.theme(false, s.palette) }, { Text("Светлая") }); FilterChip(s.amoled, { c.amoled(true) }, { Text("AMOLED") }, leadingIcon = { Icon(Icons.Rounded.DarkMode, null, Modifier.size(16.dp)) }) } }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(listOf("blue" to "Синий", "violet" to "Сирень", "mint" to "Мята", "rose" to "Роза", "amber" to "Янтарь", "lagoon" to "Лагуна")) { (value, label) -> FilterChip(s.palette == value, { c.theme(s.dark, value) }, { Text(label) }) } } }
        item { Section("Обновления") }
        item {
            Text(updates.message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ c.checkUpdates() }, enabled = !updates.checking && !updates.installing) { Text(if (updates.checking) "Проверяем…" else "Проверить обновления") }
                updates.offer?.let { offer ->
                    var confirm by remember { mutableStateOf(false) }
                    Button({ confirm = true }, enabled = !updates.installing) { Text("Обновить до ${offer.version}") }
                    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Установить обновление?") }, text = { Text("После скачивания и проверки приложение перезапустится. Предыдущая версия сохранится рядом с установленной.") }, confirmButton = { TextButton({ confirm = false; c.installUpdate(offer) }) { Text("Обновить") } }, dismissButton = { TextButton({ confirm = false }) { Text("Отмена") } })
                }
            }
        }
        item { Text("DedSec Music Desktop · ${DesktopBuild.VERSION}\nGPL-3.0-or-later", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 20.sp); OutlinedButton(c::logout, Modifier.padding(top = 20.dp)) { Icon(Icons.AutoMirrored.Rounded.Logout, null); Spacer(Modifier.width(8.dp)); Text("Выйти из аккаунта") } }
    }
}
@Composable private fun Upload(c: DesktopController, s: DesktopState) {
    LaunchedEffect(s.username) { c.refreshImport() }
    var server by remember { mutableStateOf(DEFAULT_UPLOAD_SERVER) }; var username by remember { mutableStateOf(s.username) }; var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        YandexImportCard(s.importStatus, !s.demo, c::startImport, c::cancelImport, c::refreshImport)
        Section("Добавь свой звук", "ЗАГРУЗКА МУЗЫКИ")
        Text("Аудиофайлы попадут в общую библиотеку сервера. До 100 МБ и 20 минут на файл.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(server, { server = it }, label = { Text("Сервер загрузки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { OutlinedTextField(username, { username = it }, label = { Text("Логин загрузки") }, singleLine = true, modifier = Modifier.weight(1f)); OutlinedTextField(password, { password = it }, label = { Text("Пароль загрузки") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f)) }
        Button({ val chooser = JFileChooser().apply { isMultiSelectionEnabled = true }; if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) { c.upload(server, username, password, chooser.selectedFiles.map { it.toPath() }); password = "" } }, enabled = password.isNotEmpty() && s.transfer == null, modifier = Modifier.height(52.dp)) { Icon(Icons.Rounded.CloudUpload, null); Spacer(Modifier.width(10.dp)); Text("Выбрать аудиофайлы") }
        s.transfer?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it, fontSize = 12.sp) }
        Text("Пароль загрузки вводится отдельно. Музыкальный пароль сюда автоматически не передаётся.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
