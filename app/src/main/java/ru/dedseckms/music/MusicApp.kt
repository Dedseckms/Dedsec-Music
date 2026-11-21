package ru.dedseckms.music

import android.app.Application
import ru.dedseckms.music.data.CredentialStore
import ru.dedseckms.music.data.LocalStore
import ru.dedseckms.music.data.NavApi

class MusicApp : Application() {
    val playRequests = ru.dedseckms.music.data.PlayRequests()
    val accountChanges = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val contentRatingChanges = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, ru.dedseckms.music.data.Song>>(extraBufferCapacity = 16)
    val contentPolicyChanges = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val playbackSongs = java.util.concurrent.ConcurrentHashMap<String, ru.dedseckms.music.data.Song>()
    val volumeChanges = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val credentials by lazy { CredentialStore(this) }
    val covers by lazy { ru.dedseckms.music.data.CoverStore(java.io.File(cacheDir, "covers"), credentials::load) }
    val images by lazy { coil.ImageLoader.Builder(this).build() }
    private var navSession: Pair<ru.dedseckms.music.data.Credentials, NavApi>? = null
    private var profileSession: Pair<ru.dedseckms.music.data.Credentials, ru.dedseckms.music.data.ProfileApi>? = null
    @Synchronized fun profileApi(): ru.dedseckms.music.data.ProfileApi? {
        val account = credentials.active() ?: return null
        return profileSession?.takeIf { it.first.accountId == account.accountId && it.first.password == account.password }?.second
            ?: ru.dedseckms.music.data.ProfileApi(account).also { profileSession = account to it }
    }
    @Synchronized fun clearProfileSession() { profileSession = null; navSession = null }
    @Synchronized fun api(): NavApi? {
        val account = credentials.active() ?: return null
        return navSession?.takeIf { it.first.accountId == account.accountId && it.first.password == account.password }?.second
            ?: NavApi(account).also { navSession = account to it }
    }
    fun local(): LocalStore? = credentials.activeId()?.let { LocalStore(this, it) }
}
