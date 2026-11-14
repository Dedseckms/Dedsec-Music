package ru.dedseckms.music.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

data class DesktopUpdateState(val offer: UpdateOffer? = null, val checking: Boolean = false,
    val installing: Boolean = false, val downloaded: Long = 0, val total: Long = 0,
    val message: String = "Проверяем обновления…")

/** Updates belong to the application, independently of music login/logout. */
class DesktopUpdates(private val scope: CoroutineScope, private val exit: () -> (() -> Unit)?,
                     private val checker: () -> UpdateOffer? = { UpdateClient().check() }) {
    private val mutable = MutableStateFlow(DesktopUpdateState())
    val state = mutable.asStateFlow()
    private var checkJob: Job? = null
    private var installJob: Job? = null
    private var download: UpdateClient? = null
    private var lastCheckNanos: Long? = null
    fun check(force: Boolean = false) {
        if (checkJob?.isActive == true || installJob?.isActive == true) return
        val now = System.nanoTime()
        if (!force && lastCheckNanos?.let { now - it < 300_000_000_000L } == true) return
        lastCheckNanos = now
        mutable.update { it.copy(checking = true) }
        checkJob = scope.launch {
            try {
                val offer = withContext(Dispatchers.IO) { checker() }
                mutable.update { it.copy(offer = offer, message = offer?.let { "Доступна версия ${it.version}" } ?: "Обновлений нет · Windows ${DesktopBuild.VERSION}") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(message = "Не удалось проверить обновления. Повтори проверку в настройках") } }
            finally { mutable.update { it.copy(checking = false) } }
        }
    }
    fun cancel() { installJob?.cancel(); download?.cancelDownload() }
    fun install(offer: UpdateOffer) {
        if (installJob?.isActive == true) return
        mutable.update { it.copy(installing = true, downloaded = 0, total = offer.size, message = "Скачиваем обновление…") }
        installJob = scope.launch {
            var helper: Process? = null
            var handedOff = false
            var proceed: Path? = null
            try {
                val operation = currentCoroutineContext()
                val client = UpdateClient(); download = client
                val archive = withContext(Dispatchers.IO) {
                    client.download(offer, { operation.ensureActive() }) { count, total -> mutable.update { it.copy(downloaded = count, total = total) } }
                }
                mutable.update { it.copy(message = "Проверяем и подготавливаем установку…") }
                val resource = System.getProperty("compose.application.resources.dir")?.let(Path::of)
                val root = resource?.parent?.parent
                if (root == null || !Files.isRegularFile(root.resolve(".dedsec-install")) || exit() == null) {
                    java.awt.Desktop.getDesktop().open(archive.parent.toFile())
                    mutable.update { it.copy(message = "Проверенное обновление скачано. Распакуй его в отдельную папку") }
                    return@launch
                }
                val script = archive.parent.resolve("ApplyUpdate-${UUID.randomUUID()}.ps1")
                val ready = archive.parent.resolve("ready-${UUID.randomUUID()}")
                val confirmation = archive.parent.resolve("proceed-${UUID.randomUUID()}"); proceed = confirmation
                withContext(Dispatchers.IO) {
                    DesktopUpdates::class.java.getResourceAsStream("/ApplyUpdate.ps1")!!.use { Files.copy(it, script, REPLACE_EXISTING) }
                    val process = ProcessBuilder("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", script.toString(),
                        "-ProcessId", ProcessHandle.current().pid().toString(), "-InstallRoot", root.toString(), "-Archive", archive.toString(),
                        "-ReadyFile", ready.toString(), "-ProceedFile", confirmation.toString())
                        .directory(archive.parent.toFile()).redirectErrorStream(true).redirectOutput(archive.parent.resolve("apply-update.log").toFile()).start()
                    helper = process
                    withTimeout(300_000) { while (!Files.exists(ready)) { check(process.isAlive) { "Update helper stopped" }; delay(100) } }
                }
                currentCoroutineContext().ensureActive()
                val finish = exit() ?: error("Application exit unavailable")
                check(helper?.isAlive == true)
                // Commit and request exit together, without a cancellation point in between.
                Files.writeString(confirmation, "Proceed")
                finish(); handedOff = true
            } catch (e: CancellationException) { mutable.update { it.copy(message = "Обновление отменено") }; throw e }
            catch (_: Exception) { mutable.update { it.copy(message = "Обновление не установлено. Текущая версия сохранена; журнал — в папке обновлений") } }
            finally {
                if (!handedOff) withContext(NonCancellable + Dispatchers.IO) {
                    helper?.let { it.destroy(); if (!it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { it.destroyForcibly(); it.waitFor() } }
                    proceed?.let { Files.deleteIfExists(it) }
                }
                download = null; mutable.update { it.copy(installing = false) }
            }
        }
    }
}
