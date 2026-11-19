@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package ru.dedseckms.music.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Surface
import kotlinx.coroutines.*
import ru.dedseckms.music.data.*
import java.nio.file.Files
import java.nio.file.Path

/** Production dashboard layout rendered with synthetic metrics, without contacting a server. */
fun renderDashboardPreview(output: Path) = runBlocking(Dispatchers.Main) {
    Files.createDirectories(output)
    val session = ServerAdminSession(this, ProfileApi(Credentials("https://example.invalid", "preview", "preview-only")), autoRefresh = false)
    val total = 8L * 1073741824
    val samples = (0..60).map { n -> SystemSample(1_800_000_000L + n * 5, 19 + 12 * kotlin.math.sin(n / 4.0), total, total / 3 + n * 1024 * 1024,
        total * 2 / 3, 0, .4, .3, .2, 400_000 + 200_000 * kotlin.math.sin(n / 5.0), 120_000 + 70_000 * kotlin.math.cos(n / 7.0)) }
    val system = ServerSystem(samples.last().at, "host", 2, 420_000, samples.last(), samples,
        listOf(ServerProcess("1", 1245, "navidrome", 4.2, 95_000_000), ServerProcess("2", 1246, "python3", 1.1, 80_000_000)), 146)
    val state = ServerAdminState(system = system, storage = ServerStorage(listOf(StorageVolume("main", "Музыкальная библиотека", "/srv/music", 250_000_000_000, 80_000_000_000, 160_000_000_000, false, false, "")), emptyList(), null), loading = false)
    try { for ((name, width, height) in listOf(Triple("server-dashboard-wide", 1100, 820), Triple("server-dashboard-phone", 412, 900))) {
        val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xffA8C7FA), primaryContainer = androidx.compose.ui.graphics.Color(0xff28466D), tertiary = androidx.compose.ui.graphics.Color(0xffB5D9C0))) { Surface { DashboardBody(session, state) {} } }
        }
        try { repeat(4) { scene.render(it * 100_000_000L).close(); yield() }; scene.render(1_000_000_000L).use { image -> image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.use { Files.write(output.resolve("$name.png"), it.bytes) } } }
        finally { scene.close() }
    } } finally { session.close() }
    println("Dashboard previews rendered: wide and phone; synthetic data")
}
