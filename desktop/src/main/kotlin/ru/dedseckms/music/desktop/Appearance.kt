package ru.dedseckms.music.desktop

import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*

object DesktopBuild { const val VERSION = "0.3.2" }

data class Appearance(val dark: Boolean = true, val palette: String = "blue", val style: String = "material", val amoled: Boolean = false) {
    fun validated() = copy(palette = palette.takeIf { it in PALETTES } ?: "blue", style = style.takeIf { it in STYLES } ?: "material", amoled = dark && amoled)
    companion object {
        val PALETTES = listOf("blue", "violet", "mint", "rose", "amber", "lagoon")
        val STYLES = listOf("material", "glass", "aurora", "minimal")
    }
}

/** Small local preferences file, kept separate from account credentials and demo rendering. */
class DesktopPreferences(private val directory: Path = DesktopPaths.root) {
    fun read(): Appearance = runCatching {
        val path = directory.resolve("appearance.json")
        require(Files.size(path) <= 4096)
        val json = JSONObject(Files.readString(path))
        Appearance(json.optBoolean("dark", true), json.optString("palette", "blue"), json.optString("style", "material"), json.optBoolean("amoled")).validated()
    }.getOrDefault(Appearance())
    fun write(value: Appearance) {
        val a = value.validated(); Files.createDirectories(directory)
        val part = Files.createTempFile(directory, "appearance-", ".tmp")
        try {
            Files.writeString(part, JSONObject().put("dark", a.dark).put("palette", a.palette).put("style", a.style).put("amoled", a.amoled).toString())
            Files.move(part, directory.resolve("appearance.json"), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(part) }
    }
}

internal fun appearanceColors(value: Appearance): ColorScheme {
    val a = value.validated()
    val seed = when (a.palette) {
        "violet" -> Color(0xFFC3A6FF); "mint" -> Color(0xFF8EDBBE); "rose" -> Color(0xFFFFAFCD)
        "amber" -> Color(0xFFF5C778); "lagoon" -> Color(0xFF74DAE2); else -> Color(0xFFA8C8FF)
    }
    val glass = a.style == "glass"
    if (a.dark) {
        val base = if (a.amoled) Color.Black else if (a.style == "minimal") Color(0xFF101114) else lerp(Color(0xFF0E1117), seed, .025f)
        val surface = lerp(base, seed, if (a.style == "aurora") .13f else .065f)
        val container = lerp(base, seed, if (a.style == "aurora") .20f else .12f)
        return darkColorScheme(primary = seed, onPrimary = Color(0xFF102035), primaryContainer = lerp(base, seed, .28f), onPrimaryContainer = lerp(seed, Color.White, .55f),
            secondary = lerp(seed, Color.White, .25f), secondaryContainer = lerp(base, seed, .18f), onSecondaryContainer = Color(0xFFE4E8F2),
            tertiary = lerp(seed, Color(0xFFDAA9FF), .55f), tertiaryContainer = lerp(base, seed, .22f), onTertiaryContainer = Color(0xFFF2E8FF),
            background = base, surface = surface.copy(alpha = if (glass) .52f else 1f),
            surfaceContainer = container.copy(alpha = if (glass) .48f else 1f), surfaceContainerLow = surface.copy(alpha = if (glass) .38f else 1f),
            surfaceContainerHigh = lerp(base, seed, .18f).copy(alpha = if (glass) .48f else 1f),
            onSurface = Color(0xFFE7EAF1), onSurfaceVariant = lerp(Color(0xFFA5ADBD), seed, .12f), outline = lerp(base, seed, .38f), outlineVariant = lerp(base, seed, .22f))
    }
    val primary = lerp(seed, Color.Black, .52f)
    val background = if (a.style == "minimal") Color(0xFFF6F6F7) else lerp(Color.White, seed, .12f)
    val surface = lerp(Color.White, seed, .055f)
    return lightColorScheme(primary = primary, onPrimary = Color.White, primaryContainer = lerp(Color.White, seed, .55f), onPrimaryContainer = lerp(seed, Color.Black, .75f),
        secondary = lerp(seed, Color.Black, .55f), secondaryContainer = lerp(Color.White, seed, .35f), onSecondaryContainer = Color(0xFF243043),
        tertiary = lerp(primary, Color(0xFF6E4187), .45f), tertiaryContainer = lerp(Color.White, seed, .40f), onTertiaryContainer = Color(0xFF33263D),
        background = background, surface = surface.copy(alpha = if (glass) .68f else 1f),
        surfaceContainer = lerp(Color.White, seed, .22f).copy(alpha = if (glass) .48f else 1f), surfaceContainerLow = surface.copy(alpha = if (glass) .55f else 1f),
        surfaceContainerHigh = lerp(Color.White, seed, .31f).copy(alpha = if (glass) .60f else 1f),
        onSurface = Color(0xFF1B2432), onSurfaceVariant = Color(0xFF536073), outline = lerp(primary, Color.White, .35f), outlineVariant = lerp(Color.White, seed, .50f))
}
