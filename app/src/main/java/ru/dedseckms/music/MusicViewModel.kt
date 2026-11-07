@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package ru.dedseckms.music

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import android.os.Bundle
import ru.dedseckms.music.playback.NEXT_COMMAND
import ru.dedseckms.music.playback.PREVIOUS_COMMAND
import ru.dedseckms.music.playback.SHUFFLE_COMMAND
import androidx.work.*
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import ru.dedseckms.music.data.*
import ru.dedseckms.music.playback.MusicService

data class QueueCover(val queueIndex: Int, val song: Song)
data class CollectionPage(val title: String, val songs: List<Song>, val kind: String = "", val id: String = title)
private data class TabDestination(val page: CollectionPage?, val artist: ArtistProfile?, val more: Boolean)
data class Transfer(val name: String, val progress: Int, val state: WorkInfo.State, val error: String = "",
                    val id: String = "", val title: String = "", val artist: String = "", val audioDuration: Long = 0,
                    val indexedSong: Song? = null, val scanRequested: Boolean = false)
data class AppState(
    val importStatus: ImportStatus = ImportStatus(),
    val loggedIn: Boolean = false, val demo: Boolean = false, val username: String = "",
    val loading: Boolean = false, val error: String? = null, val tab: Int = 0,
    val songs: List<Song> = emptyList(), val albums: List<Album> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(), val playlistsLoading: Boolean = false,
    val playlistsError: String? = null, val playlistBusy: Boolean = false, val favorites: List<Song> = emptyList(),
    val favoriteIds: Set<String> = emptySet(), val offline: List<Song> = emptyList(),
    val history: List<HistoryEntry> = emptyList(), val query: String = "", val search: List<Song> = emptyList(),
    val searching: Boolean = false, val page: CollectionPage? = null, val moreAvailable: Boolean = true,
    val searchCategory: SearchCategory = SearchCategory.ALL, val artists: List<Artist> = emptyList(),
    val searchArtists: List<Artist> = emptyList(), val genres: List<Genre> = emptyList(), val catalogLoading: Boolean = false,
    val artistsLoading: Boolean = false, val genresLoading: Boolean = false, val catalogError: String? = null,
    val waveActive: Boolean = false, val playbackSource: String = "Сейчас играет",
    val artistPage: ArtistProfile? = null, val pageMore: Boolean = false,
    val now: Song? = null, val playing: Boolean = false, val position: Long = 0, val duration: Long = 0,
    val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF, val queue: List<Song> = emptyList(),
    val buffering: Boolean = false,
    val queueStart: Int = 0, val queueIndex: Int = 0, val coverQueue: List<QueueCover> = emptyList(),
    val mixMood: MixMood = MixMood.ANY, val mixMode: MixMode = MixMode.BALANCED, val mixLoading: Boolean = false,
    val downloads: Map<String, Transfer> = emptyMap(), val uploads: List<Transfer> = emptyList(),
    val uploadConnected: Boolean = false, val serverDisk: ServerDisk? = null,
    val checkingUploads: Boolean = false, val scanActive: Boolean = false, val uploadCheckError: String? = null,
    val visualStyle: String = "material", val palette: String = "blue",
    val avatarPath: String? = null, val avatarVersion: String = "", val profileBusy: Boolean = false,
    val libraryTotal: Int? = null,
    val wifiOnly: Boolean = true, val theme: String = "system", val dynamicColor: Boolean = true,
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MusicApp
    val updates = AndroidUpdates(app, viewModelScope)
    private val settings = app.getSharedPreferences("appearance", Application.MODE_PRIVATE)
    private val mutable = MutableStateFlow(AppState(theme = settings.getString("theme", "system") ?: "system", dynamicColor = settings.getBoolean("dynamic", true), visualStyle = settings.getString("visualStyle", "material") ?: "material", palette = settings.getString("palette", "blue") ?: "blue"))
    val state: StateFlow<AppState> = mutable.asStateFlow()
    private val messages = Channel<String>(Channel.BUFFERED)
    val notices = messages.receiveAsFlow()
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val accountJobs = mutableListOf<Job>()
    private var searchJob: Job? = null
    private var generation = 0
    private var uploadPoll: Job? = null
    private var importSession: YandexImportSession? = null
    private var importObserver: Job? = null
    private var artistJob: Job? = null
    private val collectionTask = LatestTask()
    private val playlistTask = LatestTask()
    private var playlistMutation: Job? = null
    private val mixTask = LatestTask()
    private val profileTask = LatestTask()
    private val tabDestinations = mutableMapOf<Int, TabDestination>()
    private var lastProfileRefresh = 0L
    private val profileLock = kotlinx.coroutines.sync.Mutex()
    private var requestedSong: Song? = null
    private val uploadTags = mutableMapOf<String, UploadTags>()
    private val work = WorkManager.getInstance(app)

    init {
        updates.check(silent = true)
        connectPlayer()
        app.credentials.active()?.let { account ->
            mutable.update { it.copy(loggedIn = true, username = account.username, songs = local()!!.cachedSongs(), offline = local()!!.offline(),
                history = local()!!.history(), favorites = local()!!.cachedFavorites(), favoriteIds = local()!!.cachedFavorites().map { song -> song.id }.toSet(),
                wifiOnly = local()!!.wifiOnly(), uploadConnected = app.credentials.upload(account.accountId) != null) }
            observeWork(account.accountId)
            restoreAvatar(account.accountId)
            refresh()
        }
    }
    private fun api(): NavApi? = app.api()
    private fun local(): LocalStore? = app.local()
    fun toast(message: String) { messages.trySend(message) }
    private fun safeMessage(error: Exception): String = if (error is java.io.IOException && error.message?.contains("http", true) != true)
        error.message ?: "Не удалось выполнить операцию" else "Не удалось выполнить операцию. Проверь соединение"

    private fun restoreAvatar(account: String) {
        val store = ProfileStore(app, account)
        mutable.update { it.copy(avatarPath = store.avatar.takeIf { file -> file.isFile }?.path, avatarVersion = store.version()) }
    }
    fun onAppResume() { if (state.value.loggedIn && !state.value.demo) { refreshProfile(); refreshImport() } }
    fun refreshImport() {
        if (!state.value.loggedIn || state.value.demo) return
        val profile = app.profileApi() ?: return
        if (importSession == null) {
            val session = YandexImportSession(viewModelScope, profile); importSession = session
            val revision = generation
            importObserver = viewModelScope.launch { session.state.collect { status -> if (revision == generation) mutable.update { it.copy(importStatus = status) } } }
        }
        importSession?.refresh()
    }
    private fun closeImport() { importSession?.close(); importSession = null; importObserver?.cancel(); importObserver = null }
    fun startImport(token: String) { refreshImport(); importSession?.submit(token) }
    fun cancelImport() { importSession?.cancel() }
    fun refreshProfile(force: Boolean = false) {
        if (state.value.demo || state.value.profileBusy || (!force && System.currentTimeMillis() - lastProfileRefresh < 60_000)) return
        updateProfile(null, false, false)
    }
    fun setAvatar(uri: Uri) = updateProfile(uri, false, true)
    fun removeAvatar() = updateProfile(null, true, true)
    private fun updateProfile(uri: Uri?, remove: Boolean, announce: Boolean) {
        val account = app.credentials.activeId() ?: return
        val profile = app.profileApi() ?: return
        val revision = generation
        mutable.update { it.copy(profileBusy = true) }
        profileTask.launch(viewModelScope) { current ->
            try {
                val result = withContext(Dispatchers.IO) { profileLock.withLock {
                    val operation = currentCoroutineContext()
                    operation.ensureActive()
                    profile.await {
                    val info = when { uri != null -> upload(prepareAvatar(app, uri)); remove -> this.remove(); else -> me() }
                    operation.ensureActive()
                    val store = ProfileStore(app, account)
                    if (info.avatarVersion.isBlank()) store.clear()
                    else if (info.avatarVersion != store.version() || !store.avatar.isFile) {
                        val bytes = avatar()
                        operation.ensureActive()
                        store.save(bytes, info.avatarVersion)
                    }
                    info to store.avatar.takeIf { it.isFile }?.path
                    }
                } }
                if (revision == generation && current() && account == app.credentials.activeId()) {
                    lastProfileRefresh = System.currentTimeMillis()
                    mutable.update { it.copy(avatarPath = result.second, avatarVersion = result.first.avatarVersion,
                        libraryTotal = result.first.librarySongCount.takeIf { count -> count >= 0 }, profileBusy = false) }
                    if (announce) toast(if (remove) "Аватарка удалена" else "Аватарка сохранена")
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { if (revision == generation && current()) {
                lastProfileRefresh = System.currentTimeMillis()
                mutable.update { it.copy(profileBusy = false) }
                if (announce) toast(safeMessage(error))
            } }
        }
    }

    private fun connectPlayer() {
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, MusicService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) { updatePlayer() }
            }).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val player = future.get()
                controller = player
                player.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { updatePlayer() }
                    override fun onPlayerError(error: PlaybackException) { requestedSong = null; mutable.update { it.copy(buffering = false) }; toast("Не удалось воспроизвести трек. Проверь доступ к серверу") }
                })
                updatePlayer()
            } catch (_: Exception) { toast("Плеер запускается. Открой приложение ещё раз, если управление недоступно") }
        }, ContextCompat.getMainExecutor(app))
        viewModelScope.launch {
            while (isActive) { delay(1000); updatePlayer(progressOnly = true) }
        }
    }
    private fun updatePlayer(progressOnly: Boolean = false) {
        val player = controller ?: return
        if (!state.value.loggedIn || state.value.demo) return
        val current = player.currentMediaItem?.let(MusicService::songFromItem)
        if (current?.id == requestedSong?.id) requestedSong = null
        val pending = requestedSong
        val song = pending ?: current
        val extras = player.currentMediaItem?.mediaMetadata?.extras
        val waveActive = extras?.getBoolean("mix") == true
        val playingMood = runCatching { MixMood.valueOf(extras?.getString("mixMood").orEmpty()) }.getOrDefault(MixMood.ANY)
        val source = playbackSourceLabel(extras?.getString("playbackSource").orEmpty(), waveActive, playingMood)
        val queueStart = (player.currentMediaItemIndex - 5).coerceAtLeast(0)
        val navigation = player.sessionExtras.takeIf { it.getString("navigationCurrent") == current?.id &&
            it.getInt("navigationCount", -1) == player.mediaItemCount && it.getBoolean("navigationShuffle") == player.shuffleModeEnabled }
        val previous = navigation?.getInt("navigationPrevious", -1) ?: player.previousMediaItemIndex
        val next = navigation?.getInt("navigationNext", -1) ?: player.nextMediaItemIndex
        val covers = if (progressOnly) state.value.coverQueue else listOf(previous, player.currentMediaItemIndex, next)
            .filter { it in 0 until player.mediaItemCount }.distinct().mapNotNull { index ->
                MusicService.songFromItem(player.getMediaItemAt(index))?.let { QueueCover(index, it) }
            }
        mutable.update { it.copy(now = song, waveActive = waveActive, playbackSource = if (pending != null) it.playbackSource else source, playing = player.isPlaying && pending == null, buffering = pending != null || player.playbackState == Player.STATE_BUFFERING,
            position = if (pending != null) 0 else player.currentPosition.coerceAtLeast(0), duration = if (pending != null) pending.duration * 1000L else player.duration.coerceAtLeast(0),
            shuffle = player.shuffleModeEnabled, repeat = player.repeatMode,
            queueStart = if (progressOnly) it.queueStart else queueStart, queueIndex = player.currentMediaItemIndex.coerceAtLeast(0), coverQueue = covers,
            queue = if (progressOnly) it.queue else (queueStart until minOf(player.mediaItemCount, queueStart + 105)).mapNotNull { i -> MusicService.songFromItem(player.getMediaItemAt(i)) }) }
    }
    fun login(server: String, username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) { toast("Введи логин и пароль"); return }
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val account = withContext(Dispatchers.IO) {
                    val candidate = Credentials(ServerAddress.normalize(server), username.trim(), password)
                    val nav = NavApi(candidate); nav.ping()
                    val canonical = nav.canonicalUsername()
                    Credentials(candidate.server, canonical, password)
                }
                closeImport()
                app.credentials.save(account)
                tabDestinations.clear(); profileTask.cancel(); app.clearProfileSession(); lastProfileRefresh = 0
                collectionTask.cancel(); mixTask.cancel(); playlistTask.cancel(); playlistMutation?.cancel()
                generation++
                app.accountChanges.tryEmit(Unit)
                mutable.value = AppState(loggedIn = true, username = account.username,
                    songs = LocalStore(app, account.accountId).cachedSongs(), offline = LocalStore(app, account.accountId).offline(),
                    history = LocalStore(app, account.accountId).history(), wifiOnly = LocalStore(app, account.accountId).wifiOnly(),
                    favorites = LocalStore(app, account.accountId).cachedFavorites(), favoriteIds = LocalStore(app, account.accountId).cachedFavorites().map { it.id }.toSet(),
                    theme = state.value.theme, dynamicColor = state.value.dynamicColor, visualStyle = state.value.visualStyle, palette = state.value.palette,
                    uploadConnected = app.credentials.upload(account.accountId) != null)
                observeWork(account.accountId)
                restoreAvatar(account.accountId)
                refresh()
            } catch (error: Exception) {
                mutable.update { it.copy(loading = false, error = safeMessage(error)) }
            }
        }
    }
    fun demo() {
        closeImport()
        collectionTask.cancel(); mixTask.cancel(); playlistTask.cancel(); playlistMutation?.cancel()
        mutable.value = AppState(loggedIn = true, demo = true, username = "Демо", songs = Demo.songs, albums = Demo.albums,
            favorites = Demo.songs.take(4), favoriteIds = Demo.songs.take(4).map { it.id }.toSet(),
            playlists = listOf(MusicPlaylist("demo-p1", "Ночной маршрут", "Демо", 8, "", false)),
            theme = state.value.theme, dynamicColor = state.value.dynamicColor, visualStyle = state.value.visualStyle, palette = state.value.palette)
    }
    fun logout() {
        closeImport()
        tabDestinations.clear(); profileTask.cancel(); app.clearProfileSession(); lastProfileRefresh = 0
        collectionTask.cancel(); mixTask.cancel(); playlistTask.cancel(); playlistMutation?.cancel()
        requestedSong = null
        controller?.stop(); controller?.clearMediaItems()
        searchJob?.cancel(); artistJob?.cancel(); uploadPoll?.cancel(); accountJobs.forEach(Job::cancel); accountJobs.clear()
        app.credentials.activeId()?.let { work.cancelAllWorkByTag("downloads-$it"); work.cancelAllWorkByTag("uploads-$it") }
        app.credentials.logout(); app.accountChanges.tryEmit(Unit); generation++
        app.playRequests.clear()
        uploadTags.clear()
        mutable.value = AppState(theme = state.value.theme, dynamicColor = state.value.dynamicColor, visualStyle = state.value.visualStyle, palette = state.value.palette)
    }
    fun tab(index: Int) {
        if (index == state.value.tab) return
        val old = state.value
        tabDestinations[old.tab] = TabDestination(old.page?.takeUnless { old.loading && it.songs.isEmpty() }, old.artistPage, old.pageMore)
        val destination = tabDestinations[index]
        collectionTask.cancel(); artistJob?.cancel()
        mutable.update { it.copy(tab = index, page = destination?.page, artistPage = destination?.artist, pageMore = destination?.more ?: false, loading = false) }
        if (destination?.page == null && destination?.artist?.loading == true) openArtist(destination.artist.artist, destination.artist)
        refreshProfile()
        if (index == 1) loadCatalog()
        uploadPoll?.cancel()
        if (index == 3) uploadPoll = viewModelScope.launch {
            while (isActive && state.value.tab == 3) { checkUploads(); delay(20_000) }
        }
    }
    fun dismissPage() {
        collectionTask.cancel()
        mutable.update { it.copy(loading = false) }
        if (state.value.page != null) {
            mutable.update { it.copy(page = null) }
            state.value.artistPage?.takeIf { it.loading }?.let { openArtist(it.artist, it) }
        }
        else { artistJob?.cancel(); mutable.update { it.copy(artistPage = null) } }
    }
    fun searchCategory(value: SearchCategory) { mutable.update { it.copy(searchCategory = value) } }
    fun loadCatalog() {
        if (state.value.demo) {
            mutable.update { it.copy(artists = Demo.artists, genres = Demo.genres) }; return
        }
        val nav = api() ?: return
        val store = local() ?: return
        if (state.value.catalogLoading) return
        if (state.value.catalogError == null && state.value.artists.isNotEmpty() && state.value.genres.isNotEmpty()) return
        val revision = generation
        mutable.update { it.copy(catalogLoading = true, artistsLoading = true, genresLoading = true, catalogError = null) }
        val job = viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { store.cachedArtists() to store.cachedGenres() }
            if (revision != generation) return@launch
            mutable.update { it.copy(artists = cached.first.ifEmpty { it.artists }, genres = cached.second.ifEmpty { it.genres }) }
            supervisorScope {
                launch {
                    try {
                        val result = nav.await { artists() }
                        withContext(Dispatchers.IO) { store.cacheArtists(result) }
                        if (revision == generation) mutable.update { it.copy(artists = result) }
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { if (revision == generation) mutable.update { it.copy(catalogError = "Не удалось загрузить исполнителей. " + safeMessage(e)) }
                    } finally { if (revision == generation) mutable.update { it.copy(artistsLoading = false, catalogLoading = it.genresLoading) } }
                }
                launch {
                    try {
                        val result = nav.await { genres() }
                        withContext(Dispatchers.IO) { store.cacheGenres(result) }
                        if (revision == generation) mutable.update { it.copy(genres = result) }
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { if (revision == generation) mutable.update { it.copy(catalogError = "Не удалось загрузить жанры. " + safeMessage(e)) }
                    } finally { if (revision == generation) mutable.update { it.copy(genresLoading = false, catalogLoading = it.artistsLoading) } }
                }
            }
        }
        accountJobs += job
    }

    fun refresh() {
        if (state.value.demo) return
        val nav = api() ?: return
        val store = local() ?: return
        val revision = generation
        val playlistRevision = playlistTask.capture()
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    store.pendingFavorites().forEach { (id, value) -> runCatching { nav.setFavorite(id, value); store.favoriteSynced(id, value) } }
                    val songs = nav.songs(count = 100)
                    val albums = nav.albums()
                    val favorites = nav.favorites()
                    store.cacheFavorites(favorites)
                    val playlists = nav.playlists()
                    store.cacheSongs(songs + store.cachedSongs())
                    Triple(songs, albums, favorites) to playlists
                }
                if (revision != generation) return@launch
                val (home, playlists) = result
                val favoriteIds = home.third.map { it.id }.toMutableSet()
                store.pendingFavorites().forEach { (id, enabled) -> if (enabled) favoriteIds += id else favoriteIds -= id }
                mutable.update { it.copy(songs = home.first, albums = home.second, favorites = home.third,
                    favoriteIds = favoriteIds, playlists = if (playlistTask.isCurrent(playlistRevision)) playlists else it.playlists, offline = store.offline(), history = store.history(),
                    moreAvailable = home.first.size >= 100, loading = false) }
            } catch (error: Exception) {
                if (revision == generation) mutable.update { it.copy(loading = false, error = safeMessage(error), offline = store.offline()) }
            }
            refreshDisk()
            refreshProfile()
        }
    }
    fun loadMore() {
        val nav = api() ?: return
        if (state.value.loading || !state.value.moreAvailable) return
        val offset = state.value.songs.size
        val revision = generation
        mutable.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val more = withContext(Dispatchers.IO) { nav.songs(offset = offset) }
                if (revision == generation) mutable.update { it.copy(songs = (it.songs + more).distinctBy(Song::id), loading = false, moreAvailable = more.size >= 100) }
            } catch (error: Exception) { if (revision == generation) { mutable.update { it.copy(loading = false) }; toast(safeMessage(error)) } }
        }
    }
    fun query(value: String) {
        searchJob?.cancel()
        mutable.update { it.copy(query = value, searching = value.isNotBlank()) }
        if (value.isBlank()) { mutable.update { it.copy(search = emptyList(), searchArtists = emptyList(), searching = false) }; return }
        val nav = api()
        val revision = generation
        val source = state.value
        searchJob = viewModelScope.launch {
            val cached = withContext(Dispatchers.Default) { SearchResults(
                (source.songs + source.favorites + source.offline + if (source.demo) Demo.songs else emptyList()).distinctBy(Song::id)
                    .filter { SearchText.matches(value, "${it.title} ${it.artist} ${it.album}") }.take(100),
                (source.artists + if (source.demo) Demo.artists else emptyList()).distinctBy(Artist::id)
                    .filter { SearchText.matches(value, it.name) }.take(50)) }
            if (revision == generation && state.value.query == value) mutable.update { it.copy(search = cached.songs, searchArtists = cached.artists) }
            delay(180)
            try {
                val result = nav?.await { search(value.trim()) } ?: cached
                if (revision == generation && state.value.query == value) mutable.update { it.copy(
                    search = (result.songs + cached.songs).distinctBy(Song::id).take(100),
                    searchArtists = (result.artists + cached.artists).distinctBy(Artist::id).take(50), searching = false) }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                if (revision == generation && state.value.query == value) {
                    mutable.update { it.copy(searching = false) }
                    toast("Поиск по сохранённым трекам. Проверь связь с сервером")
                }
            }
        }
    }
    fun openAlbum(album: Album) = openCollection(album.name, "album", album.id) { api()?.albumSongs(album.id) ?: Demo.songs }
    fun openArtist(artist: Artist, seed: ArtistProfile? = null) {
        collectionTask.cancel()
        artistJob?.cancel()
        val revision = generation
        val nav = api()
        mutable.update { it.copy(page = null, artistPage = seed?.copy(loading = true, error = null) ?: ArtistProfile(artist), loading = false) }
        artistJob = viewModelScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    if (nav == null) ArtistProfile(artist, Demo.albums.filter { it.artist == artist.name }, Demo.songs.filter { it.artist == artist.name }, loading = false)
                    else {
                        val info = nav.await { artist(artist.id) }
                        if (revision == generation && state.value.artistPage?.artist?.id == artist.id) mutable.update { it.copy(artistPage = info.copy(songs = seed?.songs.orEmpty())) }
                        val slots = Semaphore(3)
                        val songs = coroutineScope { info.albums.map { album -> async { slots.withPermit {
                            val part = nav.await { albumSongs(album.id) }
                            if (revision == generation && state.value.artistPage?.artist?.id == artist.id) mutable.update { state -> state.copy(artistPage = state.artistPage?.let { it.copy(songs = (it.songs + part).distinctBy(Song::id)) }) }
                            part
                        } } }.awaitAll().flatten().distinctBy { it.id } }
                        info.copy(songs = songs, loading = false)
                    }
                }
                if (revision == generation && state.value.artistPage?.artist?.id == artist.id) mutable.update { it.copy(artistPage = profile) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { if (revision == generation && state.value.artistPage?.artist?.id == artist.id) mutable.update { it.copy(artistPage = it.artistPage?.copy(loading = false, error = safeMessage(error))) } }
        }
    }
    fun openArtist(song: Song) {
        val artist = state.value.artists.find { it.id == song.artistId } ?: Artist(song.artistId, song.artist)
        if (artist.id.isNotBlank()) openArtist(artist)
        else { tab(1); searchCategory(SearchCategory.ARTISTS); query(song.artist) }
    }
    fun openGenre(genre: Genre) {
        openCollection(genre.name, "genre") { api()?.genreSongs(genre.name) ?: Demo.songs.filter { it.genre == genre.name } }
    }
    fun moreGenre() {
        val page = state.value.page?.takeIf { it.kind == "genre" } ?: return
        val nav = api() ?: return
        val revision = generation
        val collectionRevision = collectionTask.capture()
        mutable.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val songs = withContext(Dispatchers.IO) { nav.genreSongs(page.title, page.songs.size) }
                if (revision == generation && collectionTask.isCurrent(collectionRevision) && state.value.page?.kind == "genre" && state.value.page?.title == page.title) mutable.update { it.copy(page = page.copy(songs = (page.songs + songs).distinctBy(Song::id)), pageMore = songs.size >= 100, loading = false) }
            } catch (error: Exception) { if (revision == generation && collectionTask.isCurrent(collectionRevision)) { mutable.update { it.copy(loading = false) }; toast(safeMessage(error)) } }
        }
    }
    fun openPlaylist(playlist: MusicPlaylist) {
        refreshPlaylists()
        openCollection(playlist.name, "playlist", playlist.id) { api()?.playlistSongs(playlist.id) ?: Demo.songs }
    }
    fun openFavorites() { collectionTask.cancel(); mutable.update { it.copy(page = CollectionPage("Мне нравится", it.favorites, "favorites"), loading = false) } }
    fun openOffline() { collectionTask.cancel(); mutable.update { it.copy(offline = local()?.offline() ?: it.offline, page = CollectionPage("Скачанное", local()?.offline() ?: it.offline, "offline"), loading = false) } }
    private fun openCollection(title: String, kind: String, id: String = title, load: () -> List<Song>) {
        val revision = generation
        mutable.update { it.copy(loading = true, page = CollectionPage(title, emptyList(), kind, id), pageMore = false) }
        collectionTask.launch(viewModelScope) { current ->
            try {
                val songs = withContext(Dispatchers.IO) { load() }
                if (revision == generation && current()) mutable.update { it.copy(page = CollectionPage(title, songs, kind, id), pageMore = kind == "genre" && songs.size >= 100, loading = false) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { if (revision == generation && current()) { mutable.update { it.copy(loading = false) }; toast(safeMessage(error)) } }
        }
    }
    fun play(songs: List<Song>, index: Int = 0, mix: Boolean = false, source: String = "") {
        mixTask.cancel()
        mutable.update { it.copy(mixLoading = false) }
        playPrepared(songs, index, mix, state.value.mixMode, state.value.mixMood, source)
    }
    private fun playPrepared(songs: List<Song>, index: Int, mix: Boolean, mode: MixMode, mood: MixMood, source: String = "") {
        if (state.value.demo) { toast("Это демо интерфейса. Подключи сервер для прослушивания"); return }
        if (songs.isEmpty()) { toast("Здесь пока нет треков"); return }
        val player = controller ?: run { toast("Плеер ещё запускается"); return }
        val account = app.credentials.activeId() ?: return
        requestedSong = songs[index.coerceIn(songs.indices)]
        player.pause()
        mutable.update { it.copy(now = requestedSong, playbackSource = playbackSourceLabel(source, mix, mood), playing = false, buffering = true, position = 0, duration = (requestedSong?.duration ?: 0) * 1000L) }
        val token = app.playRequests.offer(account, songs, index, mix, mode, mood, source)
        player.setMediaItem(MediaItem.Builder().setMediaId("queue:$token").build())
        player.prepare(); player.play()
    }
    fun toggleWave() { if (state.value.waveActive) togglePlay() else wave() }
    fun trackGain(song: Song): Int = local()?.trackGain(song.id) ?: 0
    fun trackGain(song: Song, decibels: Int) { local()?.trackGain(song.id, decibels); app.volumeChanges.tryEmit(Unit) }
    fun wave(mode: MixMode = state.value.mixMode, mood: MixMood = state.value.mixMood) {
        if (state.value.demo) { toast("Подключи сервер, и здесь будет персональный микс"); return }
        val nav = api() ?: return
        val store = local() ?: return
        val revision = generation
        mutable.update { it.copy(mixLoading = true, mixMode = mode, mixMood = mood) }
        mixTask.launch(viewModelScope) { current ->
            try {
                val songs = withContext(Dispatchers.IO) {
                    val favorites = runCatching { nav.favorites() }.getOrDefault(state.value.favorites)
                    val candidates = runCatching { nav.random(300) }.getOrElse { store.offline() }
                    MixEngine.build(candidates + favorites, state.value.favoriteIds, store.history(), store.disliked(), mode, mood = mood)
                }
                if (revision == generation && current()) { mutable.update { it.copy(mixLoading = false) }; playPrepared(songs, 0, true, mode, mood) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { if (revision == generation && current()) { mutable.update { it.copy(mixLoading = false) }; toast(safeMessage(error)) } }
        }
    }
    fun togglePlay() { mixTask.cancel(); mutable.update { it.copy(mixLoading = false) }; controller?.let { if (it.playbackState == Player.STATE_ENDED && it.mediaItemCount > 0) { it.seekTo(0L); it.play() } else if (it.playWhenReady) it.pause() else it.play() } }
    fun playQueued(index: Int) = playQueueIndex(state.value.queueStart + index)
    fun playQueueIndex(target: Int) {
        mixTask.cancel(); mutable.update { it.copy(mixLoading = false) }
        controller?.let { player ->
            if (target in 0 until player.mediaItemCount) { player.seekToDefaultPosition(target); player.play() }
        }
    }
    fun next() = skip(true)
    fun previous() = skip(false)
    private fun skip(forward: Boolean) {
        mixTask.cancel(); mutable.update { it.copy(mixLoading = false) }
        val player = controller ?: return
        requestedSong = null
        player.sendCustomCommand(SessionCommand(if (forward) NEXT_COMMAND else PREVIOUS_COMMAND, Bundle.EMPTY), Bundle.EMPTY)
    }
    fun seek(position: Long) { controller?.seekTo(position) }
    fun shuffle() { controller?.sendCustomCommand(SessionCommand(SHUFFLE_COMMAND, Bundle.EMPTY), Bundle.EMPTY) }
    fun repeat() { controller?.let { it.repeatMode = if (it.repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF } }
    fun dislike() { state.value.now?.let { local()?.dislike(it.id); next(); toast("Трек исключён из моих миксов") } }
    fun favorite(song: Song) {
        if (state.value.demo) { mutable.update { it.copy(favoriteIds = if (song.id in it.favoriteIds) it.favoriteIds - song.id else it.favoriteIds + song.id) }; return }
        val nav = api() ?: return
        val store = local() ?: return
        val enabled = song.id !in state.value.favoriteIds
        val revision = generation
        store.pendingFavorite(song.id, enabled)
        mutable.update { it.copy(favoriteIds = if (enabled) it.favoriteIds + song.id else it.favoriteIds - song.id,
            favorites = if (enabled) (it.favorites + song).distinctBy(Song::id) else it.favorites.filterNot { track -> track.id == song.id }) }
        store.cacheFavorites(state.value.favorites)
        viewModelScope.launch(Dispatchers.IO) {
            try { nav.setFavorite(song.id, enabled); store.favoriteSynced(song.id, enabled) }
            catch (_: Exception) { if (revision == generation) toast("Лайк сохранён на телефоне. Синхронизируем при следующем обновлении") }
        }
    }
    fun refreshPlaylists() {
        if (state.value.demo) return
        val nav = api() ?: return
        val revision = generation
        mutable.update { it.copy(playlistsLoading = true, playlistsError = null) }
        playlistTask.launch(viewModelScope) { current ->
            try {
                val lists = nav.await { playlists() }
                if (revision == generation && current()) mutable.update { it.copy(playlists = lists, playlistsLoading = false) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { if (revision == generation && current()) mutable.update { it.copy(playlistsLoading = false, playlistsError = safeMessage(error)) } }
        }
    }
    private fun applyPlaylistContents(contents: PlaylistContents) {
        fun replace(page: CollectionPage?): CollectionPage? = if (page?.kind == "playlist" && page.id == contents.playlist.id)
            page.copy(title = contents.playlist.name, songs = contents.songs) else page
        if (state.value.page?.let { it.kind == "playlist" && it.id == contents.playlist.id } == true) collectionTask.cancel()
        mutable.update { it.copy(page = replace(it.page), loading = if (it.page?.let { page -> page.kind == "playlist" && page.id == contents.playlist.id } == true) false else it.loading,
            playlists = it.playlists.map { list -> if (list.id == contents.playlist.id) contents.playlist else list }) }
        tabDestinations.replaceAll { _, destination -> destination.copy(page = replace(destination.page)) }
    }
    private fun changePlaylist(action: suspend (NavApi) -> Unit) {
        if (state.value.importStatus.job?.syncingPlaylists == true) { toast("Дождись завершения переноса плейлистов"); return }
        if (state.value.demo) { toast("Для сохранения плейлистов подключи сервер"); return }
        if (state.value.playlistBusy) return
        val nav = api() ?: return
        val revision = generation
        playlistTask.cancel()
        mutable.update { it.copy(playlistBusy = true, playlistsLoading = false) }
        playlistMutation = viewModelScope.launch {
            try {
                action(nav)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { if (revision == generation) {
                if (error is PlaylistChangedException) applyPlaylistContents(error.latest)
                toast(safeMessage(error))
            } } finally { if (revision == generation) {
                mutable.update { it.copy(playlistBusy = false) }
                refreshPlaylists()
            } }
        }
    }
    fun createPlaylist(name: String, song: Song? = null) {
        val revision = generation
        changePlaylist { nav ->
            val created = nav.await { createPlaylist(name, listOfNotNull(song?.id)) }
            if (revision == generation) { mutable.update { it.copy(playlists = it.playlists.filterNot { list -> list.id == created.id } + created) }; toast("Приватный плейлист создан") }
        }
    }
    fun addToPlaylist(playlist: MusicPlaylist, song: Song) {
        val revision = generation
        changePlaylist { nav ->
            nav.await { appendToPlaylist(playlist, song) }
            if (revision != generation) return@changePlaylist
            toast("Добавлено в «${playlist.name}»")
            // Read back the server result so cached tabs also include changes from other clients.
            try {
                val contents = nav.await { playlist(playlist.id) }
                if (revision == generation) applyPlaylistContents(contents)
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { if (revision == generation) {
                // The append succeeded. Never offer a blind retry that could add a duplicate.
                fun matches(page: CollectionPage?) = page?.kind == "playlist" && page.id == playlist.id
                if (matches(state.value.page)) collectionTask.cancel()
                mutable.update { it.copy(page = if (matches(it.page)) null else it.page, loading = if (matches(it.page)) false else it.loading) }
                tabDestinations.replaceAll { _, destination -> if (matches(destination.page)) destination.copy(page = null) else destination }
                toast("Трек добавлен. Не удалось обновить список — открой плейлист заново")
            } }

        }
    }
    fun removeFromPlaylist(page: CollectionPage, index: Int) {
        if (page.kind != "playlist") return
        val revision = generation
        changePlaylist { nav ->
            val contents = nav.await { removeFromPlaylist(page.id, index, page.songs.map(Song::id)) }
            if (revision == generation) { applyPlaylistContents(contents); toast("Убрано из «${page.title}»") }
        }
    }
    fun download(song: Song) {
        val account = app.credentials.activeId() ?: run { toast("Подключи сервер для скачивания"); return }
        if (state.value.offline.any { it.id == song.id }) { toast("Трек уже скачан"); return }
        if (state.value.downloads[song.id]?.state in setOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)) { toast("Трек уже в очереди скачивания"); return }
        val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf("account" to account, "song" to song.json().toString()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (state.value.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .addTag("downloads-$account").addTag("track:${song.id}").addTag("created:${System.currentTimeMillis()}").build()
        work.enqueueUniqueWork("offline-$account-${sha256(song.id)}", ExistingWorkPolicy.KEEP, request)
        toast(if (state.value.wifiOnly) "В очереди скачивания по Wi-Fi" else "В очереди скачивания")
    }
    fun removeOffline(song: Song) {
        local()?.removeDownload(song.id)
        mutable.update { it.copy(offline = local()?.offline().orEmpty(), page = it.page?.let { page -> if (page.kind == "offline") page.copy(songs = local()?.offline().orEmpty()) else page }) }
        toast("Офлайн-копия удалена с телефона")
    }
    fun wifiOnly(value: Boolean) { local()?.wifiOnly(value); mutable.update { it.copy(wifiOnly = value) } }
    fun appearance(theme: String = state.value.theme, dynamic: Boolean = state.value.dynamicColor,
                   palette: String = state.value.palette, visualStyle: String = state.value.visualStyle) {
        val mode = theme.takeIf { it in setOf("system", "light", "dark", "amoled") } ?: "system"
        val style = visualStyle.takeIf { it in setOf("material", "glass", "aurora", "mono") } ?: "material"
        val color = palette.takeIf { it in setOf("blue", "violet", "mint", "rose", "amber", "cyan") } ?: "blue"
        settings.edit().putString("theme", mode).putBoolean("dynamic", dynamic).putString("palette", color).putString("visualStyle", style).apply()
        mutable.update { it.copy(theme = mode, dynamicColor = dynamic, palette = color, visualStyle = style) }
    }
    private fun observeWork(account: String) {
        accountJobs.forEach(Job::cancel); accountJobs.clear()
        accountJobs += viewModelScope.launch {
            work.getWorkInfosByTagFlow("downloads-$account").collect { entries ->
                if (app.credentials.activeId() != account) return@collect
                val transfers = DownloadWork.current(entries).mapValues { (_, item) ->
                    Transfer("", item.progress.getInt("progress", 0), item.state, item.outputData.getString("error").orEmpty()) }
                mutable.update { it.copy(downloads = transfers, offline = local()?.offline().orEmpty()) }
            }
        }
        accountJobs += viewModelScope.launch {
            work.getWorkInfosByTagFlow("uploads-$account").collect { entries ->
                if (app.credentials.activeId() != account) return@collect
                mutable.update { it.copy(uploads = entries.sortedWith(compareBy<WorkInfo> { entry -> entry.state.isFinished }.thenByDescending { entry -> entry.outputData.getLong("uploadedAt", 0) }).map { entry ->
                    Transfer(entry.progress.getString("name") ?: entry.outputData.getString("name") ?: entry.tags.find { it.startsWith("name:") }?.removePrefix("name:").orEmpty(),
                        entry.progress.getInt("progress", 0), entry.state, entry.outputData.getString("error").orEmpty(), id = entry.id.toString(),
                        title = entry.outputData.getString("title").orEmpty(), artist = entry.outputData.getString("artist").orEmpty(), audioDuration = entry.outputData.getLong("duration", 0),
                        indexedSong = it.uploads.find { transfer -> transfer.id == entry.id.toString() }?.indexedSong, scanRequested = entry.outputData.getBoolean("scanRequested", false)) }) }
                if (state.value.tab == 3) checkUploads()
            }
        }
    }
    fun connectUpload(server: String, username: String, password: String) {
        val account = app.credentials.activeId() ?: return
        val revision = generation
        mutable.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val credentials = Credentials(ServerAddress.normalize(server), username.trim(), password)
                    val upload = UploadApi(credentials); upload.login()
                    credentials to upload.disk()
                }
                if (revision == generation) {
                    app.credentials.saveUpload(account, data.first)
                    mutable.update { it.copy(uploadConnected = true, serverDisk = data.second, loading = false) }
                    toast("Загрузка файлов подключена")
                }
            } catch (error: Exception) { if (revision == generation) { mutable.update { it.copy(loading = false) }; toast(safeMessage(error)) } }
        }
    }
    fun refreshDisk() {
        val account = app.credentials.activeId() ?: return
        val credentials = app.credentials.upload(account) ?: return
        viewModelScope.launch {
            val disk = withContext(Dispatchers.IO) { runCatching { UploadApi(credentials).apply { login() }.disk() }.getOrNull() }
            if (app.credentials.activeId() == account) mutable.update { it.copy(serverDisk = disk) }
        }
    }
    fun checkUploads() {
        val nav = api() ?: return
        if (state.value.checkingUploads) return
        val pending = state.value.uploads.filter { it.state == WorkInfo.State.SUCCEEDED && it.indexedSong == null }.take(10)
        if (pending.isEmpty()) return
        val revision = generation
        mutable.update { it.copy(checkingUploads = true, uploadCheckError = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val status = runCatching { nav.scanStatus() }.getOrDefault(ScanStatus(false, 0))
                    val found = pending.mapNotNull { transfer ->
                        val tags = if (transfer.title.isNotBlank()) UploadTags(transfer.title, transfer.artist, transfer.audioDuration)
                            else uploadTags.getOrPut(transfer.name) { UploadTagReader.findPersisted(app, transfer.name) }
                        val query = tags.title.ifBlank { transfer.name.substringBeforeLast('.') }
                        val songs = nav.songs(query, count = 100)
                        UploadMatch.find(transfer.name, tags.title, tags.artist, tags.durationMs, songs)?.let { transfer.id to it }
                    }.toMap()
                    status to found
                }
                if (revision == generation) mutable.update { it.copy(checkingUploads = false, scanActive = result.first.scanning,
                    uploads = it.uploads.map { transfer -> transfer.copy(indexedSong = result.second[transfer.id] ?: transfer.indexedSong) }) }
            } catch (_: Exception) { if (revision == generation) mutable.update { it.copy(checkingUploads = false, uploadCheckError = "Не удалось проверить библиотеку. Файл на сервере сохранён.") } }
        }
    }
    fun upload(uris: List<Uri>) {
        val account = app.credentials.activeId() ?: return
        if (!state.value.uploadConnected) { toast("Сначала подключи аккаунт загрузки"); return }
        uris.forEach { uri ->
            try {
                app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                var name = "audio.m4a"; var size = -1L
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) { name = cursor.getString(0); if (!cursor.isNull(1)) size = cursor.getLong(1) }
                }
                name = UploadApi.safeFilename(name)
                val request = OneTimeWorkRequestBuilder<UploadWorker>()
                    .setInputData(workDataOf("account" to account, "uri" to uri.toString(), "name" to name, "size" to size))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag("uploads-$account").addTag("name:$name").build()
                work.enqueueUniqueWork("upload-$account-${sha256(uri.toString())}", ExistingWorkPolicy.KEEP, request)
            } catch (_: Exception) { toast("Не удалось открыть файл. Выбери музыкальный файл из памяти телефона") }
        }
    }
    override fun onCleared() { closeImport(); updates.close(); controllerFuture?.let(MediaController::releaseFuture); super.onCleared() }
}

object Demo {
    val songs = listOf(
        Song("d1", "После полуночи", "Тихий сигнал", "Ночной маршрут", "a1", duration = 218, genre = "Electronic"),
        Song("d2", "Тёплый воздух", "Линия света", "Орбиты", "a2", duration = 193, genre = "Indie"),
        Song("d3", "Между нами город", "Новая волна", "Сторона Б", "a3", duration = 245, genre = "Pop"),
        Song("d4", "Домой", "Атмосфера", "Свои места", "a4", duration = 182, genre = "Electronic"),
        Song("d5", "Снова в пути", "Тихий сигнал", "Ночной маршрут", "a1", duration = 207, genre = "Electronic"),
        Song("d6", "Свет из окна", "Линия света", "Орбиты", "a2", duration = 229, genre = "Indie"),
        Song("d7", "До рассвета", "Новая волна", "Сторона Б", "a3", duration = 202, genre = "Pop"),
        Song("d8", "Медленный пульс", "Атмосфера", "Свои места", "a4", duration = 176, genre = "Electronic"),
    )
    val albums = songs.distinctBy { it.albumId }.map { Album(it.albumId, it.album, it.artist, "", 8) }
    val artists = songs.distinctBy { it.artist }.map { Artist(it.artist, it.artist, albumCount = 1) }
    val genres = songs.groupBy { it.genre }.map { Genre(it.key, it.value.size) }
}
