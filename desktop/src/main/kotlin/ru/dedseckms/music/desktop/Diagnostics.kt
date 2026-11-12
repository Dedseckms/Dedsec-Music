@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package ru.dedseckms.music.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** Renders the production composables with sample data, without reading or capturing a desktop. */
fun renderGallery(output: Path) = runBlocking(Dispatchers.Main) {
    Files.createDirectories(output)
    val controller = DesktopController(CoroutineScope(SupervisorJob() + Dispatchers.Main), DesktopPreferences(output.resolve(".fixture-preferences")))
    controller.demo()
    try {
        for (screen in listOf("desktop-home", "desktop-library", "desktop-settings", "desktop-search", "desktop-import", "desktop-light", "desktop-glass", "desktop-amoled", "desktop-compact")) {
            controller.theme(true, "blue"); controller.style("material"); controller.amoled(false); controller.tab("Главная")
            when (screen) {
                "desktop-library" -> { controller.tab("Музыка"); controller.filter("Альбомы") }
                "desktop-settings" -> controller.tab("Настройки")
                "desktop-search" -> controller.tab("Поиск")
                "desktop-import" -> controller.tab("Добавить")
                "desktop-light" -> { controller.theme(false, "rose") }
                "desktop-glass" -> { controller.style("glass"); controller.theme(true, "violet") }
                "desktop-amoled" -> { controller.amoled(true); controller.theme(true, "mint") }
                "desktop-compact" -> { controller.tab("Главная"); controller.theme(true, "blue") }
            }
            val scene = ImageComposeScene(width = if (screen == "desktop-compact") 960 else 1440, height = if (screen == "desktop-compact") 600 else 900, density = Density(1f), coroutineContext = coroutineContext) { DesktopApp(controller) }
            try {
                repeat(5) { scene.render(it * 100_000_000L).close(); yield() }
                scene.render(1_000_000_000L).use { image ->
                    image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.use { data -> Files.write(output.resolve("$screen.png"), data.bytes) }
                }
            } finally { scene.close() }
        }
        println("Nine production UI screenshots rendered with demo data, including glass, search and 960x600.")
    } finally { controller.close() }
}

/** Exercises bundled libVLC using a generated silent WAV, without network or account credentials. */
fun audioSmoke() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val finished = java.util.concurrent.atomic.AtomicInteger()
    val advanced = java.util.concurrent.CopyOnWriteArrayList<String>()
    val player = DesktopAudio(scope, { finished.incrementAndGet() }, silent = true, advanced = advanced::add)
    val file = Files.createTempFile("dedsec-audio-", ".wav")
    val longFile = Files.createTempFile("dedsec-crossfade-", ".wav")
    try {
        fun wav(target: Path, seconds: Int) {
            val length = 44100 * 2 * seconds
            val bytes = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(36 + length).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(length)
            Files.write(target, bytes.array())
        }
        wav(file, 2); wav(longFile, 12)
        withTimeout(20_000) {
            player.start(file.toUri().toString())
            while (!player.state.value.playing || player.state.value.position < 250) delay(50)
            check(player.state.value.duration in 1900..2100) { "Unexpected audio duration" }
            player.toggle(); delay(400); check(!player.state.value.playing) { "Pause failed" }
            player.seek(1400); player.toggle()
            while (finished.get() == 0) delay(50)
            player.start(file.toUri().toString()); delay(500); check(player.state.value.playing) { "Replay failed" }
        }
        withTimeout(25_000) {
            val uri = longFile.toUri().toString()
            player.start(uri, "first"); player.prepareNext("second", uri)
            while (!player.state.value.prepared || !player.state.value.playing) delay(50)
            check(advanced.isEmpty()) { "Preloading advanced the queue" }
            player.seek(9700)
            while (advanced.isEmpty()) delay(25)
            check(advanced.single() == "second" && player.state.value.crossfading) { "Crossfade did not promote the prepared track" }
            player.toggle(); delay(300)
            check(!player.state.value.playing) { "Crossfade pause failed" }
            val paused = player.state.value.position; delay(250)
            check(kotlin.math.abs(player.state.value.position - paused) < 100) { "Paused crossfade kept advancing" }
            player.toggle()
            while (player.state.value.crossfading || !player.state.value.playing) delay(50)
            check(advanced.size == 1) { "Crossfade advanced the queue more than once" }
            player.prepareNext("third", uri)
            while (!player.state.value.prepared) delay(50)
            player.stop(preservePrepared = true); player.start(uri, "third")
            while (!player.state.value.playing || player.state.value.position < 100) delay(25)
            check(player.state.value.position < 2000) { "Manual skip did not rewind the prepared track" }
            player.seek(5000)
            while (player.state.value.position < 4800) delay(25)
            check(!player.state.value.crossfading && !player.state.value.error) { "Seek after handoff failed" }
            player.stop(); delay(200)
            check(!player.state.value.playing && !player.state.value.prepared) { "Stop retained playback" }
        }
        println("VLC smoke OK: decode, pause, seek, completion, replay, preload, crossfade, paused crossfade and manual handoff.")
    } finally { scope.cancel(); player.close(); Files.deleteIfExists(file); Files.deleteIfExists(longFile) }
}
