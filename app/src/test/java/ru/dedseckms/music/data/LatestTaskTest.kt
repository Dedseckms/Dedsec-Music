package ru.dedseckms.music.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LatestTaskTest {
    @Test fun aLateResponseIgnoringCancellationCannotReplaceANewerSelection() = runBlocking {
        val task = LatestTask()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        task.launch(this) { current ->
            withContext(NonCancellable) { entered.complete(Unit); release.await() }
            if (current()) published += "old album or mood"
        }
        entered.await()
        task.launch(this) { current -> if (current()) published += "new selection" }
        yield()
        release.complete(Unit)
        coroutineContext.job.children.toList().joinAll()
        assertEquals(listOf("new selection"), published)
    }

    @Test fun backOrManualPlaybackInvalidatesBothPendingAndPaginatedResponses() = runBlocking {
        val task = LatestTask()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var published = false
        task.launch(this) { current ->
            withContext(NonCancellable) { entered.complete(Unit); release.await() }
            if (current()) published = true
        }
        entered.await()
        val pagination = task.capture()
        task.cancel()
        release.complete(Unit)
        coroutineContext.job.children.toList().joinAll()
        assertFalse(published)
        assertFalse(task.isCurrent(pagination))
    }
}
