@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.dedseckms.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.dedseckms.music.data.*

@Composable internal fun WaveSettingsSheet(mode: MixMode, mood: MixMood, start: (MixMode, MixMood) -> Unit, dismiss: () -> Unit) {
    var selected by remember { mutableStateOf(mode) }
    var atmosphere by remember { mutableStateOf(mood) }
    val moods = listOf(Triple(MixMood.ANY, "Любое", Icons.Rounded.AutoAwesome), Triple(MixMood.ENERGY, "Драйв", Icons.Rounded.Bolt),
        Triple(MixMood.CHILL, "Чилл", Icons.Rounded.NightsStay), Triple(MixMood.FOCUS, "Фокус", Icons.Rounded.Headphones),
        Triple(MixMood.WORKOUT, "Спорт", Icons.Rounded.FitnessCenter), Triple(MixMood.PARTY, "Вечер", Icons.Rounded.Celebration),
        Triple(MixMood.SAD, "Грусть", Icons.Rounded.WaterDrop), Triple(MixMood.HAPPY, "Радость", Icons.Rounded.WbSunny))
    ModalBottomSheet(dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Моя волна", style = MaterialTheme.typography.headlineMedium)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(MixMode.BALANCED to "Баланс", MixMode.FAVORITES to "Любимое", MixMode.DISCOVERY to "Новое").forEach { (value, label) ->
                        FilterChip(selected == value, { selected = value }, { Text(label) })
                    }
                }
                Text("Настроение", style = MaterialTheme.typography.titleSmall)
                moods.chunked(4).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (value, label, icon) ->
                        Surface(onClick = { atmosphere = value }, shape = panelShape(18), border = panelBorder(),
                            color = if (value == atmosphere) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(horizontal = 3.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(icon, null, Modifier.size(25.dp), tint = if (value == atmosphere) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } }
                Text("Настроение подбирается по жанрам и названиям твоей музыки. Если подходящих тегов мало, микс будет разнообразнее.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button({ start(selected, atmosphere) }, modifier = Modifier.fillMaxWidth().height(54.dp).testTag("start-wave")) {
                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Слушать волну")
            }
        }
    }
}
