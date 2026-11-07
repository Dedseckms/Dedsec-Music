package ru.dedseckms.music

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.dedseckms.music.ui.ThemeBackdrop
import ru.dedseckms.music.ui.accentScheme

class ThemeContentTest {
    @get:Rule val compose = createComposeRule()
    @Test fun transparentRootKeepsLightTextOnADarkBackground() {
        val scheme = accentScheme(true, "blue")
        var observed = Color.Unspecified
        compose.setContent {
            MaterialTheme(colorScheme = scheme) { ThemeBackdrop(false) { observed = LocalContentColor.current; Text("Visible text") } }
        }
        compose.runOnIdle { assertEquals(scheme.onBackground, observed) }
    }
}
