package ru.dedseckms.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ru.dedseckms.music.data.*

@Composable internal fun YandexImportCard(status: ImportStatus, enabled: Boolean, submit: (String) -> Unit, cancel: () -> Unit, refresh: () -> Unit) {
    var token by remember { mutableStateOf("") }
    val links = LocalUriHandler.current
    val job = status.job
    OutlinedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Из Яндекс Музыки", style = MaterialTheme.typography.titleLarge)
            Text("Перенеси свою коллекцию на этот сервер. Аудиокниги и подкасты исключаются; готовые файлы не скачиваются повторно.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (job?.active != true) {
                OutlinedTextField(token, { token = it }, label = { Text("Токен Яндекс Музыки") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = enabled && !status.busy, modifier = Modifier.fillMaxWidth())
                FilledTonalButton({ links.openUri(YANDEX_IMPORT_GUIDE) }) {
                    Icon(Icons.AutoMirrored.Rounded.HelpOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Как получить токен")
                }
                Text("Токен отправляется только твоему музыкальному серверу. Он не сохраняется в приложении.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({ val value = token.trim(); token = ""; submit(value) }, enabled = enabled && !status.busy && token.isNotBlank()) {
                    Icon(Icons.Rounded.CloudDownload, null); Spacer(Modifier.width(8.dp)); Text(if (job?.resumable == true) "Продолжить перенос" else "Перенести коллекцию")
                }
            }
            if (!enabled) Text("Перенос доступен после входа на свой сервер", style = MaterialTheme.typography.bodySmall)
            status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (status.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            job?.let {
                HorizontalDivider()
                Text(it.label, style = MaterialTheme.typography.titleMedium)
                val fraction = it.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${(fraction * 100).toInt()}% · ${it.processed} из ${it.total}", style = MaterialTheme.typography.labelLarge)
                } else if (it.active) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Найдено записей: ${it.discovered}", style = MaterialTheme.typography.labelLarge)
                }
                Text("Скачано: ${it.downloaded} · Уже на сервере: ${it.skipped}\nОшибки: ${it.failed} · Недоступно: ${it.unavailable} · Исключено: ${it.excluded}", style = MaterialTheme.typography.bodySmall)
                if (it.playlistCount > 0) Text("Плейлисты: ${it.completedPlaylistCount} из ${it.playlistCount}", style = MaterialTheme.typography.bodySmall)
                if (it.syncingPlaylists) Text("Не редактируй импортируемые плейлисты до завершения переноса.", style = MaterialTheme.typography.labelSmall)
                it.current?.let { track -> Text("${track.artist} — ${track.title}", maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (it.active) {
                    Text("Сервер продолжит перенос, даже если закрыть приложение.", style = MaterialTheme.typography.labelSmall)
                    OutlinedButton(cancel, enabled = !status.busy && it.state != "cancelling") { Text("Остановить перенос") }
                }
                if (it.state == "paused_auth") Text("Сервер перезапускался или токен истёк. Вставь токен снова, чтобы продолжить с сохранённого места.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(refresh, enabled = enabled && !status.busy) { Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Обновить статус") }
        }
    }
}
