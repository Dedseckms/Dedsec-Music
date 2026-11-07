package ru.dedseckms.music

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.dedseckms.music.data.*

data class AndroidUpdateState(val message: String = "Обновления ещё не опубликованы", val offer: ApkOffer? = null,
    val busy: Boolean = false, val progress: Float? = null, val ready: Boolean = false)

class AndroidUpdates(private val context: Context, private val scope: CoroutineScope) : AutoCloseable {
    private val client = ApkReleaseClient()
    private val mutable = MutableStateFlow(AndroidUpdateState())
    val state = mutable.asStateFlow()
    private val file get() = File(context.cacheDir, "updates/update.apk")
    fun check(silent: Boolean = false) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true) }
        scope.launch {
            try {
                val offer = withContext(Dispatchers.IO) { client.check(BuildConfig.VERSION_CODE.toLong()) }
                mutable.value = AndroidUpdateState(offer?.let { "Доступна версия ${it.version}" } ?: "Новых обновлений нет · Android ${BuildConfig.VERSION_NAME}", offer)
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { if (!silent) mutable.update { it.copy(message = "Не удалось проверить обновления. Попробуй позже") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun download() {
        val offer = state.value.offer ?: return
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, ready = false, progress = 0f, message = "Скачиваем обновление") }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    val job = currentCoroutineContext()
                    client.download(offer, file) { bytes -> job.ensureActive(); mutable.update { it.copy(progress = (bytes.toDouble() / offer.size).toFloat()) } }
                    verifyApk(offer)
                }
                mutable.update { it.copy(ready = true, message = "Обновление проверено. Подтверди установку в Android") }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { file.delete(); mutable.update { it.copy(ready = false, message = "Обновление не прошло проверку или не скачалось. Попробуй снова") }
            } finally { mutable.update { it.copy(busy = false, progress = null) } }
        }
    }
    @Suppress("DEPRECATION") private fun verifyApk(offer: ApkOffer) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = manager.getPackageArchiveInfo(file.path, flags) ?: error("Invalid APK")
        val installed = manager.getPackageInfo(context.packageName, flags)
        fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        fun certificates(info: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            .orEmpty().map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        val current = certificates(installed)
        check(archive.packageName == context.packageName && code(archive) == offer.versionCode && code(archive) > code(installed) &&
            current.isNotEmpty() && certificates(archive) == current)
    }
    /** The system installer performs the final APK signature verification and asks for user consent. */
    fun install() {
        if (!state.value.ready || state.value.busy) return
        val offer = state.value.offer ?: return
        mutable.update { it.copy(busy = true) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { verifyApk(offer) }
                val intent = if (!context.packageManager.canRequestPackageInstalls()) {
                    mutable.update { it.copy(message = "Разреши установку из DedSec Music и вернись к кнопке «Установить»") }
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                } else Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.updates", file), "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutable.update { it.copy(message = "Не удалось открыть установщик Android") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    override fun close() { client.close() }
}
