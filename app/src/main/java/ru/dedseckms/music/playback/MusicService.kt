@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package ru.dedseckms.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.DefaultMediaNotificationProvider
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import ru.dedseckms.music.MainActivity
import ru.dedseckms.music.MusicApp
import ru.dedseckms.music.R
import ru.dedseckms.music.data.*

class MusicService : MediaLibraryService() {
    private lateinit var session: MediaLibrarySession
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as MusicApp
    private var recordedTransition = false
    private var mixActive = false
    private var queueSource = ""
    private var extending = false
    private var mixAccount: String? = null
    private var currentMixMood = MixMood.ANY
    private var currentMixMode = MixMode.BALANCED
    private var artworkJob: Job? = null
    private var prefetchJob: Job? = null
    private var prefetchKey: Pair<String, List<CoverTarget>>? = null
    private var transitionId: String? = null
    private var queueWindow: QueueWindow? = null
    private var queueAccount: String? = null
    private var hydration: Job? = null
    private var mixExtension: Job? = null
    private var playbackRevision = 0
    private var loudness: android.media.audiofx.LoudnessEnhancer? = null

    override fun onCreate() {
        super.onCreate()
        val factory = DataSourceFactory(app)
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_notification) })
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 45_000, 500, 1_000).build()).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        scope.launch { app.contentRatingChanges.collect { (account, song) ->
            if (account != app.credentials.activeId()) return@collect
            app.playbackSongs["$account:${song.id}"] = song
            if (app.local()?.childMode() == true && !allowed(song) &&
                ((0 until player.mediaItemCount).any { player.getMediaItemAt(it).mediaId == song.id } ||
                    queueWindow?.request?.songs?.any { it.id == song.id } == true)) { resetPlayback(); return@collect }
            queueWindow?.updateSongs { value -> if (value.id == song.id) value.copy(contentRating = song.contentRating, contentRatingOverride = song.contentRatingOverride) else value }
            for (index in 0 until player.mediaItemCount) {
                val old = player.getMediaItemAt(index)
                if (old.mediaId != song.id) continue
                val extras = Bundle(old.mediaMetadata.extras ?: Bundle.EMPTY).apply { putString("song", song.json().toString()) }
                player.replaceMediaItem(index, old.buildUpon().setMediaMetadata(old.mediaMetadata.buildUpon().setExtras(extras).build()).build())
            }
        } }
        scope.launch { app.contentPolicyChanges.collect { resetPlayback() } }
        scope.launch { app.accountChanges.collect {
            resetPlayback()
        } }
        scope.launch { app.volumeChanges.collect { applyTrackVolume() } }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { publishNavigation() }
            override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) pauseAtEnd(player) }
            override fun onAudioSessionIdChanged(audioSessionId: Int) { loudness?.release(); loudness = null; applyTrackVolume() }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (mixActive && mixAccount == app.credentials.activeId() && reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId) {
                    val song = oldPosition.mediaItem?.let(::songFromItem) ?: return
                    val profile = app.profileApi() ?: return
                    scope.launch {
                        try { profile.await { radioEvent(song.id, "skipped", oldPosition.positionMs, song.duration * 1000L) } }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { /* Feedback never blocks playback. */ }
                    }
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem != null && songFromItem(mediaItem)?.let { !allowed(it) } != false) { resetPlayback(); return }
                applyTrackVolume()
                if (transitionId != mediaItem?.mediaId) { recordedTransition = false; transitionId = mediaItem?.mediaId }
                if (mediaItem != null && mediaItem.mediaMetadata.artworkData == null) loadArtwork(mediaItem)
                prefetchArtwork()
                if (mixActive && player.mediaItemCount - player.currentMediaItemIndex < 5) extendMix()
                if (!mixActive && player.currentMediaItemIndex < 5) {
                    val account = queueAccount
                    if (account != null && account == app.credentials.activeId()) {
                        val previous = queueWindow?.previous().orEmpty()
                        if (previous.isNotEmpty()) player.addMediaItems(0, previous.map { resolve(it, account) })
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && !mixActive) hydrateQueue()
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { prefetchArtwork() }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { prefetchArtwork() }
            override fun onRepeatModeChanged(repeatMode: Int) { if (mixActive && repeatMode == Player.REPEAT_MODE_ALL) player.repeatMode = Player.REPEAT_MODE_ONE; prefetchArtwork() }
        })
        scope.launch {
            val offlineGate = ru.dedseckms.music.data.OfflineListenGate()
            while (true) {
                delay(1000)
                val active = app.credentials.active()
                val currentSong = player.currentMediaItem?.let(::songFromItem)
                if (active != null && currentSong != null && player.currentMediaItem?.localConfiguration?.uri?.host == active.accountId &&
                    offlineGate.observe("${active.accountId}:${currentSong.id}", player.isPlaying, player.currentPosition, currentSong.duration * 1000L, android.os.SystemClock.elapsedRealtime())) {
                    if (LocalStore(this@MusicService, active.accountId).offlinePreferences().afterListen) scope.launch {
                        try { ru.dedseckms.music.data.OfflineDownloads.enqueue(this@MusicService, active.accountId, listOf(currentSong)) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { /* Automatic copies never interrupt playback. */ }
                    }
                }
                if (player.isPlaying && !recordedTransition && player.currentPosition > 30_000) {
                    val song = player.currentMediaItem?.let(::songFromItem)
                    val credentials = app.credentials.active()
                    if (song != null && credentials != null && player.currentMediaItem?.localConfiguration?.uri?.host == credentials.accountId) {
                        recordedTransition = true
                        scope.launch(Dispatchers.IO) {
                            LocalStore(this@MusicService, credentials.accountId).record(song)
                            runCatching { NavApi(credentials).scrobble(song.id) }
                        }
                    }
                }
            }
        }
    }

    private fun resetPlayback() {
        playbackRevision++
        app.playRequests.clear(); app.playbackSongs.clear()
        artworkJob?.cancel(); prefetchJob?.cancel(); prefetchKey = null
        hydration?.cancel(); mixExtension?.cancel(); extending = false
        queueWindow = null; queueAccount = null; mixActive = false; mixAccount = null; queueSource = ""
        loudness?.release(); loudness = null; player.volume = 1f
        player.stop(); player.clearMediaItems()
    }
    private fun allowed(song: Song): Boolean = ContentPolicy.allowed(app.local()?.contentRatedSong(song) ?: song, app.local()?.childMode() ?: false)
    private fun playable(songs: List<Song>): List<Song> = songs.filter(::allowed)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    private fun publishNavigation() {
        session.setSessionExtras(Bundle().apply {
            putString("navigationCurrent", player.currentMediaItem?.mediaId.orEmpty())
            putInt("navigationCount", player.mediaItemCount)
            putBoolean("navigationShuffle", player.shuffleModeEnabled)
            putInt("navigationPrevious", player.previousMediaItemIndex)
            putInt("navigationNext", player.nextMediaItemIndex)
        })
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        loudness?.release(); loudness = null
        session.release()
        player.release()
        super.onDestroy()
    }

    private fun <T> background(action: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val account = app.credentials.activeId()
        scope.launch {
            try {
                val value = withContext(Dispatchers.IO) { action() }
                if (account != app.credentials.activeId()) throw IOException("Аккаунт изменился")
                result.set(value)
            }
            catch (error: Exception) { result.setException(IOException("Открой DedSec Music на телефоне и проверь подключение")) }
        }
        return result
    }

    private fun browsable(id: String, title: String, subtitle: String = "") = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setIsBrowsable(true).setIsPlayable(false).build()).build()

    private fun resolve(rawSong: Song, accountId: String): MediaItem {
        val song = LocalStore(this, accountId).contentRatedSong(rawSong)
        if (!allowed(song)) throw IOException(ContentPolicy.BLOCKED_MESSAGE)
        app.playbackSongs["$accountId:${song.id}"] = song
        return item(song, mixActive, currentMixMode, currentMixMood, queueSource).buildUpon().setUri(Uri.Builder().scheme("dedsec").authority(accountId).appendPath(song.id).build())
            .setMimeType(song.contentType).build()
    }

    private fun applyTrackVolume() {
        val song = player.currentMediaItem?.let(::songFromItem)
        val gain = song?.let { app.local()?.trackGain(it.id) } ?: 0
        player.volume = if (gain < 0) Math.pow(10.0, gain / 20.0).toFloat() else 1f
        try {
            if (gain <= 0) { loudness?.enabled = false; return }
            if (loudness == null && player.audioSessionId > 0) {
                loudness = android.media.audiofx.LoudnessEnhancer(player.audioSessionId)
            }
            loudness?.setTargetGain(gain * 100)
            loudness?.enabled = true
        } catch (_: RuntimeException) {
            loudness?.release(); loudness = null
            // Not every audio route offers the platform compressor. Never replace it with clipping PCM multiplication.
        }
    }

    private fun hydrateQueue() {
        if (hydration?.isActive == true) return
        val window = queueWindow ?: return
        val account = queueAccount ?: return
        hydration = scope.launch {
            while (window === queueWindow && account == app.credentials.activeId()) {
                val next = window.next()
                if (next.isNotEmpty()) player.addMediaItems(next.map { resolve(it, account) })
                else {
                    val previous = window.previous()
                    if (previous.isEmpty()) break
                    player.addMediaItems(0, previous.map { resolve(it, account) })
                }
                delay(30)
            }
        }
    }

    private fun loadArtwork(mediaItem: MediaItem) {
        artworkJob?.cancel()
        val song = songFromItem(mediaItem) ?: return
        val account = app.credentials.activeId() ?: return
        if (song.coverArt.isBlank()) return
        artworkJob = scope.launch {
            val bytes = try {
                val file = app.covers.file(account, song.coverArt, 192)
                withContext(Dispatchers.IO) { SessionArtwork.encode(file) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
            if (bytes != null && account == app.credentials.activeId() && player.currentMediaItem?.mediaId == song.id) {
                val current = player.currentMediaItem ?: return@launch
                player.replaceMediaItem(player.currentMediaItemIndex, current.buildUpon().setMediaMetadata(
                    current.mediaMetadata.buildUpon().setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build())
            }
        }
    }

    private fun prefetchArtwork() {
        val account = app.credentials.activeId() ?: return
        fun songAt(index: Int): Song? = if (index in 0 until player.mediaItemCount) songFromItem(player.getMediaItemAt(index)) else null
        val targets = neighborArtwork(songAt(player.currentMediaItemIndex), songAt(player.nextMediaItemIndex), songAt(player.previousMediaItemIndex))
        val key = account to targets
        if (key == prefetchKey) return
        prefetchKey = key
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            for (target in targets) {
                if (account != app.credentials.activeId()) break
                try {
                    val file = app.covers.file(account, target.coverArt, target.size)
                    app.images.execute(coil.request.ImageRequest.Builder(this@MusicService).data(file)
                        .memoryCacheKey("$account:${target.coverArt}:${target.size}").size(target.size).build())
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { /* Artwork failure never delays the next audio track. */ }
            }
        }
    }

    private suspend fun mixSongs(profile: ProfileApi, store: LocalStore, mode: MixMode, mood: MixMood, exclude: List<String> = emptyList()): List<Song> =
        profile.await { radio(mode, mood, store.history(), store.disliked(), exclude, childMode = store.childMode()) }.songs.filter { ContentPolicy.allowed(it, store.childMode()) }

    private fun extendMix() {
        if (extending || mixAccount != app.credentials.activeId()) return
        val profile = app.profileApi() ?: return
        val store = app.local() ?: return
        val mode = currentMixMode; val mood = currentMixMood
        extending = true
        val account = mixAccount
        val revision = playbackRevision
        mixExtension = scope.launch {
            try {
                if (account != app.credentials.activeId() || revision != playbackRevision) return@launch
                val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.takeLast(100)
                val more = withContext(Dispatchers.IO) {
                    mixSongs(profile, store, mode, mood, existing).map { resolve(it, account ?: throw IOException("Аккаунт изменился")) }
                }
                if (revision == playbackRevision && mixActive && account == app.credentials.activeId()) player.addMediaItems(more)
            } catch (_: Exception) { /* Continue the existing queue when the server is unavailable. */ }
            finally { if (revision == playbackRevision) extending = false }
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val revision = ++playbackRevision
            mixExtension?.cancel(); extending = false
            hydration?.cancel()
            artworkJob?.cancel()
            queueWindow = null; queueAccount = null; mixActive = false; mixAccount = null; queueSource = ""
            player.stop()
            player.clearMediaItems()
            val reference = mediaItems.singleOrNull()?.mediaId?.takeIf { it.startsWith("queue:") }
                ?: return background {
                    val account = app.credentials.activeId() ?: throw IOException("Войди в аккаунт")
                    val api = app.api() ?: throw IOException("Войди в аккаунт")
                    val wave = mediaItems.singleOrNull()?.mediaId == "wave"
                    val songs = if (wave) mixSongs(app.profileApi() ?: throw IOException("Войди в аккаунт"), app.local() ?: throw IOException("Войди в аккаунт"), MixMode.BALANCED, MixMood.ANY)
                    else mediaItems.map { entry ->
                        if (controller.uid == applicationInfo.uid) songFromItem(entry)
                            ?: Song.parse(api.call("getSong", "id" to entry.mediaId).getJSONObject("song"))
                        else app.local()?.offlineSong(entry.mediaId)
                            ?: entry.requestMetadata.searchQuery?.let { api.songs(it, count = 1).firstOrNull() }
                            ?: Song.parse(api.call("getSong", "id" to entry.mediaId).getJSONObject("song"))
                    }
                    val selection = ContentPolicy.selection(songs.map { app.local()?.contentRatedSong(it) ?: it }, startIndex.coerceAtLeast(0), app.local()?.childMode() ?: false)
                        ?: throw IOException(ContentPolicy.BLOCKED_MESSAGE)
                    withContext(Dispatchers.Main.immediate) {
                        if (revision != playbackRevision || account != app.credentials.activeId()) throw IOException("Очередь устарела")
                        if (wave) { mixActive = true; mixAccount = account; currentMixMode = MixMode.BALANCED; currentMixMood = MixMood.ANY; player.shuffleModeEnabled = false; player.repeatMode = Player.REPEAT_MODE_OFF }
                        MediaSession.MediaItemsWithStartPosition(selection.songs.map { resolve(it, account) }, selection.index, startPositionMs)
                    }
                }
            if (controller.uid != applicationInfo.uid) {
                return background { throw IOException("Очередь принадлежит приложению") }
            }
            return background {
                val account = app.credentials.activeId() ?: throw IOException("Войди в аккаунт")
                val request = app.playRequests.take(reference.removePrefix("queue:"), account) ?: throw IOException("Очередь устарела")
                val store = LocalStore(this@MusicService, account)
                val selection = ContentPolicy.selection(request.songs.map(store::contentRatedSong), request.selected, store.childMode())
                    ?: throw IOException(ContentPolicy.BLOCKED_MESSAGE)
                val window = QueueWindow(request.copy(songs = selection.songs, selected = selection.index))
                withContext(Dispatchers.Main.immediate) {
                    if (revision != playbackRevision || account != app.credentials.activeId()) throw IOException("Выбран другой трек")
                    queueWindow = window; queueAccount = account
                    queueSource = request.source
                    mixActive = request.mix; currentMixMode = request.mode; currentMixMood = request.mood; mixAccount = if (request.mix) account else null
                    if (request.mix) player.shuffleModeEnabled = false
                    if (request.mix || player.repeatMode == Player.REPEAT_MODE_ONE) player.repeatMode = Player.REPEAT_MODE_OFF
                    MediaSession.MediaItemsWithStartPosition(window.initial.map { resolve(it, account) }, window.initialIndex, 0)
                }
            }
        }
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val allowed = controller.uid == applicationInfo.uid || controller.isTrusted ||
                session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)
            if (!allowed) return MediaSession.ConnectionResult.reject()
            if (controller.uid != applicationInfo.uid) return super.onConnect(session, controller)
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(SHUFFLE_COMMAND, Bundle.EMPTY)).add(SessionCommand(NEXT_COMMAND, Bundle.EMPTY))
                .add(SessionCommand(PREVIOUS_COMMAND, Bundle.EMPTY)).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            val code = if (controller.uid != applicationInfo.uid) SessionError.ERROR_PERMISSION_DENIED else when (command.customAction) {
                SHUFFLE_COMMAND -> { if (!mixActive) player.shuffleModeEnabled = !player.shuffleModeEnabled; publishNavigation(); SessionResult.RESULT_SUCCESS }
                NEXT_COMMAND -> { sessionSkip(player, true); SessionResult.RESULT_SUCCESS }
                PREVIOUS_COMMAND -> { sessionSkip(player, false); SessionResult.RESULT_SUCCESS }
                else -> SessionError.ERROR_NOT_SUPPORTED
            }
            return com.google.common.util.concurrent.Futures.immediateFuture(SessionResult(code))
        }
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            future.set(LibraryResult.ofItem(browsable("root", "DedSec Music"), params))
            return future
        }
        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
                                   page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> = background {
            val api = app.api()
            val list = when {
                api == null -> listOf(browsable("login", "Войди в DedSec Music на телефоне"))
                parentId == "root" -> listOf(
                    MediaItem.Builder().setMediaId("wave").setMediaMetadata(MediaMetadata.Builder().setTitle("Моя волна")
                        .setSubtitle("Персональный микс из своей библиотеки").setIsBrowsable(false).setIsPlayable(true).build()).build(),
                    browsable("favorites", "Любимое"), browsable("playlists", "Мои плейлисты"), browsable("offline", "Скачанное"))
                parentId == "favorites" -> runCatching { api.favorites() }.getOrElse { app.local()?.cachedFavorites().orEmpty() }.filter(::allowed).map(::item)
                parentId == "offline" -> playable(app.local()?.offline().orEmpty()).map(::item)
                parentId == "playlists" -> api.playlists().map { browsable("playlist:${it.id}", it.name, "${it.songCount} треков") }
                parentId.startsWith("playlist:") -> api.playlist(parentId.removePrefix("playlist:")).let { list -> list.songs.filter(::allowed).map { item(it, source = collectionSource("playlist", list.playlist.name)) } }
                else -> emptyList()
            }
            LibraryResult.ofItemList(list.drop(page * pageSize).take(pageSize), params)
        }
        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = background {
            val section = when (mediaId) {
                "root" -> browsable("root", "DedSec Music")
                "favorites" -> browsable("favorites", "Любимое")
                "playlists" -> browsable("playlists", "Мои плейлисты")
                "offline" -> browsable("offline", "Скачанное")
                "wave" -> MediaItem.Builder().setMediaId("wave").setMediaMetadata(MediaMetadata.Builder()
                    .setTitle("Моя волна").setIsBrowsable(false).setIsPlayable(true).build()).build()
                else -> null
            }
            if (section != null) return@background LibraryResult.ofItem(section, null)
            val song = app.local()?.cachedSongs()?.find { it.id == mediaId }
                ?: app.api()?.call("getSong", "id" to mediaId)?.getJSONObject("song")?.let(Song::parse)
            if (song == null || !allowed(song)) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) else LibraryResult.ofItem(item(song), null)
        }
        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
            val profile = app.profileApi(); val store = app.local(); val account = app.credentials.activeId()
            val api = app.api()
            val revision = ++playbackRevision
            mixExtension?.cancel(); extending = false; hydration?.cancel()
            val wave = mediaItems.any { it.mediaId == "wave" }
            val extras = mediaItems.firstOrNull()?.mediaMetadata?.extras
            val mode = if (wave) MixMode.BALANCED else runCatching { MixMode.valueOf(extras?.getString("mixMode").orEmpty()) }.getOrDefault(MixMode.BALANCED)
            val mood = if (wave) MixMood.ANY else runCatching { MixMood.valueOf(extras?.getString("mixMood").orEmpty()) }.getOrDefault(MixMood.ANY)
            return background {
                if (account == null || api == null || account != app.credentials.activeId() || revision != playbackRevision) throw IOException("Очередь устарела")
                val songs = if (wave) mixSongs(profile ?: throw IOException("Войди в аккаунт"), store ?: throw IOException("Войди в аккаунт"), mode, mood)
                else mediaItems.mapNotNull { entry ->
                    entry.takeIf { controller.uid == applicationInfo.uid }?.let(::songFromItem)
                        ?: store?.offlineSong(entry.mediaId)
                        ?: entry.requestMetadata.searchQuery?.let { api.songs(it, count = 1).firstOrNull() }
                        ?: runCatching { Song.parse(api.call("getSong", "id" to entry.mediaId).getJSONObject("song")) }.getOrNull()
                }
                withContext(Dispatchers.Main.immediate) {
                    if (account != app.credentials.activeId() || revision != playbackRevision) throw IOException("Очередь устарела")
                    if (wave && songs.isEmpty()) throw IOException("Нет подходящих треков для волны")
                    queueWindow = null; queueAccount = null
                    queueSource = if (wave) "" else extras?.getString("playbackSource").orEmpty()
                    mixActive = wave || extras?.getBoolean("mix") == true
                    if (mixActive) player.shuffleModeEnabled = false
                    if (mixActive || player.repeatMode == Player.REPEAT_MODE_ONE) player.repeatMode = Player.REPEAT_MODE_OFF
                    currentMixMode = mode; currentMixMood = mood; mixAccount = if (mixActive) account else null
                    val safe = playable(songs)
                    if (safe.isEmpty() && songs.isNotEmpty()) throw IOException(ContentPolicy.BLOCKED_MESSAGE)
                    safe.map { resolve(it, account) }.toMutableList()
                }
            }
        }
        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, 100, params)
            val future = SettableFuture.create<LibraryResult<Void>>()
            future.set(LibraryResult.ofVoid())
            return future
        }
        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
                                       page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> = background {
            LibraryResult.ofItemList(app.api()?.songs(query, page * pageSize, pageSize)?.filter(::allowed)?.map(::item).orEmpty(), params)
        }
    }

    companion object {
        fun item(song: Song, mix: Boolean = false, mixMode: MixMode = MixMode.BALANCED, mixMood: MixMood = MixMood.ANY, source: String = ""): MediaItem = MediaItem.Builder().setMediaId(song.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setDisplayTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album).setDurationMs(song.duration * 1000L)
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply { putString("song", song.json().toString()); putBoolean("mix", mix); putString("mixMode", mixMode.name); putString("mixMood", mixMood.name); putString("playbackSource", source) }).build()).build()
        fun songFromItem(item: MediaItem): Song? = item.mediaMetadata.extras?.getString("song")?.let {
            runCatching { Song.parse(org.json.JSONObject(it)) }.getOrNull()
        }
    }
    private class DataSourceFactory(private val app: MusicApp) : androidx.media3.datasource.DataSource.Factory {
        override fun createDataSource(): androidx.media3.datasource.DataSource = AccountDataSource(app)
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = read(chunk)
            if (count < 0) break
            if (buffer.size() + count > limit) throw IOException("Обложка слишком большая")
            buffer.write(chunk, 0, count)
        }
        return buffer.toByteArray()
    }
}
