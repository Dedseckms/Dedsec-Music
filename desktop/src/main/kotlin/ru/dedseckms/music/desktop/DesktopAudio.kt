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

data class AudioState(val playing: Boolean = false, val buffering: Boolean = false, val position: Long = 0, val duration: Long = 0, val error: Boolean = false)

/** URLs are passed to libVLC in memory, never to a child process or a command line. */
class DesktopAudio(private val scope: CoroutineScope, private val ended: () -> Unit, private val silent: Boolean = false) : AutoCloseable {
    private var factory: MediaPlayerFactory? = null
    private var player: MediaPlayer? = null
    private val audioThread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "music-audio").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val mutable = MutableStateFlow(AudioState())
    val state = mutable.asStateFlow()
    @Volatile private var generation = 0
    private var desiredVolume = .8f
    suspend fun start(uri: String) {
        val revision = ++generation
        mutable.value = AudioState(buffering = true)
        withContext(audioThread) {
            if (player == null) {
                val root = System.getProperty("compose.application.resources.dir")?.let(Path::of)
                    ?: Path.of(System.getProperty("user.dir"), ".tools/desktop-resources/windows")
                val native = root.resolve("vlc")
                if (!Files.isRegularFile(native.resolve("libvlc.dll"))) error("Встроенный аудиодвижок не найден. Распакуй сборку целиком")
                NativeLibrary.addSearchPath("libvlc", native.toString())
                System.load(native.resolve("libvlccore.dll").toAbsolutePath().toString())
                System.load(native.resolve("libvlc.dll").toAbsolutePath().toString())
                // Use only the verified bundled library; do not scan the machine for VLC.
                factory = MediaPlayerFactory(null as uk.co.caprica.vlcj.factory.discovery.NativeDiscovery?, "--no-video", "--quiet", "--no-metadata-network-access", "--no-media-library", if (silent) "--aout=dummy" else "--aout=any")
                player = factory!!.mediaPlayers().newMediaPlayer().also { media ->
                    media.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                        override fun finished(mediaPlayer: MediaPlayer) { val event = generation; scope.launch { val finished = withContext(audioThread) { player?.status()?.state()?.name == "ENDED" }; if (event == generation && finished) ended() } }
                        override fun error(mediaPlayer: MediaPlayer) { val event = generation; scope.launch(audioThread) { if (event == generation && player?.status()?.state()?.name == "ERROR") mutable.value = mutable.value.copy(playing = false, buffering = false, error = true) } }
                    })
                }
                scope.launch {
                    while (isActive) {
                        delay(250)
                        withContext(audioThread) { player?.let { p ->
                            if (!mutable.value.error) mutable.value = AudioState(p.status().isPlaying, p.status().state().name in setOf("OPENING", "BUFFERING"), p.status().time().coerceAtLeast(0), p.status().length().coerceAtLeast(0))
                        } }
                    }
                }
            }
            if (revision != generation) return@withContext
            player!!.controls().stop()
            player!!.audio().setVolume((desiredVolume * 100).toInt())
            if (!player!!.media().play(uri, ":network-caching=800", ":no-video")) error("Не удалось открыть аудиофайл")
        }
    }
    fun toggle() { scope.launch(audioThread) { player?.controls()?.pause() } }
    fun seek(time: Long) { scope.launch(audioThread) { player?.controls()?.setTime(time) } }
    fun volume(value: Float) { desiredVolume = value; scope.launch(audioThread) { player?.audio()?.setVolume((value * 100).toInt().coerceIn(0, 100)) } }
    fun stop() { generation++; scope.launch(audioThread) { player?.controls()?.stop(); mutable.value = AudioState() } }
    override fun close() {
        generation++
        runBlocking(audioThread) { player?.release(); player = null; factory?.release(); factory = null }
        audioThread.close()
    }
}
