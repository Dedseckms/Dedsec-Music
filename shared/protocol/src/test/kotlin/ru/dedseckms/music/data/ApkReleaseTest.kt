package ru.dedseckms.music.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApkReleaseTest {
    private fun manifest(url: String = "https://github.com/Dedseckms/Dedsec-Music/releases/download/v-next/music.apk") = JSONObject()
        .put("schema", 1).put("android", JSONObject().put("version", "0.6.0").put("versionCode", 9)
            .put("url", url).put("sha256", "a".repeat(64)).put("size", 1024))
    @Test fun offersOnlyNewerAndroidBuildsAndAllowsCombinedManifest() {
        assertNotNull(ApkReleaseClient.parse(manifest().put("windows-x64", JSONObject()), 8))
        assertNull(ApkReleaseClient.parse(manifest(), 9))
        assertNull(ApkReleaseClient.parse(manifest(), 10))
        assertNull(ApkReleaseClient.parse(JSONObject().put("schema", 1), 8))
    }
    @Test fun assetCannotEscapeThePinnedRepositoryOrHidePathSegments() {
        listOf("http://github.com/Dedseckms/Dedsec-Music/releases/download/a/b.apk",
            "https://github.com/attacker/repo/releases/download/a/b.apk",
            "https://github.com/Dedseckms/Dedsec-Music/releases/download/../../evil.apk",
            "https://github.com/Dedseckms/Dedsec-Music/releases/download/%2e%2e/evil.apk",
            "https://github.com:8443/Dedseckms/Dedsec-Music/releases/download/a/b.apk",
            "https://attacker@github.com/Dedseckms/Dedsec-Music/releases/download/a/b.apk").forEach { url ->
                assertThrows(IllegalArgumentException::class.java) { ApkReleaseClient.parse(manifest(url), 8) }
            }
    }
    @Test fun invalidHashesAndOversizedArchivesAreRejectedBeforeDownload() {
        val hash = manifest(); hash.getJSONObject("android").put("sha256", "bad")
        assertThrows(IllegalArgumentException::class.java) { ApkReleaseClient.parse(hash, 8) }
        val size = manifest(); size.getJSONObject("android").put("size", 256L * 1024 * 1024 + 1)
        assertThrows(IllegalArgumentException::class.java) { ApkReleaseClient.parse(size, 8) }
    }
}
