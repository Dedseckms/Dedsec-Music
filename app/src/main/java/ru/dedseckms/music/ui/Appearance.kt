@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package ru.dedseckms.music.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.dedseckms.music.AppState
import ru.dedseckms.music.MusicViewModel

internal val LocalVisualStyle = staticCompositionLocalOf { "material" }
internal val LocalAmoled = staticCompositionLocalOf { false }
internal val AccentColors = linkedMapOf("blue" to Color(0xFF407BFF), "violet" to Color(0xFF9966EE),
    "mint" to Color(0xFF27A887), "rose" to Color(0xFFE05A8C), "amber" to Color(0xFFB88210), "cyan" to Color(0xFF039CA9))
private val AccentNames = listOf("Синий", "Лаванда", "Мята", "Роза", "Янтарь", "Лагуна")

internal fun accentScheme(dark: Boolean, palette: String): ColorScheme {
    val seed = AccentColors[palette] ?: AccentColors.getValue("blue")
    val primary = if (dark) lerp(seed, Color.White, .48f) else lerp(seed, Color.Black, .28f)
    val background = if (dark) lerp(Color(0xFF101115), seed, .025f) else lerp(Color(0xFFFFFBFF), seed, .018f)
    val surface = if (dark) lerp(Color(0xFF191B22), seed, .035f) else lerp(Color.White, seed, .045f)
    return if (dark) darkColorScheme(primary = primary, onPrimary = lerp(Color.Black, seed, .22f),
        primaryContainer = lerp(Color(0xFF20232E), seed, .23f), onPrimaryContainer = lerp(seed, Color.White, .78f),
        secondary = lerp(primary, Color(0xFFC1C4D1), .5f), secondaryContainer = lerp(surface, seed, .15f), onSecondaryContainer = Color(0xFFE8EAF3),
        tertiary = lerp(primary, Color(0xFFEFB8C8), .5f), tertiaryContainer = lerp(surface, seed, .12f), onTertiaryContainer = Color(0xFFF5E6EF),
        background = background, surface = background, surfaceContainerLowest = background, surfaceContainer = surface,
        surfaceContainerLow = lerp(background, surface, .5f), surfaceContainerHigh = lerp(surface, Color.White, .04f),
        surfaceContainerHighest = lerp(surface, Color.White, .08f), surfaceVariant = lerp(surface, Color.White, .12f), surfaceTint = primary)
    else lightColorScheme(primary = primary, onPrimary = Color.White,
        primaryContainer = lerp(seed, Color.White, .83f), onPrimaryContainer = lerp(seed, Color.Black, .70f),
        secondary = lerp(seed, Color(0xFF454B60), .7f), secondaryContainer = lerp(seed, Color.White, .90f), onSecondaryContainer = Color(0xFF232738),
        tertiary = lerp(primary, Color(0xFF885371), .6f), tertiaryContainer = Color(0xFFF4DFEE), onTertiaryContainer = Color(0xFF352032),
        background = background, surface = background, surfaceContainerLowest = Color.White, surfaceContainer = surface,
        surfaceContainerLow = lerp(background, surface, .5f), surfaceContainerHigh = lerp(surface, seed, .025f),
        surfaceContainerHighest = lerp(surface, seed, .05f), surfaceVariant = lerp(surface, seed, .08f), surfaceTint = primary)
}

internal fun styledScheme(base: ColorScheme, style: String, amoled: Boolean): ColorScheme {
    val scheme = when (style) {
        "glass" -> base.copy(surfaceContainer = base.surfaceContainer.copy(alpha = .14f), surfaceContainerHigh = base.surfaceContainerHigh.copy(alpha = .24f),
            primaryContainer = base.primaryContainer.copy(alpha = .30f), secondaryContainer = base.secondaryContainer.copy(alpha = .18f))
        "mono" -> base.copy(primary = base.onSurface, onPrimary = base.surface, primaryContainer = base.surfaceContainerHigh,
            onPrimaryContainer = base.onSurface, secondary = base.onSurface, secondaryContainer = base.surfaceContainerHigh, onSecondaryContainer = base.onSurface)
        else -> base
    }
    return if (!amoled) scheme else scheme.copy(background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color.Black, surfaceContainer = Color.Black, surfaceContainerHigh = Color(0xFF0C0C0C),
        surfaceContainerHighest = Color(0xFF151515), surfaceVariant = Color(0xFF151515))
}

@Composable internal fun panelShape(radius: Int) = RoundedCornerShape(when (LocalVisualStyle.current) { "glass" -> maxOf(radius, 30); "mono" -> 8; else -> radius }.dp)
@Composable internal fun panelBorder(): BorderStroke? = if (LocalVisualStyle.current == "glass")
    BorderStroke(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .48f), MaterialTheme.colorScheme.primary.copy(alpha = .12f), Color.White.copy(alpha = .20f)))) else null

@Composable internal fun ThemeBackdrop(amoled: Boolean, content: @Composable () -> Unit) {
    val style = LocalVisualStyle.current
    if (style == "glass") { LiquidBackdrop(amoled, content); return }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.background)) {
        if (!amoled && style in setOf("glass", "aurora")) Canvas(Modifier.fillMaxSize()) {
            val strength = if (style == "glass") .24f else .36f
            drawRect(Brush.radialGradient(listOf(colors.primary.copy(alpha = strength), Color.Transparent), Offset(size.width * .92f, size.height * .18f), size.width * 1.1f))
            drawRect(Brush.radialGradient(listOf(colors.tertiary.copy(alpha = strength), Color.Transparent), Offset(size.width * .05f, size.height * .68f), size.width * 1.05f))
        }
        CompositionLocalProvider(LocalContentColor provides colors.onBackground) { content() }
    }
}

@Composable internal fun AppearanceControls(state: AppState, vm: MusicViewModel) {
    Text("Оформление", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("system" to "Система", "light" to "Светлая", "dark" to "Тёмная", "amoled" to "AMOLED").forEach { (key, label) ->
            FilterChip(state.theme == key, { vm.appearance(theme = key) }, { Text(label) })
        }
    }
    Text("Стиль интерфейса", style = MaterialTheme.typography.titleSmall)
    val styles = listOf(Triple("material", "Material", Icons.Rounded.Widgets), Triple("glass", "Стекло", Icons.Rounded.BlurOn),
        Triple("aurora", "Аврора", Icons.Rounded.AutoAwesome), Triple("mono", "Минимал", Icons.Rounded.CropSquare))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(styles, key = { it.first }) { (key, label, icon) ->
            Surface(onClick = { vm.appearance(visualStyle = key) }, shape = RoundedCornerShape(if (key == "mono") 8.dp else 24.dp),
                color = if (state.visualStyle == key) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (state.visualStyle == key) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else if (key == "glass") BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null) {
                Column(Modifier.width(118.dp).height(90.dp).padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Icon(icon, null); if (state.visualStyle == key) Icon(Icons.Rounded.CheckCircle, "Выбран", tint = MaterialTheme.colorScheme.primary) }
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    Text(when (state.visualStyle) { "glass" -> "Жидкое стекло: полупрозрачные панели, мягкий свет и блики по краям."; "aurora" -> "Аврора: цветное свечение и выразительные акценты."; "mono" -> "Минимал: строгие формы и спокойные акценты."; else -> "Material: привычные объёмные формы и мягкие поверхности." }, style = MaterialTheme.typography.bodySmall)
    if (Build.VERSION.SDK_INT >= 31) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Системные цвета", style = MaterialTheme.typography.titleSmall); Text("Палитра из обоев телефона", style = MaterialTheme.typography.bodySmall) }
        Switch(state.dynamicColor, { vm.appearance(dynamic = it) })
    }
    Text("Цвета интерфейса", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AccentColors.entries.forEachIndexed { index, (key, color) ->
            Surface(onClick = { vm.appearance(palette = key, dynamic = false) }, shape = CircleShape, color = color,
                border = if (!state.dynamicColor && state.palette == key) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(if (!state.dynamicColor && state.palette == key) Icons.Rounded.Check else Icons.Rounded.Circle,
                    AccentNames[index], tint = if (!state.dynamicColor && state.palette == key) Color.White else Color.Transparent, modifier = Modifier.size(20.dp)) }
            }
        }
    }
}
