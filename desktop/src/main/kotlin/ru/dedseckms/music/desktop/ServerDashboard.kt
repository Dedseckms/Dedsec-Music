package ru.dedseckms.music.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.dedseckms.music.data.*
import java.util.Locale

@Composable internal fun ServerDashboard(api: ProfileApi, dismiss: () -> Unit) {
    key(api) {
        val scope = rememberCoroutineScope()
        val session = remember(api) { ServerAdminSession(scope, api) }
        DisposableEffect(session) { onDispose { session.close() } }
        val state by session.state.collectAsState()
        DashboardSurface(dismiss) { DashboardBody(session, state, dismiss) }
    }
}

@Composable internal fun DashboardBody(session: ServerAdminSession, state: ServerAdminState, dismiss: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var source by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Dns, null, tint = colors.primary, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Мой сервер", style = MaterialTheme.typography.headlineSmall)
                Text("Состояние, процессы и музыка на дисках", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            IconButton(session::refresh, enabled = !state.acting) { Icon(Icons.Rounded.Refresh, "Обновить") }
            IconButton(dismiss) { Icon(Icons.Rounded.Close, "Закрыть") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Обзор", "Процессы", "Диски").forEachIndexed { i, title -> FilterChip(tab == i, { tab = i }, { Text(title) }) }
        }
        if (state.loading || state.acting) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { error -> Surface(color = colors.errorContainer, shape = RoundedCornerShape(18.dp)) {
            Text(error, Modifier.fillMaxWidth().padding(14.dp), color = colors.onErrorContainer, style = MaterialTheme.typography.bodySmall)
        } }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (tab == 0) {
                val system = state.system
                if (system == null) item { DashboardEmpty("Ждём данные сервера", "Статистика появится после подключения серверного дополнения.") }
                else {
                    val now = system.latest
                    item { Text(if (system.scope == "host") "Домашний сервер · ${system.cpuCount} CPU · ${if (system.uptime >= 0) "работает ${system.uptime / 86400} дн. ${(system.uptime % 86400) / 3600} ч." else "время работы —"}" else "Показатели контейнера · данные всего хоста недоступны", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DashboardMetric("Процессор", now.cpu?.let { "${serverNumber(it)}%" } ?: "—", "${system.cpuCount} логических CPU", Modifier.weight(1f))
                        DashboardMetric("Память", serverBytes(now.memoryUsed), "из ${serverBytes(now.memoryTotal)}", Modifier.weight(1f))
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(900 to "15 м", 3600 to "1 ч", 21600 to "6 ч", 86400 to "24 ч").forEach { (seconds, label) -> FilterChip(state.windowSeconds == seconds, { session.window(seconds) }, { Text(label) }) } } }
                    item { DashboardMetrics(system) }
                }
            }
            if (tab == 1) {
                val system = state.system
                if (system == null) item { DashboardEmpty("Нет данных о процессах", "Убедись, что серверное дополнение поддерживает мониторинг.") }
                else {
                    item { Text("Показано ${system.processes.size} из ${system.processCount} · CPU и память", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                    items(system.processes.sortedByDescending { it.cpu ?: 0.0 }, key = { "${it.id}:${it.pid}" }) { process ->
                        Surface(shape = RoundedCornerShape(18.dp), color = colors.surfaceContainer) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Memory, null, tint = colors.primary)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(process.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium); Text("PID ${process.pid}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
                                Column(horizontalAlignment = Alignment.End) { Text(process.cpu?.let { "${serverNumber(it)}%" } ?: "—"); Text(serverBytes(process.memory), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
                            }
                        }
                    }
                    item { Text("Показатели только для чтения. Командные строки, пароли и переменные окружения не передаются.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                }
            }
            if (tab == 2) {
                val storage = state.storage
                if (storage == null) item { DashboardEmpty("Хранилище недоступно", "Обнови серверное дополнение и повтори проверку.") }
                else {
                    items(storage.volumes, key = StorageVolume::id) { volume -> DashboardPanel {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Storage, null, tint = colors.primary); Text(volume.label.ifBlank { volume.id }, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium) }
                        Text("Свободно ${serverBytes(volume.available)}", style = MaterialTheme.typography.headlineSmall)
                        LinearProgressIndicator(progress = { if (volume.total > 0) (volume.used.toFloat() / volume.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                        Text("Занято ${serverBytes(volume.used)} из ${serverBytes(volume.total)}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        if (volume.reason.isNotBlank()) Text(volume.reason, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    } }
                    storage.activeJob?.let { move -> item { DashboardPanel {
                        Text("Перенос библиотеки", style = MaterialTheme.typography.titleMedium)
                        Text(moveLabel(move), color = colors.primary)
                        LinearProgressIndicator(progress = { if (move.bytesTotal > 0) (move.bytesCopied.toFloat() / move.bytesTotal).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                        Text("${serverBytes(move.bytesCopied)} / ${serverBytes(move.bytesTotal)} · ${move.filesDone} / ${move.filesTotal} файлов", style = MaterialTheme.typography.bodySmall)
                        if (move.state == "awaiting_activation") Text("Копия проверена. Чтобы переключить диск с сохранением ID треков и плейлистов, администратор должен отдельно активировать прежний путь на сервере. Исходники сохранены, место пока не освобождено.", style = MaterialTheme.typography.bodySmall)
                        if (move.error.isNotBlank()) Text("Код: ${move.error}", color = colors.error, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (move.state in setOf("queued", "running")) OutlinedButton({ session.cancel(move.id) }, enabled = !state.acting) { Text("Остановить копирование") }
                            if (move.state in setOf("paused", "cancelled", "failed")) Button({ session.resume(move.id) }, enabled = !state.acting) { Text("Продолжить") }
                        }
                    } } }
                    item { DashboardPanel {
                        Text("Подготовить перенос", style = MaterialTheme.typography.titleMedium)
                        Text("Музыка продолжит открываться по прежним путям. Сначала создаётся проверенная копия; форматирование и удаление через приложение не выполняются.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        DashboardChoice("Музыка", storage.shards.map { it.id to "${it.label} · ${it.tracks} треков" }, source) { source = it }
                        val sourceVolume = storage.shards.find { it.id == source }?.volumeId
                        val targets = storage.volumes.filter { it.eligible && !it.readOnly && it.id != sourceVolume }
                        DashboardChoice("Новый диск", targets.map { it.id to "${it.label} · ${serverBytes(it.available)} свободно" }, target) { target = it }
                        if (targets.isEmpty()) Text("Пока нет подходящего второго диска. После подключения его нужно добавить в конфигурацию сервера.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Button({ session.plan(source, target) }, enabled = !state.acting && storage.shards.any { it.id == source } && targets.any { it.id == target } && storage.activeJob?.state !in setOf("queued", "running", "awaiting_activation")) { Icon(Icons.Rounded.FactCheck, null); Spacer(Modifier.width(8.dp)); Text("Проверить план") }
                    } }
                }
            }
        }
    }
    state.plan?.let { plan -> AlertDialog(onDismissRequest = session::dismissPlan, title = { Text("Создать проверенную копию?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${plan.files} файлов · ${serverBytes(plan.bytes)}. На новом диске останется резерв не менее ${serverBytes(plan.reserve)}.")
            Text("Исходная музыка остаётся доступной. После копирования потребуется отдельная активация на сервере; эта операция сама не освобождает старый диск.")
            plan.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { Button(session::start, enabled = !state.acting) { Text("Копировать и проверить") } }, dismissButton = { TextButton(session::dismissPlan, enabled = !state.acting) { Text("Отмена") } }) }
}

@Composable private fun DashboardPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainer) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}
@Composable private fun DashboardMetric(title: String, value: String, detail: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.labelLarge); Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1); Text(detail, style = MaterialTheme.typography.bodySmall) }
    }
}
@Composable private fun DashboardEmpty(title: String, detail: String) = DashboardPanel { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, style = MaterialTheme.typography.bodySmall) }
@Composable private fun DashboardChoice(label: String, options: List<Pair<String, String>>, selected: String, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box { OutlinedButton({ open = true }, enabled = options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(options.find { it.first == selected }?.second ?: label, maxLines = 1, overflow = TextOverflow.Ellipsis); Icon(Icons.Rounded.ExpandMore, null) }
        DropdownMenu(open, { open = false }) { options.forEach { (id, title) -> DropdownMenuItem({ Text(title) }, { select(id); open = false }) } }
    }
}
@Composable private fun DashboardChart(title: String, values: List<Double?>, fixedMax: Double?, color: Color, detail: String, secondary: List<Double?> = emptyList()) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.tertiary
    DashboardPanel {
        Row { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (values.count { it != null } < 2) Text("График появится после двух замеров", style = MaterialTheme.typography.bodySmall)
        else Canvas(Modifier.fillMaxWidth().height(128.dp)) {
            val max = (fixedMax ?: (values + secondary).filterNotNull().filter(Double::isFinite).maxOrNull() ?: 1.0).coerceAtLeast(1.0)
            for (i in 0..4) { val y = size.height * i / 4; drawLine(grid.copy(alpha = .55f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
            fun line(data: List<Double?>, tint: Color) {
                if (data.size < 2) return
                val path = Path(); var started = false
                data.forEachIndexed { i, value ->
                    if (value == null || !value.isFinite()) started = false
                    else { val x = size.width * i / (data.size - 1); val y = size.height * (1 - (value / max).coerceIn(0.0, 1.0)).toFloat(); if (!started) path.moveTo(x, y) else path.lineTo(x, y); started = true }
                }
                drawPath(path, tint, style = Stroke(2.5.dp.toPx()))
            }
            line(values, color); line(secondary, accent)
        }
    }
}
private fun serverNumber(value: Double) = if (value.isFinite()) String.format(Locale.ROOT, "%.1f", value) else "—"
private fun serverBytes(value: Long): String { if (value < 0) return "—"; val n = value.coerceAtLeast(0).toDouble(); return when { n >= 1073741824 -> "${serverNumber(n / 1073741824)} ГБ"; n >= 1048576 -> "${serverNumber(n / 1048576)} МБ"; n >= 1024 -> "${serverNumber(n / 1024)} КБ"; else -> "${n.toLong()} Б" } }
private fun moveLabel(move: StorageMove) = when (move.state) { "queued" -> "В очереди"; "running" -> if (move.phase == "verifying") "Проверяем SHA-256" else "Копируем с ограничением нагрузки"; "paused" -> "Приостановлено"; "cancelled" -> "Копирование остановлено"; "awaiting_activation" -> "Копия готова к активации"; "completed" -> "Перенос завершён"; else -> "Операция требует проверки" }

@Composable private fun DashboardSurface(dismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.ui.window.DialogWindow(onCloseRequest = dismiss, state = androidx.compose.ui.window.rememberDialogState(width = 1080.dp, height = 820.dp), title = "Мой сервер", undecorated = true) {
        Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) { content() }
    }
}

@Composable private fun DashboardMetrics(system: ServerSystem) {
    val colors = MaterialTheme.colorScheme
    val now = system.latest
    BoxWithConstraints {
        val wide = maxWidth >= 760.dp
        val cpu: @Composable () -> Unit = { DashboardChart("Загрузка CPU", system.history.map { it.cpu }, 100.0, colors.primary, "0—100%") }
        val memory: @Composable () -> Unit = { DashboardChart("Память", system.history.map { it.memoryUsed.takeIf { n -> n >= 0 }?.toDouble() }, now.memoryTotal.takeIf { it > 0 }?.toDouble(), colors.tertiary, serverBytes(now.memoryTotal)) }
        val network: @Composable () -> Unit = { DashboardChart("Сеть · вход / выход", system.history.map { it.rx }, null, colors.primary, "${if (now.rx.isFinite()) serverBytes(now.rx.toLong()) else "—"}/с ↓  ${if (now.tx.isFinite()) serverBytes(now.tx.toLong()) else "—"}/с ↑", system.history.map { it.tx }) }
        val load: @Composable () -> Unit = { DashboardPanel {
            Text("Средняя нагрузка", style = MaterialTheme.typography.titleMedium)
            Text("${serverNumber(now.load1)} / ${serverNumber(now.load5)} / ${serverNumber(now.load15)}", style = MaterialTheme.typography.headlineSmall)
            Text("За 1, 5 и 15 минут · ${system.cpuCount} CPU", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text("Доступно RAM: ${serverBytes(now.memoryAvailable)}", style = MaterialTheme.typography.bodySmall)
            Text("Swap: ${serverBytes(now.swapUsed)} · Обновление каждые 5 секунд", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        } }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { Box(Modifier.weight(1f)) { cpu() }; Box(Modifier.weight(1f)) { memory() } }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { Box(Modifier.weight(1f)) { network() }; Box(Modifier.weight(1f)) { load() } }
            } else { cpu(); memory(); network(); load() }
            system.history.firstOrNull()?.let { first ->
                val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())
                Text("История: ${formatter.format(java.time.Instant.ofEpochSecond(first.at))} — ${formatter.format(java.time.Instant.ofEpochSecond(system.sampledAt))}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}
