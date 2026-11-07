package ru.dedseckms.music.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import ru.dedseckms.music.AppState
import ru.dedseckms.music.MusicViewModel

internal val LocalProfilePhoto = staticCompositionLocalOf { Pair<String?, String>(null, "") }

@Composable internal fun AccountAvatar(username: String, modifier: Modifier = Modifier) {
    val photo = LocalProfilePhoto.current
    Box(modifier.clip(CircleShape), contentAlignment = Alignment.Center) {
        if (photo.first != null) AsyncImage(ImageRequest.Builder(LocalContext.current).data(java.io.File(photo.first!!))
            .memoryCacheKey("profile:${photo.first}:${photo.second}").build(), "Аватарка $username", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(username.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable internal fun ProfileControls(state: AppState, vm: MusicViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.setAvatar(uri) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) { AccountAvatar(state.username, Modifier.size(76.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.username, style = MaterialTheme.typography.titleLarge)
            Text("Твой профиль", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.profileBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    FilledTonalButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        enabled = !state.profileBusy && !state.demo, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.AddAPhoto, null); Spacer(Modifier.width(8.dp)); Text("Выбрать аватарку")
    }
    if (state.avatarPath != null) TextButton(vm::removeAvatar, enabled = !state.profileBusy) { Text("Убрать аватарку") }
    Text("Фото сохраняется в аккаунте и появится на других твоих устройствах.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
