package ru.dedseckms.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.dedseckms.music.MusicViewModel

@Composable internal fun UpdateControls(vm: MusicViewModel) {
    val state by vm.updates.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Обновления", style = MaterialTheme.typography.titleLarge)
        Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.busy) {
            val fraction = state.progress
            if (fraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ vm.updates.check() }, enabled = !state.busy) { Text("Проверить") }
            state.offer?.let {
                Button(if (state.ready) vm.updates::install else vm.updates::download, enabled = !state.busy) {
                    Icon(if (state.ready) Icons.Rounded.SystemUpdate else Icons.Rounded.Download, null)
                    Spacer(Modifier.width(6.dp)); Text(if (state.ready) "Установить" else "Скачать ${it.version}")
                }
            }
        }
    }
}
