package ru.dedseckms.music.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun DesktopUpdateDialog(controller: DesktopController) {
    val update by controller.updates.collectAsState()
    var postponed by remember { mutableStateOf("") }
    val offer = update.offer ?: return
    if (postponed == offer.version && !update.installing) return
    fun postpone() { postponed = offer.version; if (update.installing) controller.cancelUpdate() }
    AlertDialog(onDismissRequest = { postpone() }, title = { Text(if (update.installing) "Обновляем DedSec Music" else "Доступна версия ${offer.version}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(update.message)
            if (update.installing) {
                if (update.total > 0 && update.downloaded < update.total) {
                    LinearProgressIndicator(progress = { (update.downloaded.toFloat() / update.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${update.downloaded / 1048576} / ${update.total / 1048576} МБ")
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            } else Text("После проверки пакета приложение перезапустится. Музыка и настройки сохранятся.")
        } },
        confirmButton = { if (!update.installing) TextButton({ controller.installUpdate(offer) }) { Text("Обновить") } },
        dismissButton = { TextButton({ postpone() }) { Text(if (update.installing) "Отменить" else "Позже") } })
}
