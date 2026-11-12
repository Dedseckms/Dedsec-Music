package ru.dedseckms.music.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import ru.dedseckms.music.data.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*

data class DesktopPage(val kind: String, val title: String, val songs: List<Song>, val playlist: MusicPlaylist? = null, val albums: List<Album> = emptyList())
data class DesktopState(
    val importStatus: ImportStatus = ImportStatus(),
    val username: String = "", val loggedIn: Boolean = false, val demo: Boolean = false,
    val busy: Boolean = false, val message: String? = null, val tab: String = "Главная", val filter: String = "Треки",
    val query: String = "", val songs: List<Song> = emptyList(), val albums: List<Album> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(), val artists: List<Artist> = emptyList(), val genres: List<Genre> = emptyList(),
    val favorites: List<Song> = emptyList(), val offline: List<Song> = emptyList(), val page: DesktopPage? = null,
    val search: SearchResults = SearchResults(emptyList(), emptyList()), val searching: Boolean = false,
    val now: Song? = null, val queue: List<Song> = emptyList(), val cursor: Int = 0, val shuffle: Boolean = false,
    val repeat: Boolean = false, val ended: Boolean = false, val source: String = "Сейчас играет", val wave: Boolean = false,
    val mood: MixMood = MixMood.ANY, val mode: MixMode = MixMode.BALANCED, val dark: Boolean = true,
    val palette: String = "blue", val style: String = "material", val amoled: Boolean = false,
    val recentSearches: List<String> = emptyList(), val catalogLoading: Boolean = false, val total: Int? = null, val avatar: ByteArray? = null,
    val playlistBusy: Boolean = false, val transfer: String? = null, val update: String = "Обновления ещё не опубликованы", val updateOffer: UpdateOffer? = null, val more: Boolean = true,
)

class DesktopController(val scope: CoroutineScope, private val preferences: DesktopPreferences = DesktopPreferences()) : AutoCloseable {
    private var appearance = preferences.read()
    private val preferenceLock = kotlinx.coroutines.sync.Mutex()
    private val searchHistoryLock = kotlinx.coroutines.sync.Mutex()
    private fun DesktopState.styled() = copy(dark = appearance.dark, palette = appearance.palette, style = appearance.style, amoled = appearance.amoled)
    var exitForUpdate: (() -> Unit)? = null
    private val mutable = MutableStateFlow(DesktopState().styled())
    val state = mutable.asStateFlow()
    val queue = QueueState()
    val audio = DesktopAudio(scope, ::onEnded, advanced = ::onCrossfaded)
    private var queueEpoch = 0L
    private var prepareJob: Job? = null
    private var api: NavApi? = null
    private var profile: ProfileApi? = null
    private var importSession: YandexImportSession? = null
    var store: DesktopStore? = null; private set
    var covers: DesktopCovers? = null; private set
    private var generation = 0
    private val accountTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    private val authLock = kotlinx.coroutines.sync.Mutex()
    private var mixJob: Job? = null
    private var mixRevision = 0L
    private fun invalidateMix() { mixRevision++; mixJob?.cancel(); mixJob = null }
    private fun cancelSession() { queueEpoch++; prepareJob?.cancel(); importSession?.close(); importSession = null; accountTasks.forEach(Job::cancel); accountTasks.clear(); pageJob?.cancel(); searchJob?.cancel(); playJob?.cancel(); invalidateMix(); audio.stop() }
    private fun track(job: Job): Job { accountTasks += job; job.invokeOnCompletion { accountTasks.remove(job) }; return job }
    private var pageJob: Job? = null
    private var searchJob: Job? = null
    private var playJob: Job? = null
    private val transferLock = Semaphore(1)
    private val playlistLock = kotlinx.coroutines.sync.Mutex()
    private fun playlistAction(block: suspend (NavApi) -> Unit) = action { nav ->
        if (state.value.importStatus.job?.syncingPlaylists == true) { notice("Дождись завершения переноса плейлистов"); return@action }
        val revision = generation
        playlistLock.withLock {
            currentCoroutineContext().ensureActive(); if (revision != generation) return@withLock
            mutable.update { it.copy(playlistBusy = true) }
            try { block(nav) } finally { if (revision == generation) mutable.update { it.copy(playlistBusy = false) } }
        }
    }
    private var recorded = ""
    private var updateCheck: Job? = null
    private val destinations = mutableMapOf<String, Pair<String, DesktopPage?>>()
    init {
        scope.launch { audio.state.collect { sound ->
            val song = state.value.now
            if (song != null && sound.playing && sound.position >= 30_000 && recorded != song.id) {
                recorded = song.id
                val nav = api; val local = store
                launch(Dispatchers.IO) { local?.record(song); runCatching { nav?.scrobble(song.id) } }
            }
        } }
    }
    fun notice(text: String?) { mutable.update { it.copy(message = text) } }
    private fun failure(error: Exception) = if (error is java.io.IOException && !error.message.orEmpty().contains("http", true)) error.message ?: "Проверь соединение" else "Не удалось выполнить операцию. Проверь подключение"
    private fun action(busy: Boolean = true, block: suspend (NavApi) -> Unit) {
        val nav = api ?: return
        val revision = generation
        if (busy) mutable.update { it.copy(busy = true) }
        track(scope.launch {
            try { currentCoroutineContext().ensureActive(); if (revision == generation) block(nav) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == generation) notice(failure(e)) }
            finally { if (busy && revision == generation) mutable.update { it.copy(busy = false) } }
        })
    }
    fun login(server: String, username: String, password: String, remember: Boolean) {
        val revision = ++generation
        cancelSession(); api = null; profile = null; store = null; covers = null; destinations.clear()
        mutable.value = DesktopState(busy = true).styled()
        track(scope.launch {
            try {
                val candidate = Credentials(ServerAddress.normalize(server), username.trim(), password)
                val nav = NavApi(candidate)
                val canonical = nav.await { ping(); canonicalUsername() }
                val credentials = Credentials(candidate.server, canonical, password)
                if (revision != generation) return@launch
                withContext(Dispatchers.IO) { authLock.withLock { currentCoroutineContext().ensureActive(); if (revision == generation) DesktopStore.remember(if (remember) credentials else null) } }
                currentCoroutineContext().ensureActive(); if (revision != generation) return@launch
                val local = withContext(Dispatchers.IO) { DesktopStore(credentials) }
                val offline = withContext(Dispatchers.IO) { local.offline() }
                val recent = withContext(Dispatchers.IO) { local.searchHistory() }
                currentCoroutineContext().ensureActive(); if (revision != generation) return@launch
                api = NavApi(credentials); profile = ProfileApi(credentials); store = DesktopStore(credentials); covers = DesktopCovers(api!!, store!!.directory)
                mutable.value = DesktopState(username = canonical, loggedIn = true, busy = true, offline = offline, recentSearches = recent).styled()
                refresh()
                refreshImport()
                checkUpdates(silent = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == generation) mutable.update { it.copy(busy = false, message = failure(e)) } }
        })
    }
    fun restoreLogin() {
        val revision = generation
        track(scope.launch {
            val saved = withContext(Dispatchers.IO) { authLock.withLock { DesktopStore.remembered() } }
            if (revision != generation || saved == null) return@launch
            val local = withContext(Dispatchers.IO) { DesktopStore(saved) }
            val offline = withContext(Dispatchers.IO) { local.offline() }
            val recent = withContext(Dispatchers.IO) { local.searchHistory() }
            currentCoroutineContext().ensureActive(); if (revision != generation) return@launch
            if (offline.isEmpty()) { login(saved.server, saved.username, saved.password, true); return@launch }
            api = NavApi(saved); profile = ProfileApi(saved); store = local; covers = DesktopCovers(api!!, local.directory)
            mutable.value = DesktopState(username = saved.username, loggedIn = true, songs = offline, offline = offline, recentSearches = recent).styled()
            refresh(); refreshImport(); checkUpdates(silent = true)
        })
    }
    fun demo() {
        generation++; cancelSession()
        api = null; profile = null; store = null; covers = null
        val sample = DesktopDemo.songs
        mutable.value = DesktopState(username = "Слушатель", loggedIn = true, demo = true, songs = sample, albums = DesktopDemo.albums,
            now = sample.first(), queue = sample.take(12), source = "Моя волна · Чилл", favorites = sample.take(3), playlists = listOf(MusicPlaylist("night", "Ночной маршрут", "Слушатель", 18, "", false)), total = 1248,
            artists = sample.map { Artist(it.artistId, it.artist) }.distinctBy(Artist::id), genres = listOf(Genre("Electronic", 42), Genre("Indie", 31), Genre("Ambient", 17)), recentSearches = listOf("Тихий сигнал", "Невесомость")).styled()
    }
    fun logout() {
        generation++; cancelSession(); api = null; profile = null; covers = null; store = null; destinations.clear()
        val revision = generation
        scope.launch(Dispatchers.IO) { authLock.withLock { if (revision == generation) DesktopStore.remember(null) } }
        mutable.value = DesktopState().styled()
    }
    fun refreshImport() {
        if (state.value.demo) return
        val service = profile ?: return
        if (importSession == null) {
            val session = YandexImportSession(scope, service); importSession = session
            val revision = generation
            track(scope.launch { session.state.collect { status -> if (revision == generation) mutable.update { it.copy(importStatus = status) } } })
        }
        importSession?.refresh()
    }
    fun startImport(token: String) { refreshImport(); importSession?.submit(token) }
    fun cancelImport() { importSession?.cancel() }
    fun refresh() = action { nav ->
        val revision = generation
        val values = coroutineScope {
            val songs = async { nav.await { songs() } }; val albums = async { nav.await { albums() } }
            val lists = async { nav.await { playlists() } }; val favorite = async { nav.await { favorites() } }
            listOf(songs.await(), albums.await(), lists.await(), favorite.await())
        }
        @Suppress("UNCHECKED_CAST") val songs = values[0] as List<Song>
        @Suppress("UNCHECKED_CAST") val albums = values[1] as List<Album>
        @Suppress("UNCHECKED_CAST") val lists = values[2] as List<MusicPlaylist>
        @Suppress("UNCHECKED_CAST") val favorite = values[3] as List<Song>
        if (revision == generation) mutable.update { it.copy(songs = songs, albums = albums, playlists = lists, favorites = favorite, more = songs.size >= 100) }
        runCatching { profile?.await { me() } }.getOrNull()?.let { info ->
            val picture = if (info.avatarVersion.isNotBlank()) runCatching { profile?.await { avatar() } }.getOrNull() else null
            if (revision == generation) mutable.update { it.copy(total = info.librarySongCount, avatar = picture) }
        }
    }
    fun tab(value: String) {
        val s = state.value
        if (s.tab == value) return
        if (s.tab == "Поиск") rememberQuery()
        destinations[s.tab] = s.filter to s.page
        pageJob?.cancel()
        mutable.update { it.copy(tab = value, filter = destinations[value]?.first ?: if (value == "Поиск") "Все" else "Треки", page = destinations[value]?.second, busy = false) }
        if (value == "Поиск" && state.value.artists.isEmpty()) loadCatalog()
    }
    fun filter(value: String) { mutable.update { it.copy(filter = value) }; if (value in listOf("Исполнители", "Жанры") && state.value.artists.isEmpty()) loadCatalog() }
    fun theme(dark: Boolean, palette: String) = setAppearance(appearance.copy(dark = dark, palette = palette, amoled = dark && appearance.amoled))
    fun style(value: String) = setAppearance(appearance.copy(style = value))
    fun amoled(value: Boolean) = setAppearance(appearance.copy(dark = if (value) true else appearance.dark, amoled = value))
    private fun setAppearance(value: Appearance) {
        appearance = value.validated(); mutable.update { it.styled() }
        if (!state.value.demo) { val snapshot = appearance; scope.launch { preferenceLock.withLock { withContext(Dispatchers.IO) { preferences.write(snapshot) } } } }
    }
    fun rememberQuery() {
        val query = state.value.query.trim().take(120)
        if (query.length < 2) return
        val recent = (listOf(query) + state.value.recentSearches.filterNot { it.equals(query, true) }).take(12)
        mutable.update { it.copy(recentSearches = recent) }
        val local = store ?: return
        track(scope.launch { searchHistoryLock.withLock { withContext(Dispatchers.IO) { local.saveSearchHistory(recent) } } })
    }
    fun clearSearchHistory() {
        mutable.update { it.copy(recentSearches = emptyList()) }; val local = store ?: return
        track(scope.launch { searchHistoryLock.withLock { withContext(Dispatchers.IO) { local.saveSearchHistory(emptyList()) } } })
    }
    fun back() { pageJob?.cancel(); mutable.update { it.copy(page = null, busy = false) } }
    fun search(text: String) {
        searchJob?.cancel()
        mutable.update { it.copy(query = text, searching = text.isNotBlank()) }
        if (text.isBlank()) { mutable.update { it.copy(search = SearchResults(emptyList(), emptyList()), searching = false) }; return }
        if (state.value.demo) { mutable.update { it.copy(search = SearchResults(DesktopDemo.songs.filter { song -> SearchText.matches(text, song.title + song.artist) }, it.artists.filter { a -> SearchText.matches(text, a.name) }), searching = false) }; return }
        val nav = api ?: return; val revision = generation
        searchJob = scope.launch {
            delay(280)
            try { val result = nav.await { search(text) }; if (revision == generation) mutable.update { it.copy(search = result, searching = false) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == generation) { notice(failure(e)); mutable.update { it.copy(searching = false) } } }
        }
    }
    fun loadCatalog() {
        if (api == null || state.value.catalogLoading) return
        mutable.update { it.copy(catalogLoading = true) }
        action(false) { nav ->
            val revision = generation
            try {
                val artists = nav.await { artists() }; if (revision == generation) mutable.update { it.copy(artists = artists) }
                val genres = nav.await { genres() }; if (revision == generation) mutable.update { it.copy(genres = genres) }
            } finally { if (revision == generation) mutable.update { it.copy(catalogLoading = false) } }
        }
    }
    fun more() = action { nav -> val revision = generation; val offset = state.value.songs.size; val more = nav.await { songs(offset = offset) }; if (revision == generation) mutable.update { it.copy(songs = (it.songs + more).distinctBy(Song::id), more = more.size >= 100) } }
    private fun open(kind: String, title: String, load: suspend (NavApi) -> DesktopPage) {
        if (state.value.demo) { mutable.update { it.copy(page = DesktopPage(kind, title, DesktopDemo.songs)) }; return }
        val nav = api ?: return; val revision = generation
        pageJob?.cancel(); mutable.update { it.copy(busy = true, page = DesktopPage(kind, title, emptyList())) }
        pageJob = scope.launch {
            try { val result = load(nav); if (revision == generation) mutable.update { it.copy(page = result) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == generation) notice(failure(e)) }
            finally { if (revision == generation) mutable.update { it.copy(busy = false) } }
        }
    }
    fun album(album: Album) = open("album", album.name) { nav -> DesktopPage("album", album.name, nav.await { albumSongs(album.id) }) }
    fun playlist(list: MusicPlaylist) = open("playlist", list.name) { nav -> nav.await { playlist(list.id) }.let { DesktopPage("playlist", it.playlist.name, it.songs, it.playlist) } }
    fun artist(artist: Artist) = open("artist", artist.name) { nav ->
        val profile = nav.await { artist(artist.id) }
        val songs = mutableListOf<Song>()
        for (album in profile.albums) songs += nav.await { albumSongs(album.id) }
        DesktopPage("artist", artist.name, songs.distinctBy(Song::id), albums = profile.albums)
    }
    fun genre(genre: Genre) = open("genre", genre.name) { nav -> DesktopPage("genre", genre.name, nav.await { genreSongs(genre.name) }) }
    fun play(songs: List<Song>, index: Int = 0, source: String = "Вся музыка", wave: Boolean = false) {
        invalidateMix()
        if (state.value.demo) { notice("Это демонстрация интерфейса. Войди на сервер, чтобы слушать музыку"); return }
        if (songs.isEmpty()) return
        queueEpoch++; prepareJob?.cancel(); audio.clearNext()
        queue.set(songs, index); mutable.update { it.copy(wave = wave, source = source, shuffle = false) }; playCurrent()
    }
    private fun playCurrent() {
        val song = queue.current ?: return; val nav = api ?: return; val local = store ?: return
        playJob?.cancel(); prepareJob?.cancel(); audio.stop(preservePrepared = true); recorded = ""
        mutable.update { it.copy(now = song, queue = queue.order.map(queue.songs::get), cursor = queue.cursor, ended = false) }
        playJob = scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { local.audio(song).takeIf(Files::isRegularFile)?.toUri()?.toString() ?: nav.stream(song) }
                audio.start(uri, "$queueEpoch:${queue.order[queue.cursor]}")
                prepareNext()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice(failure(e)) }
        }
    }
    private fun prepareNext() {
        prepareJob?.cancel()
        val position = if (queue.cursor + 1 < queue.order.size) queue.cursor + 1 else if (state.value.repeat && queue.order.size > 1) 0 else -1
        val index = queue.order.getOrNull(position)
        if (index == null || index == queue.order.getOrNull(queue.cursor)) { audio.clearNext(); return }
        val song = queue.songs[index]; val nav = api ?: return; val local = store ?: return
        val epoch = queueEpoch; val revision = generation; val cache = covers
        prepareJob = track(scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { local.audio(song).takeIf(Files::isRegularFile)?.toUri()?.toString() ?: nav.stream(song) }
                currentCoroutineContext().ensureActive()
                if (epoch != queueEpoch || revision != generation) return@launch
                audio.prepareNext("$epoch:$index", uri)
                cache?.get(song.coverArt, 384)
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { /* Normal track completion remains available when preloading fails. */ }
        })
    }
    private fun onCrossfaded(key: String) {
        val parts = key.split(':')
        if (parts.size != 2 || parts[0].toLongOrNull() != queueEpoch) return
        val index = parts[1].toIntOrNull() ?: return
        val position = queue.order.indexOf(index)
        if (!queue.select(position)) return
        val song = queue.current ?: return; recorded = ""
        mutable.update { it.copy(now = song, queue = queue.order.map(queue.songs::get), cursor = queue.cursor, ended = false) }
        prepareNext()
    }
    private fun onEnded() {
        if (queue.move(true, state.value.repeat)) playCurrent()
        else if (state.value.wave) wave(keep = true)
        else mutable.update { it.copy(ended = true) }
    }
    fun toggle() { if (state.value.ended || audio.state.value.error) playCurrent() else audio.toggle() }
    fun skip(forward: Boolean) { invalidateMix(); if (queue.move(forward, state.value.repeat)) playCurrent() }
    fun select(index: Int) { invalidateMix(); if (queue.select(index)) playCurrent() }
    fun shuffle() { prepareJob?.cancel(); audio.clearNext(); queue.shuffle(); mutable.update { it.copy(shuffle = queue.shuffled, queue = queue.order.map(queue.songs::get), cursor = queue.cursor) }; prepareNext() }
    fun repeat() { mutable.update { it.copy(repeat = !it.repeat) }; prepareNext() }
    fun wave(mood: MixMood = state.value.mood, mode: MixMode = state.value.mode, keep: Boolean = false) {
        val nav = api ?: return; val local = store ?: return; val revision = generation
        invalidateMix(); val mixToken = mixRevision
        val favorites = state.value.favorites.toList()
        mutable.update { it.copy(busy = true) }
        mixJob = scope.launch {
            try {
                val candidates = nav.await { random(300) }
                val songs = withContext(Dispatchers.IO) { MixEngine.build(candidates + favorites, favorites.map(Song::id).toSet(), local.history(), emptySet(), mode, mood = mood) }
                currentCoroutineContext().ensureActive()
                if (revision == generation && mixToken == mixRevision) {
                    mixJob = null
                    mutable.update { it.copy(mood = mood, mode = mode) }
                    play(songs, source = playbackSourceLabel("", true, mood), wave = true)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == generation && mixToken == mixRevision) notice(failure(e)) }
            finally { if (revision == generation && (mixToken == mixRevision || mixJob == null)) mutable.update { it.copy(busy = false) } }
        }
    }
    fun favorite(song: Song) = action(false) { nav ->
        val revision = generation; val enabled = state.value.favorites.none { it.id == song.id }; nav.await { setFavorite(song.id, enabled) }
        if (revision == generation) mutable.update { it.copy(favorites = if (enabled) (it.favorites + song).distinctBy(Song::id) else it.favorites.filterNot { s -> s.id == song.id }) }
    }
    fun lists() = action(false) { nav -> val revision = generation; val lists = nav.await { playlists() }; if (revision == generation) mutable.update { it.copy(playlists = lists) } }
    private fun apply(contents: PlaylistContents) {
        fun replace(page: DesktopPage?) = if (page?.playlist?.id == contents.playlist.id) page.copy(songs = contents.songs, playlist = contents.playlist) else page
        if (state.value.page?.playlist?.id == contents.playlist.id) pageJob?.cancel()
        destinations.replaceAll { _, value -> value.first to replace(value.second) }
        mutable.update { it.copy(page = replace(it.page), playlists = it.playlists.map { p -> if (p.id == contents.playlist.id) contents.playlist else p }) }
    }
    fun create(name: String, song: Song?) = playlistAction { nav -> val revision = generation; val list = nav.await { createPlaylist(name, listOfNotNull(song?.id)) }; if (revision == generation) { mutable.update { it.copy(playlists = it.playlists + list) }; notice("Приватный плейлист создан") } }
    fun add(list: MusicPlaylist, song: Song) = playlistAction { nav ->
        val revision = generation; nav.await { appendToPlaylist(list, song) }; if (revision != generation) return@playlistAction
        notice("Добавлено в «${list.name}»")
        try { val content = nav.await { playlist(list.id) }; if (revision == generation) apply(content) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (revision == generation) notice("Добавлено. Открой плейлист заново для обновления списка") }
        lists()
    }
    fun remove(page: DesktopPage, index: Int) = playlistAction { nav ->
        val list = page.playlist ?: return@playlistAction; val revision = generation
        try { val content = nav.await { removeFromPlaylist(list.id, index, page.songs.map(Song::id)) }; if (revision == generation) { apply(content); notice("Убрано из плейлиста") } }
        catch (e: PlaylistChangedException) { if (revision == generation) apply(e.latest); throw e }
    }
    fun download(song: Song) {
        val local = store ?: return; val revision = generation
        action(false) { nav -> transferLock.withPermit {
        currentCoroutineContext().ensureActive(); if (revision != generation) return@withPermit
        mutable.update { it.copy(transfer = "Скачиваем: ${song.title}") }
        try {
            transfer(nav.client) { client ->
                val output = local.audio(song); Files.createDirectories(output.parent)
                if (Files.getFileStore(output.parent).usableSpace < 512L * 1024 * 1024) error("Недостаточно места на диске")
                val temp = Files.createTempFile(output.parent, "download-", ".part")
                try {
                    client.newCall(nav.download(song)).execute().use { response ->
                        if (!response.isSuccessful) throw java.io.IOException("Не удалось скачать трек")
                        val body = response.body ?: throw java.io.IOException("Пустой аудиофайл")
                        var count = 0L
                        body.byteStream().use { input -> Files.newOutputStream(temp).use { stream ->
                            val bytes = ByteArray(65536)
                            while (true) { currentCoroutineContext().ensureActive(); val n = input.read(bytes); if (n < 0) break; count += n; if (count > 2L * 1024 * 1024 * 1024) throw java.io.IOException("Файл слишком большой"); if (count % (4 * 1024 * 1024) < 65536 && Files.getFileStore(output.parent).usableSpace < 256L * 1024 * 1024) throw java.io.IOException("Недостаточно места на диске"); stream.write(bytes, 0, n) }
                        } }
                        if (count <= 0 || body.contentLength() >= 0 && count != body.contentLength()) throw java.io.IOException("Загрузка прервалась")
                        currentCoroutineContext().ensureActive(); Files.move(temp, output, ATOMIC_MOVE, REPLACE_EXISTING); local.downloaded(song, count)
                    }
                } finally { Files.deleteIfExists(temp) }
            }
            if (revision == generation) { mutable.update { it.copy(offline = local.offline()) }; notice("Трек скачан") }
        } finally { if (revision == generation) mutable.update { it.copy(transfer = null) } }
    } } }
    fun removeOffline(song: Song) {
        val local = store ?: return; val revision = generation
        action(false) { withContext(Dispatchers.IO) { local.removeOffline(song) }; if (revision == generation) mutable.update { it.copy(offline = local.offline()) } }
    }
    fun upload(server: String, username: String, password: String, files: List<Path>) {
        val revision = generation
        action(false) { _ -> transferLock.withPermit {
        currentCoroutineContext().ensureActive(); if (revision != generation) return@withPermit
        try {
            transfer(NavApi.httpClient()) { client ->
                val uploader = UploadApi(Credentials(ServerAddress.normalize(server), username, password), client); uploader.login()
                for (file in files) {
                    currentCoroutineContext().ensureActive(); val size = Files.size(file); UploadPolicy.checkBytes(size)
                    uploader.upload(file.fileName.toString(), size, { Files.newInputStream(file) }) { sent, total -> if (revision == generation) mutable.update { it.copy(transfer = "${file.fileName} · ${if (total > 0) sent * 100 / total else 0}%") } }
                }
            }
            if (revision == generation) notice("Файлы на сервере. Они появятся после сканирования библиотеки")
        } finally { if (revision == generation) mutable.update { it.copy(transfer = null) } }
    } } }
    fun avatar(bytes: ByteArray) = action(false) { _ ->
        val revision = generation; val p = profile ?: return@action
        withContext(Dispatchers.IO) {
            val image = decodePhoto(bytes)
            val side = minOf(image.width, image.height); val output = java.awt.image.BufferedImage(512, 512, java.awt.image.BufferedImage.TYPE_INT_RGB)
            output.createGraphics().apply { drawImage(image, 0, 0, 512, 512, (image.width - side) / 2, (image.height - side) / 2, (image.width + side) / 2, (image.height + side) / 2, null); dispose() }
            val data = java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(output, "jpg", data)
            p.await { upload(data.toByteArray()) }
            if (revision == generation) mutable.update { it.copy(avatar = data.toByteArray()) }
        }
    }
    fun checkUpdates(silent: Boolean = false) {
        if (updateCheck?.isActive == true) return
        updateCheck = scope.launch {
            try {
                val offer = withContext(Dispatchers.IO) { UpdateClient().check() }
                mutable.update { it.copy(updateOffer = offer, update = offer?.let { update -> "Доступна версия ${update.version}" } ?: "Доступных обновлений нет · Windows ${DesktopBuild.VERSION}") }
            } catch (_: Exception) { if (!silent) mutable.update { it.copy(update = "Не удалось проверить обновления. Попробуй позже") } }
        }
    }
    fun installUpdate(offer: UpdateOffer) {
        scope.launch {
            mutable.update { it.copy(update = "Скачиваем и проверяем обновление…") }
            try {
                val archive = withContext(Dispatchers.IO) { UpdateClient().download(offer) }
                val resource = System.getProperty("compose.application.resources.dir")?.let(Path::of)
                val root = resource?.parent?.parent
                if (root == null || !Files.isRegularFile(root.resolve(".dedsec-install")) || exitForUpdate == null) {
                    java.awt.Desktop.getDesktop().open(archive.parent.toFile())
                    mutable.update { it.copy(update = "Проверенное обновление скачано. Распакуй его в отдельную папку") }
                    return@launch
                }
                val script = archive.parent.resolve("ApplyUpdate.ps1")
                val ready = archive.parent.resolve("ready-${java.util.UUID.randomUUID()}")
                withContext(Dispatchers.IO) {
                    DesktopController::class.java.getResourceAsStream("/ApplyUpdate.ps1")!!.use { Files.copy(it, script, REPLACE_EXISTING) }
                    val process = ProcessBuilder("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", script.toString(),
                        "-ProcessId", ProcessHandle.current().pid().toString(), "-InstallRoot", root.toString(), "-Archive", archive.toString(), "-ReadyFile", ready.toString())
                        .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                    withTimeout(60_000) { while (!Files.exists(ready)) { check(process.isAlive) { "Не удалось подготовить установку" }; delay(100) } }
                }
                exitForUpdate?.invoke()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutable.update { it.copy(update = "Обновление не установлено. Текущая версия сохранена") } }
        }
    }
    override fun close() { scope.cancel(); audio.close() }
}
