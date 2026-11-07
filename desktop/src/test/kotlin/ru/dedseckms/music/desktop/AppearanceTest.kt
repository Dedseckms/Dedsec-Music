package ru.dedseckms.music.desktop

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class AppearanceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun eachPaletteChangesContainersAndPrimaryInBothModes() {
        for (dark in listOf(false, true)) {
            val schemes = Appearance.PALETTES.map { appearanceColors(Appearance(dark = dark, palette = it)) }
            assertEquals(6, schemes.map { it.primary }.distinct().size)
            assertEquals(6, schemes.map { it.primaryContainer }.distinct().size)
            assertEquals(6, schemes.map { it.surfaceContainer }.distinct().size)
        }
    }
    @Test fun glassUsesTranslucentSurfacesAndAmoledUsesBlackBackground() {
        for (dark in listOf(false, true)) {
            assertTrue(appearanceColors(Appearance(dark = dark, style = "glass")).surfaceContainerLow.alpha < .8f)
            assertEquals(1f, appearanceColors(Appearance(dark = dark, style = "material")).surfaceContainerLow.alpha)
        }
        assertEquals(Color.Black, appearanceColors(Appearance(amoled = true)).background)
        assertNotEquals(appearanceColors(Appearance()).surfaceContainer, appearanceColors(Appearance(style = "aurora")).surfaceContainer)
    }
    @Test fun preferencesSurviveANewInstanceAndRejectUnknownStyleNames() {
        val directory = temp.newFolder().toPath()
        val saved = Appearance(dark = true, palette = "lagoon", style = "glass", amoled = true)
        DesktopPreferences(directory).write(saved)
        assertEquals(saved, DesktopPreferences(directory).read())
        DesktopPreferences(directory).write(Appearance(dark = false, palette = "unknown", style = "unknown", amoled = true))
        assertEquals(Appearance(dark = false), DesktopPreferences(directory).read())
    }
    @Test fun demoAppearanceChangesDoNotOverwriteUserPreferences() {
        val preferences = DesktopPreferences(temp.newFolder().toPath())
        val original = Appearance(palette = "mint", style = "minimal")
        preferences.write(original)
        val c = DesktopController(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), preferences)
        try {
            c.demo(); c.style("glass"); c.theme(false, "rose")
            assertEquals("glass", c.state.value.style); assertFalse(c.state.value.dark)
            assertEquals(original, preferences.read())
        } finally { c.close() }
    }
}
