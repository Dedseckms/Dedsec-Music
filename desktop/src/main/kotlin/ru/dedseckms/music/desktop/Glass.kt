package ru.dedseckms.music.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

internal val LocalAppearance = staticCompositionLocalOf { Appearance() }

/** Soft radial fields remain behind translucent panels, rather than blurring the controls themselves. */
@Composable internal fun AppearanceBackdrop() {
    val a = LocalAppearance.current
    if (a.amoled || a.style !in listOf("glass", "aurora")) return
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxSize()) {
        val intensity = if (a.dark) .50f else .33f
        val radius = size.maxDimension * .57f
        for ((center, color) in listOf(Offset(size.width * .08f, size.height * .16f) to primary,
            Offset(size.width * .78f, size.height * .25f) to tertiary, Offset(size.width * .54f, size.height * 1.06f) to Color(0xFF58CFD0))) {
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = intensity), color.copy(alpha = .10f), Color.Transparent), center, radius), radius, center)
        }
    }
}

@Composable internal fun Modifier.glassPanel(shape: Shape, background: Color): Modifier {
    val glass = LocalAppearance.current.style == "glass"
    val dark = LocalAppearance.current.dark
    val base = clip(shape).background(background)
    if (!glass) return base
    return base.drawWithCache {
        val sheen = Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) .09f else .34f), Color.White.copy(alpha = .015f), Color.White.copy(alpha = .06f)), Offset.Zero, Offset(size.width * .7f, size.height))
        onDrawBehind { drawRect(sheen) }
    }.border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .48f), Color.White.copy(alpha = .07f), Color.White.copy(alpha = .20f))), shape)
}
