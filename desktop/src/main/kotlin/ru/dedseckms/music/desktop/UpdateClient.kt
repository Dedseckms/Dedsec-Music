package ru.dedseckms.music.desktop

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit

data class UpdateOffer(val version: String, val url: String, val sha256: String, val size: Long)

class UpdateClient(private val publicKey: ByteArray = UpdateClient::class.java.getResourceAsStream("/update-public-key.der")!!.use { it.readBytes() }) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(120, TimeUnit.SECONDS).build()
    private val assetPrefix = "https://github.com/Dedseckms/Dedsec-Music/releases/download/"
    fun check(): UpdateOffer? {
        val latest = fetch("https://api.github.com/repos/Dedseckms/Dedsec-Music/releases/latest", 256 * 1024, missing = true) ?: return null
        val release = JSONObject(String(latest, Charsets.UTF_8))
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val assets = release.getJSONArray("assets")
        fun asset(name: String): String? = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == name }?.optString("browser_download_url")?.also { require(it.startsWith(assetPrefix)) }
        val manifest = asset("updates.json") ?: return null
        val signature = asset("updates.json.sig") ?: return null
        val raw = fetch(manifest, 64 * 1024) ?: return null
        val sig = fetch(signature, 1024) ?: return null
        return verify(raw, sig, publicKey)?.takeIf { newer(it.version, DesktopBuild.VERSION) }
    }
    fun download(offer: UpdateOffer): Path {
        require(offer.url.startsWith(assetPrefix))
        val folder = DesktopPaths.root.resolve("updates/${offer.version}"); Files.createDirectories(folder)
        val temp = Files.createTempFile(folder, "package-", ".part")
        try {
            response(offer.url).use { response ->
                check(response.isSuccessful) { "Обновление недоступно" }
                val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
                response.body!!.byteStream().use { input -> Files.newOutputStream(temp).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; count += n; check(count <= offer.size); digest.update(buffer, 0, n); output.write(buffer, 0, n) }
                } }
                check(count == offer.size && digest.digest().joinToString("") { "%02x".format(it) } == offer.sha256) { "Проверка обновления не пройдена" }
            }
            val zip = folder.resolve("update.zip"); Files.move(temp, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return zip
        } finally { Files.deleteIfExists(temp) }
    }
    private fun fetch(url: String, limit: Int, missing: Boolean = false): ByteArray? = response(url).use {
        if (missing && it.code == 404) return null
        check(it.isSuccessful) { "Не удалось проверить обновления" }
        val bytes = it.body!!.byteStream().use { input -> input.readNBytes(limit + 1) }
        check(bytes.size <= limit); bytes
    }
    private fun response(initial: String): okhttp3.Response {
        var url = initial
        repeat(6) {
            val uri = URI(url)
            require(uri.scheme == "https" && uri.userInfo == null && uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com"))
            val response = client.newCall(Request.Builder().url(url).header("User-Agent", "DedSec-Music-Desktop/${DesktopBuild.VERSION}").build()).execute()
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val next = response.header("Location"); response.close(); require(next != null)
            url = uri.resolve(next).toString()
        }
        error("Слишком много перенаправлений")
    }
    companion object {
        fun newer(candidate: String, current: String): Boolean {
            fun parts(value: String): List<Int> { require(value.matches(Regex("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}"))); return value.split('.').map(String::toInt) }
            val a = parts(candidate); val b = parts(current)
            for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
            return false
        }
        fun verify(raw: ByteArray, encoded: ByteArray, publicKey: ByteArray): UpdateOffer? {
            require(raw.size <= 65536 && encoded.size <= 1024)
            val verifier = Signature.getInstance("Ed25519"); verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicKey))); verifier.update(raw)
            require(verifier.verify(Base64.getDecoder().decode(String(encoded).trim()))) { "Подпись обновления не совпадает" }
            val manifest = JSONObject(String(raw, Charsets.UTF_8)); require(manifest.getInt("schema") == 1)
            val item = manifest.optJSONObject("windows-x64") ?: return null
            val offer = UpdateOffer(item.getString("version"), item.getString("url"), item.getString("sha256"), item.getLong("size"))
            newer(offer.version, "0.0.0")
            require(offer.url.startsWith("https://github.com/Dedseckms/Dedsec-Music/releases/download/") && URI(offer.url).userInfo == null)
            require(offer.sha256.matches(Regex("[0-9a-f]{64}")) && offer.size in 1..768L * 1024 * 1024)
            return offer
        }
    }
}
