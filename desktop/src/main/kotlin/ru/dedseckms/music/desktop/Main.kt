package ru.dedseckms.music.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.*
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.*

fun main(args: Array<String>) {
    args.firstOrNull { it.startsWith("--render=") }?.let { renderGallery(java.nio.file.Path.of(it.substringAfter('='))); return }
    if ("--audio-smoke" in args) { audioSmoke(); return }
    application {
    val scope = rememberCoroutineScope()
    val controller = remember { DesktopController(scope) }
    val screen = remember { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
    val state = rememberWindowState(width = minOf(1440, screen.width).dp, height = minOf(940, screen.height).dp)
    DisposableEffect(Unit) { onDispose { controller.close() } }
    LaunchedEffect(Unit) { controller.exitForUpdate = { exitApplication() }; controller.checkUpdates(silent = true); if ("--demo" in args) controller.demo() else controller.restoreLogin() }
    Window(onCloseRequest = ::exitApplication, state = state, title = "DedSec Music", undecorated = true, icon = painterResource("app-icon.png")) {
        window.minimumSize = java.awt.Dimension(960, 600)
        Box {
            val toggleMaximize = { state.placement = if (state.placement == WindowPlacement.Floating) WindowPlacement.Maximized else WindowPlacement.Floating }
            DesktopApp(controller, dragArea = { content -> WindowDragArea(state, toggleMaximize) { content() } }, maximized = state.placement != WindowPlacement.Floating,
                minimize = { state.isMinimized = true }, maximize = toggleMaximize, close = ::exitApplication)
            WindowResizeEdges(state.placement == WindowPlacement.Floating)
        }
        LaunchedEffect(Unit) {
            args.firstOrNull { it.startsWith("--update-ready=") }?.let { argument ->
                val marker = java.nio.file.Path.of(argument.substringAfter('=')).toAbsolutePath().normalize()
                val allowed = DesktopPaths.root.resolve("updates").toAbsolutePath().normalize()
                if (marker.startsWith(allowed) && marker.fileName.toString().startsWith("launch-ready-")) {
                    withContext(Dispatchers.IO) { java.nio.file.Files.writeString(marker, "Ready") }
                }
            }
        }
    }
}
}
