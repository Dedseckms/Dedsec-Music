package ru.dedseckms.music.data

import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class ApkOffer(val version: String, val versionCode: Long, val url: String, val sha256: String, val size: Long)

/** Downloads only release assets from this project's GitHub repository. APK signatures are checked by Android. */
class ApkReleaseClient {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(180, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
    fun close() { client.dispatcher.cancelAll() }
    fun check(installedCode: Long): ApkOffer? {
        val releaseBytes = fetch("https://api.github.com/repos/Dedseckms/Dedsec-Music/releases/latest", 256 * 1024, true) ?: return null
        val release = JSONObject(String(releaseBytes, Charsets.UTF_8))
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val assets = release.getJSONArray("assets")
        val manifest = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == "updates.json" }?.getString("browser_download_url") ?: return null
        requireAsset(manifest)
        val raw = fetch(manifest, 65536) ?: return null
        return parse(JSONObject(String(raw, Charsets.UTF_8)), installedCode)
    }
    fun download(offer: ApkOffer, destination: File, checkpoint: (Long) -> Unit) {
        requireAsset(offer.url)
        val temp = File(destination.parentFile, "download.part")
        try {
            response(offer.url).use { response ->
                if (!response.isSuccessful) throw IOException("Не удалось скачать обновление")
                val body = response.body ?: throw IOException("Нет файла обновления")
                val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
                body.byteStream().use { input -> temp.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val size = input.read(buffer); if (size < 0) break
                        count += size; if (count > offer.size) throw IOException("Размер обновления не совпадает")
                        checkpoint(count); digest.update(buffer, 0, size); output.write(buffer, 0, size)
                    }
                } }
                if (count != offer.size || digest.digest().joinToString("") { "%02x".format(it) } != offer.sha256)
                    throw IOException("Проверка файла обновления не пройдена")
            }
            if (destination.exists() && !destination.delete()) throw IOException("Не удалось подготовить обновление")
            if (!temp.renameTo(destination)) throw IOException("Не удалось сохранить обновление")
        } finally { temp.delete() }
    }
    private fun fetch(url: String, limit: Int, missing: Boolean = false): ByteArray? = response(url).use {
        if (missing && it.code == 404) return null
        if (!it.isSuccessful) throw IOException("Не удалось проверить обновления")
        readBounded(it.body?.byteStream() ?: throw IOException("Нет ответа"), limit)
    }
    private fun response(initial: String): okhttp3.Response {
        var uri = URI(initial)
        repeat(6) {
            require(uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
                uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com"))
            val result = client.newCall(Request.Builder().url(uri.toString()).header("User-Agent", "DedSec-Music-Android").build()).execute()
            if (result.code !in setOf(301, 302, 303, 307, 308)) return result
            val next = result.header("Location"); result.close(); require(next != null)
            uri = uri.resolve(next)
        }
        throw IOException("Слишком много перенаправлений")
    }
    companion object {
        private fun requireAsset(url: String) {
            val uri = URI(url)
            require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath.startsWith("/Dedseckms/Dedsec-Music/releases/download/") && !uri.rawPath.contains("..") && !uri.rawPath.contains('%'))
        }
        fun parse(manifest: JSONObject, installedCode: Long): ApkOffer? {
            require(manifest.getInt("schema") == 1)
            val data = manifest.optJSONObject("android") ?: return null
            val offer = ApkOffer(data.getString("version"), data.getLong("versionCode"), data.getString("url"), data.getString("sha256"), data.getLong("size"))
            require(offer.version.matches(Regex("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}")))
            requireAsset(offer.url)
            require(offer.sha256.matches(Regex("[0-9a-f]{64}")) && offer.size in 1..256L * 1024 * 1024 && offer.versionCode > 0)
            return offer.takeIf { it.versionCode > installedCode }
        }
    }
}
