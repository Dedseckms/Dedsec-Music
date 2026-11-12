package ru.dedseckms.music.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ru.dedseckms.music.data.*

@Composable internal fun AdminDialog(api: ProfileApi, dismiss: () -> Unit) {
    key(api) { AdminDialogContent(api, dismiss) }
}
@Composable private fun AdminDialogContent(api: ProfileApi, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session = remember(api) { AdminSession(scope, api) }
    DisposableEffect(session) { onDispose { session.close() } }
    val state by session.state.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AdminUser?>(null) }
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.selected != null) IconButton(session::back, enabled = !state.busy) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                    Text(state.selected?.username ?: "Пользователи", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(dismiss) { Icon(Icons.Rounded.Close, "Закрыть") }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (state.selected == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ creating = true }, enabled = !state.busy) { Icon(Icons.Rounded.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text("Добавить") }
                        TextButton(session::refresh, enabled = !state.busy) { Text("Обновить") }
                    }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(state.users, key = AdminUser::id) { user ->
                            ListItem(headlineContent = { Text(user.username) },
                                supportingContent = { Text(if (user.isAdmin) "Администратор · ${user.uploadCount} добавлений" else "${user.uploadCount} добавлений") },
                                leadingContent = { Icon(Icons.Rounded.Person, null) },
                                trailingContent = { Row {
                                    IconButton({ session.uploads(user) }, enabled = !state.busy) { Icon(Icons.Rounded.LibraryMusic, "Что добавил") }
                                    if (user.canDelete) IconButton({ deleting = user }, enabled = !state.busy) { Icon(Icons.Rounded.PersonRemove, "Удалить аккаунт") }
                                } })
                        }
                    }
                } else {
                    Text("Добавленная музыка · ${state.uploadTotal}", style = MaterialTheme.typography.labelLarge)
                    Text("У старых загрузок автор может быть неизвестен.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(state.uploads) { upload ->
                            ListItem(headlineContent = { Text(upload.name) },
                                supportingContent = { Text(if (upload.source == "yandex") "Перенос из Яндекса" else "Аудиофайл") },
                                leadingContent = { Icon(Icons.Rounded.MusicNote, null) })
                        }
                        if (state.uploads.size < state.uploadTotal) item { TextButton(session::more, enabled = !state.busy) { Text("Показать ещё") } }
                        if (!state.busy && state.uploads.isEmpty()) item { Text("Пока нет известных добавлений", modifier = Modifier.padding(16.dp)) }
                    }
                }
            }
        }
    }
    if (creating) {
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { password = ""; creating = false }, title = { Text("Новый пользователь") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Личные плейлисты и доступ к общей музыке. Пароль передай другу самостоятельно.")
                OutlinedTextField(username, { username = it }, label = { Text("Логин") }, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Пароль · от 12 символов") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            } },
            confirmButton = { TextButton({ session.create(username.trim(), password); password = ""; creating = false },
                enabled = username.isNotBlank() && password.length >= 12 && password.toByteArray().size <= 72) { Text("Создать") } },
            dismissButton = { TextButton({ password = ""; creating = false }) { Text("Отмена") } })
    }
    deleting?.let { user ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить ${user.username}?") },
            text = { Text("Музыкальный аккаунт, его личные плейлисты и доступ к загрузке будут удалены. Общая музыка останется на сервере.") },
            confirmButton = { TextButton({ session.delete(user); deleting = null }) { Text("Удалить аккаунт") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Отмена") } })
    }
}
