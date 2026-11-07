package ru.dedseckms.music.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.dedseckms.music.data.*

@Composable internal fun WaveSettingsDialog(c: DesktopController, s: DesktopState, close: () -> Unit) {
    var mood by remember { mutableStateOf(s.mood) }
    var mode by remember { mutableStateOf(s.mode) }
    val moods = listOf(
        Triple(MixMood.ANY, "Любое", Icons.Rounded.AutoAwesome), Triple(MixMood.CHILL, "Чилл", Icons.Rounded.Spa),
        Triple(MixMood.ENERGY, "Бодро", Icons.Rounded.Bolt), Triple(MixMood.FOCUS, "Фокус", Icons.Rounded.CenterFocusStrong),
        Triple(MixMood.WORKOUT, "Спорт", Icons.Rounded.FitnessCenter), Triple(MixMood.PARTY, "Вечер", Icons.Rounded.Celebration),
        Triple(MixMood.SAD, "Грусть", Icons.Rounded.Cloud), Triple(MixMood.HAPPY, "Радость", Icons.Rounded.WbSunny),
    )
    AlertDialog(onDismissRequest = close, title = { Text("Настроить мою волну") }, text = {
        Column(Modifier.width(540.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Как ты сейчас?", style = MaterialTheme.typography.titleSmall)
            moods.chunked(4).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (value, title, icon) -> FilterChip(mood == value, { mood = value }, label = { Text(title) }, leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) }) }
            } }
            Spacer(Modifier.height(6.dp))
            Text("Что включать", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(MixMode.BALANCED to "Баланс", MixMode.FAVORITES to "Любимое", MixMode.DISCOVERY to "Открытия").forEach { (value, title) ->
                    FilterChip(mode == value, { mode = value }, label = { Text(title) })
                }
            }
            Text("Микс собирается из музыки твоего сервера", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { Button({ c.wave(mood, mode); close() }) { Icon(Icons.Rounded.PlayArrow, null); Text("Слушать волну") } },
        dismissButton = { TextButton(close) { Text("Отмена") } })
}
