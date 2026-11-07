package ru.dedseckms.music.data

import androidx.work.WorkInfo

/** WorkManager retains failed attempts after retry; an active attempt must own the track's UI. */
internal object DownloadWork {
    fun current(entries: List<WorkInfo>): Map<String, WorkInfo> = entries
        .filter { it.state != WorkInfo.State.SUCCEEDED && it.state != WorkInfo.State.CANCELLED }
        .mapNotNull { work -> work.tags.find { it.startsWith("track:") }?.removePrefix("track:")?.let { it to work } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, attempts) ->
            attempts.maxWith(compareBy<WorkInfo> { !it.state.isFinished }
                .thenBy { work -> work.tags.firstOrNull { it.startsWith("created:") }?.removePrefix("created:")?.toLongOrNull() ?: 0L }
                .thenBy { it.id.toString() })
        }
}
