package ru.dedseckms.music.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test
import ru.dedseckms.music.ui.*

class AppearanceTest {
    @Test fun amoledUsesTrueBlackAcrossEveryStyle() {
        listOf("material", "glass", "aurora", "mono").forEach { style ->
            val scheme = styledScheme(accentScheme(true, "blue"), style, true)
            assertEquals(Color.Black, scheme.background)
            assertEquals(Color.Black, scheme.surface)
            assertEquals(Color.Black, scheme.surfaceContainer)
            assertTrue(scheme.onSurface.luminance() > .5f)
        }
    }
    @Test fun everyManualPaletteKeepsPrimaryButtonTextReadable() {
        AccentColors.keys.forEach { palette -> listOf(false, true).forEach { dark ->
            val scheme = accentScheme(dark, palette)
            val a = scheme.primary.luminance(); val b = scheme.onPrimary.luminance()
            val ratio = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
            assertTrue("Unreadable $palette dark=$dark ratio=$ratio", ratio >= 4.5f)
        } }
    }
    @Test fun glassIsTranslucentAndMonoRemovesThePrimaryColorAccent() {
        val base = accentScheme(true, "rose")
        assertTrue(styledScheme(base, "glass", false).surfaceContainer.alpha < 1f)
        assertEquals(base.onSurface, styledScheme(base, "mono", false).primary)
    }
}
