package ru.dedseckms.music.data

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Cancellation plus ownership checks protect against late results from blocking IO. */
internal class LatestTask {
    private val revision = AtomicLong()
    private var job: Job? = null

    fun cancel() {
        revision.incrementAndGet()
        job?.cancel()
        job = null
    }

    fun capture(): Long = revision.get()
    fun isCurrent(token: Long): Boolean = revision.get() == token

    fun launch(scope: CoroutineScope, block: suspend (isCurrent: () -> Boolean) -> Unit) {
        cancel()
        val token = capture()
        job = scope.launch { block { isCurrent(token) } }
    }
}
