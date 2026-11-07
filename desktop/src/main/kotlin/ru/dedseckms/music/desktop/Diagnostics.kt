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
    val player = DesktopAudio(scope, { finished.incrementAndGet() }, silent = true)
    val file = Files.createTempFile("dedsec-audio-", ".wav")
    try {
        val length = 44100 * 2 * 2
        val wav = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + length).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(length)
        Files.write(file, wav.array())
        withTimeout(20_000) {
            player.start(file.toUri().toString())
            while (!player.state.value.playing || player.state.value.position < 250) delay(50)
            check(player.state.value.duration in 1900..2100) { "Unexpected audio duration" }
            player.toggle(); delay(400); check(!player.state.value.playing) { "Pause failed" }
            player.seek(1400); player.toggle()
            while (finished.get() == 0) delay(50)
            player.start(file.toUri().toString()); delay(500); check(player.state.value.playing) { "Replay failed" }
        }
        println("VLC smoke OK: decode, duration, progress, pause, seek, completion and replay.")
    } finally { scope.cancel(); player.close(); Files.deleteIfExists(file) }
}
