package ru.dedseckms.music.data

import androidx.work.WorkInfo
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DownloadWorkTest {
    private fun work(state: WorkInfo.State, created: Long, song: String = "song") = WorkInfo(
        id = UUID.randomUUID(), state = state, tags = setOf("track:$song", "created:$created"))

    @Test fun failedAttemptNeverHidesAnActiveRetryRegardlessOfResultOrder() {
        val failed = work(WorkInfo.State.FAILED, 100)
        for (state in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)) {
            val retry = work(state, 200)
            assertEquals(retry, DownloadWork.current(listOf(failed, retry))["song"])
            assertEquals(retry, DownloadWork.current(listOf(retry, failed))["song"])
        }
    }

    @Test fun newestFailureIsStableAndFinishedSuccessesDoNotLeakIntoOtherTracks() {
        val oldFailure = work(WorkInfo.State.FAILED, 100)
        val newFailure = work(WorkInfo.State.FAILED, 200)
        val success = work(WorkInfo.State.SUCCEEDED, 300, "downloaded")
        val cancelled = work(WorkInfo.State.CANCELLED, 400, "cancelled")
        val entries = listOf(oldFailure, success, newFailure, cancelled)
        assertEquals(mapOf("song" to newFailure), DownloadWork.current(entries))
        assertEquals(DownloadWork.current(entries), DownloadWork.current(entries.reversed()))
    }
}
