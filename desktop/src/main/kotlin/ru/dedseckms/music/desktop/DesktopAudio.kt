package ru.dedseckms.music.desktop

import com.sun.jna.NativeLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.cos
import kotlin.math.sin

data class AudioState(val playing: Boolean = false, val buffering: Boolean = false, val position: Long = 0,
    val duration: Long = 0, val error: Boolean = false, val crossfading: Boolean = false, val prepared: Boolean = false)

internal fun crossfadeGains(fraction: Double): Pair<Double, Double> {
    val angle = fraction.coerceIn(0.0, 1.0) * Math.PI / 2
    return cos(angle) to sin(angle)
}

/** Both decoders and their network URLs stay in this process. All libVLC calls run on one audio thread. */
class DesktopAudio(private val scope: CoroutineScope, private val ended: () -> Unit, private val silent: Boolean = false,
    private val advanced: (String) -> Unit = {}) : AutoCloseable {
    private class Deck(val player: MediaPlayer) {
        var key = ""; var warming = false; var ready = false; var paused = false
        var started = 0L; var finished = false
        var volume = -1
    }
    private data class Next(val key: String, val uri: String)
    private data class Fade(val from: Deck, val to: Deck, var requested: Long, var started: Long = 0L)
    private var factory: MediaPlayerFactory? = null
    private var active: Deck? = null
    private var spare: Deck? = null
    private var next: Next? = null
    private var fade: Fade? = null
    private val audioThread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "music-audio").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val mutable = MutableStateFlow(AudioState())
    val state = mutable.asStateFlow()
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    private var desiredVolume = .8f
    private var suspendedAt = 0L
    private val crossfadeMs = 2500L

    private fun ensureEngine() {
        if (factory != null) return
        val root = System.getProperty("compose.application.resources.dir")?.let(Path::of)
            ?: Path.of(System.getProperty("user.dir"), ".tools/desktop-resources/windows")
        val native = root.resolve("vlc")
        check(Files.isRegularFile(native.resolve("libvlc.dll"))) { "Встроенный аудиодвижок не найден. Распакуй сборку целиком" }
        NativeLibrary.addSearchPath("libvlc", native.toString())
        System.load(native.resolve("libvlccore.dll").toAbsolutePath().toString())
        System.load(native.resolve("libvlc.dll").toAbsolutePath().toString())
        factory = MediaPlayerFactory(null as uk.co.caprica.vlcj.factory.discovery.NativeDiscovery?, "--no-video", "--quiet",
            "--no-metadata-network-access", "--no-media-library", if (silent) "--aout=dummy" else "--aout=mmdevice")
        fun deck(): Deck = Deck(factory!!.mediaPlayers().newMediaPlayer()).also { slot ->
            slot.player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) {
                    scope.launch(audioThread) {
                        if (closed || slot.player.status().state().name != "PLAYING") return@launch
                        if (slot.warming) {
                            // Prime the output silently, then rewind before declaring this decoder ready.
                            slot.player.audio().setVolume(0); slot.player.controls().setPause(true); slot.player.controls().setTime(0)
                            slot.warming = false
                        } else if (slot === active && slot.started == 0L) slot.started = System.nanoTime()
                    }
                }
                override fun finished(mediaPlayer: MediaPlayer) {
                    scope.launch(audioThread) {
                        if (closed || slot !== active || slot.finished || slot.player.status().state().name != "ENDED") return@launch
                        slot.finished = true; val version = generation
                        scope.launch { if (version == generation && !closed) ended() }
                    }
                }
                override fun error(mediaPlayer: MediaPlayer) {
                    scope.launch(audioThread) {
                        if (!closed && slot === active && slot.player.status().state().name == "ERROR")
                            mutable.value = mutable.value.copy(playing = false, buffering = false, error = true)
                    }
                }
            })
        }
        active = deck(); spare = deck()
        scope.launch(audioThread) { while (isActive && !closed) { tick(); delay(40) } }
    }
    private fun reset(slot: Deck) {
        slot.player.controls().stop(); slot.key = ""; slot.warming = false; slot.ready = false
        slot.started = 0; slot.finished = false; slot.paused = false; slot.volume = -1
    }
    suspend fun start(uri: String, key: String = "") {
        val version = ++generation; mutable.value = AudioState(buffering = true)
        withContext(audioThread) {
            if (closed || version != generation) return@withContext
            ensureEngine(); suspendedAt = 0
            fade?.let { transition -> if (transition.from !== active) reset(transition.from) }
            fade = null; next = null
            val candidate = spare!!
            if (key.isNotEmpty() && candidate.ready && candidate.key == key) {
                val old = active!!; reset(old); active = candidate; spare = old
                candidate.ready = false; candidate.started = 0; candidate.paused = false; candidate.finished = false
                candidate.player.audio().setVolume(0); candidate.player.controls().setPause(false)
            } else {
                reset(candidate); val slot = active!!; reset(slot); slot.key = key
                slot.player.audio().setVolume(0)
                check(slot.player.media().play(uri, ":network-caching=1200", ":no-video")) { "Не удалось открыть аудиофайл" }
                slot.player.audio().setVolume(0)
            }
        }
    }
    suspend fun prepareNext(key: String, uri: String) = withContext(audioThread) {
        if (closed || active == null) return@withContext
        if (next?.key == key || spare?.key == key && (spare?.ready == true || spare?.warming == true)) return@withContext
        next = Next(key, uri)
        if (fade == null) warmNext()
    }
    private fun warmNext() {
        val target = next ?: return; val slot = spare ?: return
        reset(slot); slot.key = target.key; slot.warming = true; next = null
        slot.player.audio().setVolume(0)
        if (!slot.player.media().play(target.uri, ":network-caching=1200", ":no-video")) reset(slot)
        else slot.player.audio().setVolume(0)
    }
    fun clearNext() { scope.launch(audioThread) { next = null; if (fade == null) spare?.let(::reset) } }
    private fun tick() {
        val current = active ?: return
        val clock = System.nanoTime()
        val staged = spare!!
        if (!staged.warming && !staged.ready && staged.key.isNotEmpty() && fade == null && staged.player.status().state().name == "PAUSED" && staged.player.status().time() in 0..40) staged.ready = true
        val transition = fade
        if (transition != null) {
            if (suspendedAt == 0L && transition.started == 0L && transition.to.player.status().isPlaying) {
                transition.started = clock; active = transition.to; spare = transition.from
                transition.to.ready = false; transition.to.started = clock
                val version = generation; val key = transition.to.key
                scope.launch { if (version == generation && !closed) advanced(key) }
            }
            if (suspendedAt == 0L) {
                if (transition.started != 0L) {
                    val elapsed = (clock - transition.started) / 1_000_000
                    val (old, fresh) = crossfadeGains(elapsed.toDouble() / crossfadeMs)
                    applyVolume(transition.from, old); applyVolume(transition.to, fresh)
                    if (elapsed >= crossfadeMs) { reset(transition.from); fade = null; warmNext() }
                } else if ((clock - transition.requested) / 1_000_000 > 2000 || transition.to.player.status().state().name == "ERROR") {
                    reset(transition.to); fade = null
                }
            }
        } else if (suspendedAt == 0L) {
            if (current.player.status().isPlaying) {
                if (current.started == 0L) current.started = clock
                applyVolume(current, ((clock - current.started) / 150_000_000.0).coerceIn(0.0, 1.0))
                val length = current.player.status().length(); val remaining = length - current.player.status().time()
                if (length >= 8000 && remaining in 1..crossfadeMs && staged.ready) {
                    staged.player.audio().setVolume(0); staged.player.controls().setPause(false)
                    fade = Fade(current, staged, clock)
                }
            }
        }
        val slot = active!!; val media = slot.player
        if (!mutable.value.error) mutable.value = AudioState(media.status().isPlaying, media.status().state().name in setOf("OPENING", "BUFFERING"),
            media.status().time().coerceAtLeast(0), media.status().length().coerceAtLeast(0), crossfading = fade?.started != null && fade?.started != 0L,
            prepared = spare?.ready == true)
    }
    private fun applyVolume(slot: Deck, gain: Double) {
        val value = (desiredVolume * gain * 100).toInt().coerceIn(0, 100)
        if (slot.volume != value) { slot.player.audio().setVolume(value); slot.volume = value }
    }
    fun toggle() { scope.launch(audioThread) {
        val current = active ?: return@launch
        if (suspendedAt == 0L && (current.player.status().isPlaying || fade != null)) {
            suspendedAt = System.nanoTime(); current.player.controls().setPause(true); fade?.let { it.from.player.controls().setPause(true); it.to.player.controls().setPause(true) }
        } else {
            val shift = if (suspendedAt == 0L) 0 else System.nanoTime() - suspendedAt
            fade?.let { it.requested += shift; if (it.started != 0L) it.started += shift; it.from.player.controls().setPause(false); it.to.player.controls().setPause(false) }
                ?: current.player.controls().setPause(false)
            suspendedAt = 0
        }
    } }
    fun seek(time: Long) { scope.launch(audioThread) {
        fade?.let { if (it.from !== active) reset(it.from) else reset(it.to) }; fade = null
        active?.player?.controls()?.setTime(time.coerceAtLeast(0)); active?.let { applyVolume(it, 1.0) }
    } }
    fun volume(value: Float) { scope.launch(audioThread) { desiredVolume = value.coerceIn(0f, 1f); if (fade == null) active?.let { applyVolume(it, 1.0) } } }
    fun stop(preservePrepared: Boolean = false) {
        generation++; scope.launch(audioThread) {
            fade?.let { if (it.from !== active) reset(it.from) else reset(it.to) }; fade = null
            active?.let(::reset); if (!preservePrepared) { spare?.let(::reset); next = null }
            suspendedAt = 0; mutable.value = AudioState()
        }
    }
    override fun close() {
        closed = true; generation++
        runBlocking(audioThread) { active?.player?.release(); spare?.player?.release(); active = null; spare = null; factory?.release(); factory = null }
        audioThread.close()
    }
}
