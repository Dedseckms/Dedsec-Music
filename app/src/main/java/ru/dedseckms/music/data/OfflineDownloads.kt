package ru.dedseckms.music.data

import android.content.Context
import androidx.work.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Durable, account-scoped queue used by both the screen and background playback. */
object OfflineDownloads {
    private fun request(account: String, song: Song, wifiOnly: Boolean, id: UUID = UUID.randomUUID()) =
        OneTimeWorkRequestBuilder<DownloadWorker>().setId(id)
            .setInputData(workDataOf("account" to account, "song" to song.json().toString()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .addTag("downloads-$account").addTag("track:${song.id}").addTag("created:${System.currentTimeMillis()}").build()

    suspend fun enqueue(context: Context, account: String, songs: List<Song>): Int = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(context)
        val store = LocalStore(context, account)
        val offline = store.offline().mapTo(hashSetOf(), Song::id)
        val active = DownloadWork.current(manager.getWorkInfosByTag("downloads-$account").get()).filterValues { !it.state.isFinished }.keys
        var added = 0
        for (song in songs.distinctBy(Song::id)) {
            currentCoroutineContext().ensureActive()
            if (CredentialStore(context).activeId() != account) break
            if (song.id in offline || song.id in active) continue
            store.queueDownload(song)
            manager.enqueueUniqueWork("offline-$account-${sha256(song.id)}", ExistingWorkPolicy.KEEP, request(account, song, store.wifiOnly())).result.get()
            added++
        }
        added
    }

    /** updateWork keeps UUID, enqueue time and the running attempt; no competing .part writers. */
    suspend fun updateNetwork(context: Context, account: String): Int = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(context)
        val credentials = CredentialStore(context).load(account) ?: return@withContext 0
        val store = LocalStore(context, account)
        val known = (store.pendingDownloads() + store.cachedSongs() + store.cachedFavorites() + store.history().map { it.song }).associateBy(Song::id).toMutableMap()
        var unresolved = 0
        for (entry in manager.getWorkInfosByTag("downloads-$account").get().filterNot { it.state.isFinished }) {
            currentCoroutineContext().ensureActive()
            if (CredentialStore(context).activeId() != account) break
            val id = entry.tags.firstOrNull { it.startsWith("track:") }?.removePrefix("track:") ?: continue
            val song = known[id] ?: try { Song.parse(NavApi(credentials).call("getSong", "id" to id).getJSONObject("song")) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { unresolved++; continue }
            store.queueDownload(song)
            manager.updateWork(request(account, song, store.wifiOnly(), entry.id)).get()
        }
        unresolved
    }
}
