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
    val childMode: Boolean = false,
    val autoOffline: OfflinePreferences = OfflinePreferences(), val downloadIds: Set<String> = emptySet(), val downloadProgress: Int = 0, val downloadsPaused: Boolean = false,
    val importStatus: ImportStatus = ImportStatus(), val isAdmin: Boolean = false,
    val username: String = "", val loggedIn: Boolean = false, val demo: Boolean = false,
    val busy: Boolean = false, val message: String? = null, val tab: String = "Главная", val filter: String = "Треки",
    val query: String = "", val songs: List<Song> = emptyList(), val albums: List<Album> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(), val artists: List<Artist> = emptyList(), val genres: List<Genre> = emptyList(),
    val favorites: List<Song> = emptyList(), val offline: List<Song> = emptyList(), val page: DesktopPage? = null,
    val search: SearchResults = SearchResults(emptyList(), emptyList()), val searching: Boolean = false,
    val now: Song? = null, val queue: List<Song> = emptyList(), val cursor: Int = 0, val shuffle: Boolean = false,
    val repeat: Boolean = false, val repeatOne: Boolean = false, val unifiedUpload: Boolean = false, val ended: Boolean = false, val source: String = "Сейчас играет", val wave: Boolean = false,
    val mood: MixMood = MixMood.ANY, val mode: MixMode = MixMode.BALANCED, val dark: Boolean = true,
    val palette: String = "blue", val style: String = "material", val amoled: Boolean = false,
    val recentSearches: List<String> = emptyList(), val catalogLoading: Boolean = false, val total: Int? = null, val avatar: ByteArray? = null,
    val playlistBusy: Boolean = false, val transfer: String? = null, val more: Boolean = true,
)

internal fun DesktopState.beginQueue(wave: Boolean, source: String) = copy(wave = wave, source = source, shuffle = false,
    repeat = !wave && repeat, repeatOne = this.wave == wave && repeatOne)

class DesktopController(val scope: CoroutineScope, private val preferences: DesktopPreferences = DesktopPreferences()) : AutoCloseable {
    private var appearance = preferences.read()
    private val preferenceLock = kotlinx.coroutines.sync.Mutex()
    private val searchHistoryLock = kotlinx.coroutines.sync.Mutex()
    private fun DesktopState.styled() = copy(childMode = store?.childMode() ?: childMode, autoOffline = store?.offlinePreferences() ?: OfflinePreferences(), dark = appearance.dark, palette = appearance.palette, style = appearance.style, amoled = appearance.amoled)
    var exitForUpdate: (() -> Unit)? = null
    private val mutable = MutableStateFlow(DesktopState().styled())
    val state = mutable.asStateFlow()
    val queue = QueueState()
    val audio = DesktopAudio(scope, ::onEnded, advanced = ::onCrossfaded)
    private var queueEpoch = 0L
    private var prepareJob: Job? = null
    private var api: NavApi? = null
    private var profile: ProfileApi? = null
    fun adminApi(): ProfileApi? = if (state.value.isAdmin && !state.value.demo) profile else null
    fun lyricsApi(): ProfileApi? = if (state.value.demo) null else profile
    fun lyricsCache(song: Song): java.io.File? = store?.directory?.resolve("lyrics/${sha256(song.id)}.json")?.toFile()
    fun refreshUploadConnection() {
        val service = profile ?: return; val revision = generation
        track(scope.launch {
            val ready = try { service.await { capabilities() } } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (revision == generation) mutable.update { it.copy(unifiedUpload = ready) }
        })
    }
    private var importSession: YandexImportSession? = null
    var store: DesktopStore? = null; private set
    var covers: DesktopCovers? = null; private set
    private var generation = 0
    private val accountTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    private val authLock = kotlinx.coroutines.sync.Mutex()
    private var mixJob: Job? = null
    private var mixRevision = 0L
    private fun invalidateMix() { mixRevision++; mixJob?.cancel(); mixJob = null }
    private fun cancelSession() { queueSaveJob?.cancel(); queueSaveJob = null; downloadJob?.cancel(); downloadJob = null; downloadQueue.clear(); queueEpoch++; prepareJob?.cancel(); importSession?.close(); importSession = null; accountTasks.forEach(Job::cancel); accountTasks.clear(); pageJob?.cancel(); searchJob?.cancel(); playJob?.cancel(); invalidateMix(); audio.stop() }
    private fun track(job: Job): Job { accountTasks += job; job.invokeOnCompletion { accountTasks.remove(job) }; return job }
    private var pageJob: Job? = null
    private var searchJob: Job? = null
    private var playJob: Job? = null
    private val transferLock = Semaphore(1)
    private val downloadQueue = ArrayDeque<Song>()
    private var downloadJob: Job? = null
    private var queueSaveJob: Job? = null
    private val offlineGate = OfflineListenGate()
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
    private val destinations = mutableMapOf<String, Pair<String, DesktopPage?>>()
    init {
        scope.launch { audio.state.collect { sound ->
            val song = state.value.now
            if (song != null && offlineGate.observe("${store?.credentials?.accountId}:${song.id}", sound.playing, sound.position, sound.duration, System.nanoTime() / 1_000_000) && state.value.autoOffline.afterListen) downloadAll(listOf(song), silent = true)
            if (song != null && sound.playing && sound.position >= 30_000 && recorded != song.id) {
                recorded = song.id
                val nav = api; val local = store
                launch(Dispatchers.IO) { local?.record(song); runCatching { nav?.scrobble(song.id) } }
            }
        } }
    }
    private fun allowed(song: Song) = ContentPolicy.allowed(song, state.value.childMode)
    fun childMode(enabled: Boolean) {
        if (enabled == state.value.childMode) return
        // Invalidate pending decoders and callbacks before publishing the new policy.
        queueEpoch++; playJob?.cancel(); prepareJob?.cancel(); invalidateMix()
        audio.stop(); audio.clearNext(); queue.set(emptyList(), 0)
        downloadJob?.cancel(); downloadJob = null
        downloadQueue.removeAll { !ContentPolicy.allowed(it, enabled) }
        mutable.update { it.copy(childMode = enabled, now = null, queue = emptyList(), cursor = 0,
            wave = false, ended = false, busy = false, shuffle = false, transfer = null,
            downloadIds = downloadQueue.mapTo(hashSetOf(), Song::id)) }
        store?.childMode(enabled)
        downloadAll(emptyList(), silent = true)
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
                downloadAll(withContext(Dispatchers.IO) { local.pendingDownloads() }, silent = true)
                refresh()
                refreshImport(); refreshUploadConnection()
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
            downloadAll(withContext(Dispatchers.IO) { local.pendingDownloads() }, silent = true)
            refresh(); refreshImport(); refreshUploadConnection(); checkUpdates(silent = true)
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
            if (revision == generation) mutable.update { it.copy(total = info.librarySongCount, avatar = picture, isAdmin = info.isAdmin) }
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
    fun playCollection(songs: List<Song>, source: String) {
        val candidates = songs.filter(::allowed)
        if (candidates.isEmpty()) { notice(ContentPolicy.BLOCKED_MESSAGE); return }
        play(candidates, source = source)
    }
    fun play(songs: List<Song>, index: Int = 0, source: String = "Вся музыка", wave: Boolean = false) {
        invalidateMix()
        if (state.value.demo) { notice("Это демонстрация интерфейса. Войди на сервер, чтобы слушать музыку"); return }
        if (songs.isEmpty()) return
        val selection = ContentPolicy.selection(songs, index, state.value.childMode)
        if (selection == null) { notice(ContentPolicy.BLOCKED_MESSAGE); return }
        queueEpoch++; prepareJob?.cancel(); audio.clearNext()
        queue.set(selection.songs, selection.index); mutable.update { it.beginQueue(wave, source) }; playCurrent()
    }
    private fun playCurrent() {
        val song = queue.current ?: return; val nav = api ?: return; val local = store ?: return
        if (!allowed(song)) { audio.stop(); notice(ContentPolicy.BLOCKED_MESSAGE); return }
        val epoch = ++queueEpoch; val revision = generation
        playJob?.cancel(); prepareJob?.cancel(); audio.stop(preservePrepared = true); recorded = ""
        mutable.update { it.copy(now = song, queue = queue.order.map(queue.songs::get), cursor = queue.cursor, ended = false) }
        playJob = scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { local.audio(song).takeIf(Files::isRegularFile)?.toUri()?.toString() ?: nav.stream(song) }
                currentCoroutineContext().ensureActive()
                if (epoch != queueEpoch || revision != generation || !allowed(song)) return@launch
                audio.start(uri, "$epoch:${queue.order[queue.cursor]}")
                prepareNext()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice(failure(e)) }
        }
    }
    private fun prepareNext() {
        prepareJob?.cancel()
        if (state.value.repeatOne) { audio.clearNext(); return }
        val position = if (queue.cursor + 1 < queue.order.size) queue.cursor + 1 else if (state.value.repeat && !state.value.wave && queue.order.size > 1) 0 else -1
        val index = queue.order.getOrNull(position)
        if (index == null || index == queue.order.getOrNull(queue.cursor)) { audio.clearNext(); return }
        val song = queue.songs[index]; if (!allowed(song)) { audio.clearNext(); return }; val nav = api ?: return; val local = store ?: return
        val epoch = queueEpoch; val revision = generation; val cache = covers
        prepareJob = track(scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { local.audio(song).takeIf(Files::isRegularFile)?.toUri()?.toString() ?: nav.stream(song) }
                currentCoroutineContext().ensureActive()
                if (epoch != queueEpoch || revision != generation || !allowed(song)) return@launch
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
        if (queue.songs.getOrNull(index)?.let(::allowed) != true) { audio.stop(); return }
        if (!queue.select(position)) return
        val song = queue.current ?: return; recorded = ""
        mutable.update { it.copy(now = song, queue = queue.order.map(queue.songs::get), cursor = queue.cursor, ended = false) }
        prepareNext()
    }
    private fun onEnded() {
        if (state.value.now?.let(::allowed) != true) return
        if (state.value.repeatOne) playCurrent()
        else if (queue.move(true, state.value.repeat && !state.value.wave)) playCurrent()
        else if (state.value.wave) { mutable.update { it.copy(ended = true) }; wave(keep = true) }
        else mutable.update { it.copy(ended = true) }
    }
    fun toggle() { if (state.value.now?.let(::allowed) != true) return; invalidateMix(); if (state.value.ended || audio.state.value.error) playCurrent() else audio.toggle() }
    fun skip(forward: Boolean) {
        invalidateMix()
        val song = state.value.now; val activeProfile = profile
        val position = audio.state.value.position; val duration = audio.state.value.duration
        if (queue.move(forward, state.value.repeat && !state.value.wave)) {
            if (forward && state.value.wave && song != null && activeProfile != null) scope.launch {
                try { activeProfile.await { radioEvent(song.id, "skipped", position, duration) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Playback never waits for feedback. */ }
            }
            playCurrent()
        }
    }
    fun select(index: Int) { invalidateMix(); if (queue.select(index)) playCurrent() }
    fun shuffle() { if (state.value.wave) return; invalidateMix(); prepareJob?.cancel(); audio.clearNext(); queue.shuffle(); mutable.update { it.copy(shuffle = queue.shuffled, queue = queue.order.map(queue.songs::get), cursor = queue.cursor) }; prepareNext() }
    fun repeat() {
        mutable.update { state ->
            val current = when { state.repeatOne -> PlaybackRepeat.ONE; state.repeat -> PlaybackRepeat.ALL; else -> PlaybackRepeat.OFF }
            val next = current.next(state.wave)
            state.copy(repeat = next == PlaybackRepeat.ALL, repeatOne = next == PlaybackRepeat.ONE)
        }
        prepareNext()
    }
    fun wave(mood: MixMood = state.value.mood, mode: MixMode = state.value.mode, keep: Boolean = false) {
        val activeProfile = profile ?: return; val local = store ?: return; val revision = generation
        invalidateMix(); val mixToken = mixRevision
        val exclude = if (keep) state.value.queue.takeLast(100).map(Song::id) else listOfNotNull(state.value.now?.id)
        mutable.update { it.copy(busy = true) }
        mixJob = scope.launch {
            try {
                val mix = activeProfile.await { radio(mode, mood, local.history(), exclude = exclude, childMode = state.value.childMode) }
                currentCoroutineContext().ensureActive()
                if (revision == generation && mixToken == mixRevision) {
                    mixJob = null
                    val safe = mix.songs.filter(::allowed)
                    if (safe.isEmpty()) notice(if (state.value.childMode) ContentPolicy.BLOCKED_MESSAGE else mix.emptyMessage()) else {
                        mutable.update { it.copy(mood = mood, mode = mode) }
                        play(safe, source = playbackSourceLabel("", true, mood), wave = true)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == generation && mixToken == mixRevision) notice(failure(e)) }
            finally { if (revision == generation && (mixToken == mixRevision || mixJob == null)) mutable.update { it.copy(busy = false) } }
        }
    }
    fun favorite(song: Song) = action(false) { nav ->
        val revision = generation; val enabled = state.value.favorites.none { it.id == song.id }; nav.await { setFavorite(song.id, enabled) }
        if (revision == generation) {
            mutable.update { it.copy(favorites = if (enabled) (it.favorites + song).distinctBy(Song::id) else it.favorites.filterNot { s -> s.id == song.id }) }
            if (enabled && state.value.autoOffline.onFavorite) downloadAll(listOf(song), silent = true)
        }
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
    fun download(song: Song) = downloadAll(listOf(song))
    fun autoOffline(afterListen: Boolean = state.value.autoOffline.afterListen, onFavorite: Boolean = state.value.autoOffline.onFavorite) {
        val value = OfflinePreferences(afterListen, onFavorite)
        store?.offlinePreferences(value); mutable.update { it.copy(autoOffline = value) }
    }
    fun downloadAll(songs: List<Song>, silent: Boolean = false) {
        val local = store ?: return; val revision = generation
        val known = (state.value.offline.map(Song::id) + downloadQueue.map(Song::id)).toHashSet()
        val added = songs.distinctBy(Song::id).filter { allowed(it) && known.add(it.id) }
        downloadQueue.addAll(added)
        if (added.isNotEmpty()) {
            val previousSave = queueSaveJob
            queueSaveJob = track(scope.launch {
                try { previousSave?.join(); withContext(Dispatchers.IO) { local.queueDownloads(added) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (revision == generation) mutable.update { it.copy(downloadsPaused = true, message = "Не удалось сохранить очередь на диске") } }
            })
            mutable.update { it.copy(downloadIds = downloadQueue.mapTo(hashSetOf(), Song::id)) }
        }
        if (!silent) notice(if (songs.any { !allowed(it) }) ContentPolicy.BLOCKED_MESSAGE else if (added.isEmpty()) "Треки уже скачаны или стоят в очереди" else "В очередь добавлено: ${added.size}")
        if (state.value.downloadsPaused || downloadJob?.isActive == true || downloadQueue.isEmpty()) return
        val nav = api ?: return
        downloadJob = track(scope.launch {
            while (downloadQueue.isNotEmpty() && revision == generation) {
                queueSaveJob?.join()
                currentCoroutineContext().ensureActive()
                if (revision != generation || state.value.downloadsPaused) return@launch
                val song = downloadQueue.first()
                if (!allowed(song)) { downloadQueue.removeFirst(); continue }
                try { transferLock.withPermit { downloadOne(song, local, nav, revision) }; withContext(Dispatchers.IO) { local.finishDownload(song.id) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (revision == generation) mutable.update { it.copy(downloadsPaused = true, message = "Загрузка приостановлена. Проверь сеть и свободное место; очередь сохранена") }; return@launch }
                currentCoroutineContext().ensureActive()
                if (revision != generation) return@launch
                downloadQueue.removeFirst()
                mutable.update { it.copy(downloadIds = downloadQueue.mapTo(hashSetOf(), Song::id)) }
            }
        })
    }
    fun resumeDownloads() {
        val local = store ?: return; val revision = generation
        val pending = downloadQueue.toList(); val previousSave = queueSaveJob
        queueSaveJob = track(scope.launch {
            try {
                previousSave?.join(); withContext(Dispatchers.IO) { local.queueDownloads(pending) }
                if (revision == generation) { mutable.update { it.copy(downloadsPaused = false) }; downloadAll(emptyList(), silent = true) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (revision == generation) mutable.update { it.copy(downloadsPaused = true, message = "Не удалось сохранить очередь на диске") } }
        })
    }
    private suspend fun downloadOne(song: Song, local: DesktopStore, nav: NavApi, revision: Int) {
        currentCoroutineContext().ensureActive(); if (!allowed(song)) return
        mutable.update { it.copy(transfer = "Скачиваем: ${song.title}", downloadProgress = 0) }
        try {
            transfer(nav.client) { client ->
                val output = local.audio(song); Files.createDirectories(output.parent)
                if (Files.isRegularFile(output) && Files.size(output) > 0) { local.downloaded(song, Files.size(output)); return@transfer }
                if (Files.getFileStore(output.parent).usableSpace < 512L * 1024 * 1024) error("Недостаточно места на диске")
                val temp = Files.createTempFile(output.parent, "download-", ".part")
                try {
                    client.newCall(nav.download(song)).execute().use { response ->
                        if (!response.isSuccessful) throw java.io.IOException("Не удалось скачать трек")
                        val body = response.body ?: throw java.io.IOException("Пустой аудиофайл")
                        var count = 0L
                        body.byteStream().use { input -> Files.newOutputStream(temp).use { stream ->
                            val bytes = ByteArray(65536)
                            while (true) { currentCoroutineContext().ensureActive(); val n = input.read(bytes); if (n < 0) break; count += n; if (count > 2L * 1024 * 1024 * 1024) throw java.io.IOException("Файл слишком большой"); if (count % (4 * 1024 * 1024) < 65536 && Files.getFileStore(output.parent).usableSpace < 256L * 1024 * 1024) throw java.io.IOException("Недостаточно места на диске"); stream.write(bytes, 0, n); if (body.contentLength() > 0 && revision == generation) mutable.update { it.copy(downloadProgress = (count * 100 / body.contentLength()).toInt().coerceIn(0, 100)) } }
                        } }
                        if (count <= 0 || body.contentLength() >= 0 && count != body.contentLength()) throw java.io.IOException("Загрузка прервалась")
                        currentCoroutineContext().ensureActive(); Files.move(temp, output, ATOMIC_MOVE, REPLACE_EXISTING); local.downloaded(song, count)
                    }
                } finally { Files.deleteIfExists(temp) }
            }
            if (revision == generation) { mutable.update { it.copy(offline = (it.offline + song).distinctBy(Song::id)) } }
        } finally { if (revision == generation) mutable.update { it.copy(transfer = null) } }
    }
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
                val unified = state.value.unifiedUpload; val service = profile ?: error("Войди в музыкальный аккаунт"); val uploader = if (unified) null else UploadApi(Credentials(ServerAddress.normalize(server), username, password), client).apply { login() }
                for (file in files) {
                    currentCoroutineContext().ensureActive(); val size = Files.size(file); UploadPolicy.checkBytes(size)
                    val progress: (Long, Long) -> Unit = { sent, total -> if (revision == generation) mutable.update { it.copy(transfer = "${file.fileName} · ${if (total > 0) sent * 100 / total else 0}%") } }; if (unified) service.await(600_000) { upload(file.fileName.toString(), size, { Files.newInputStream(file) }, progress) } else uploader!!.upload(file.fileName.toString(), size, { Files.newInputStream(file) }, progress)
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
    private val updateManager = DesktopUpdates(scope, { exitForUpdate })
    val updates = updateManager.state
    fun checkUpdates(silent: Boolean = false) { updateManager.check(force = !silent) }
    fun installUpdate(offer: UpdateOffer) { updateManager.install(offer) }
    fun cancelUpdate() { updateManager.cancel() }
    override fun close() { updateManager.cancel(); scope.cancel(); audio.close() }
}
