package ru.dedseckms.music.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.dedseckms.music.data.*

@Composable internal fun LyricsBatchPanel(api: ProfileApi) {
    val scope = rememberCoroutineScope()
    val session = remember(api) { LyricsBatchSession(scope, api) }
    DisposableEffect(session) { onDispose { session.close() } }
    val state by session.state.collectAsState()
    val progress = state.progress
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Lyrics, null, tint = MaterialTheme.colorScheme.primary)
            Text("Тексты всей библиотеки", style = MaterialTheme.typography.titleLarge)
        }
        Text("Сервер ищет настоящий текст и готовые таймкоды в фоне. Результаты доступны всем слушателям, включая добавленные вручную треки.", style = MaterialTheme.typography.bodyMedium)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (progress != null) {
            Text(when { !progress.running -> if (progress.total > 0 && progress.pending == 0 && !progress.discovering) "Проверка завершена" else "Поиск приостановлен"; progress.discovering -> "Собираем очередь…"; else -> "Ищем тексты…" }, style = MaterialTheme.typography.titleMedium)
            if (progress.discovering && progress.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { if (progress.total > 0) progress.checked.toFloat() / progress.total else 0f }, modifier = Modifier.fillMaxWidth())
            Text("Проверено ${progress.checked} / ${progress.total} · в очереди ${progress.pending}")
            Text("С таймкодами: ${progress.synced}\nБез таймкодов: ${progress.plain}\nБез вокала: ${progress.instrumental}\nНе найдено: ${progress.notFound}\nОшибки: ${progress.errors}", style = MaterialTheme.typography.bodyMedium)
            Button({ session.action(if (progress.running) "pause" else "start") }, enabled = !state.acting) {
                Icon(if (progress.running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (progress.running) "Приостановить" else if (progress.total == 0) "Начать поиск" else "Продолжить / проверить новые")
            }
        } else if (state.error == null) CircularProgressIndicator()
        progress?.alignment?.let { alignment ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Автосинхронизация", style = MaterialTheme.typography.titleLarge)
            Text(when { !alignment.enabled -> "Приостановлена"; alignment.active > 0 -> "Выравниваем текст по аудио…"; alignment.pending > 0 -> "Ожидаем обработку на втором сервере"; else -> "Ожидаем новые тексты без таймкодов" }, style = MaterialTheme.typography.bodyMedium)
            val completed = alignment.done + alignment.skipped + alignment.errors
            LinearProgressIndicator(progress = { if (alignment.total > 0) (completed.toFloat() / alignment.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
            Text("Синхронизировано: ${alignment.done}\nВ очереди: ${alignment.pending} · обрабатывается: ${alignment.active}\nНе поддерживаются: ${alignment.skipped}\nОшибки: ${alignment.errors}", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton({ session.action(if (alignment.enabled) "align_pause" else "align_start") }, enabled = !state.acting) {
                Text(if (alignment.enabled) "Приостановить выравнивание" else "Включить выравнивание")
            }
            Text("Обрабатывается только найденный текст без таймкодов. Неизвестные слова и неподдерживаемые записи пропускаются. Готовая синхронизация и поправки владельца сохраняются.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Закрытие приложения не останавливает очередь. Отсутствующий текст и таймкоды не выдумываются.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}