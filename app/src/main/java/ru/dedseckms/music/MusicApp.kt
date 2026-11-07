package ru.dedseckms.music

import android.app.Application
import ru.dedseckms.music.data.CredentialStore
import ru.dedseckms.music.data.LocalStore
import ru.dedseckms.music.data.NavApi

class MusicApp : Application() {
    val playRequests = ru.dedseckms.music.data.PlayRequests()
    val accountChanges = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val volumeChanges = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val credentials by lazy { CredentialStore(this) }
    val covers by lazy { ru.dedseckms.music.data.CoverStore(java.io.File(cacheDir, "covers"), credentials::load) }
    val images by lazy { coil.ImageLoader.Builder(this).build() }
    private var profileSession: Pair<ru.dedseckms.music.data.Credentials, ru.dedseckms.music.data.ProfileApi>? = null
    @Synchronized fun profileApi(): ru.dedseckms.music.data.ProfileApi? {
        val account = credentials.active() ?: return null
        return profileSession?.takeIf { it.first === account }?.second
            ?: ru.dedseckms.music.data.ProfileApi(account).also { profileSession = account to it }
    }
    @Synchronized fun clearProfileSession() { profileSession = null }
    fun api(): NavApi? = credentials.active()?.let(::NavApi)
    fun local(): LocalStore? = credentials.activeId()?.let { LocalStore(this, it) }
}
