package ru.dedseckms.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun WindowChrome(
    dragArea: @Composable (@Composable () -> Unit) -> Unit,
    maximized: Boolean, minimize: () -> Unit, maximize: () -> Unit, close: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(38.dp).background(MaterialTheme.colorScheme.background.copy(alpha = .72f)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            dragArea {
                Row(Modifier.fillMaxWidth().fillMaxHeight().combinedClickable(onClick = {}, onDoubleClick = maximize).padding(horizontal = 19.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandIcon(Modifier.size(21.dp))
                    Spacer(Modifier.width(10.dp)); Text("DedSec Music", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        IconButton(minimize, Modifier.size(44.dp, 34.dp)) { Icon(Icons.Rounded.Remove, "Свернуть", Modifier.size(16.dp)) }
        IconButton(maximize, Modifier.size(44.dp, 34.dp)) { Icon(if (maximized) Icons.Rounded.FilterNone else Icons.Rounded.CropSquare, if (maximized) "Восстановить окно" else "Развернуть окно", Modifier.size(14.dp)) }
        IconButton(close, Modifier.size(44.dp, 34.dp), colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFFDD8B98))) { Icon(Icons.Rounded.Close, "Закрыть", Modifier.size(17.dp)) }
    }
}
