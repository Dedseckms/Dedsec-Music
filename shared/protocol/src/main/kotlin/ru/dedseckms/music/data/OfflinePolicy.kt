package ru.dedseckms.music.data

data class OfflinePreferences(val afterListen: Boolean = false, val onFavorite: Boolean = false)

/** Counts actual playback, excluding seeks, pauses and time spent buffering. */
class OfflineListenGate {
    private var key = ""
    private var position = 0L
    private var time = 0L
    private var wasPlaying = false
    private var listened = 0L
    private var offered = false
    fun observe(track: String, playing: Boolean, positionMs: Long, durationMs: Long, clockMs: Long): Boolean {
        val elapsed = clockMs - time
        val advance = positionMs - position
        if (track != key) { key = track; listened = 0; offered = false }
        else if (wasPlaying && playing && elapsed in 1..5000 && advance in 1..(elapsed + 1500)) {
            listened += minOf(elapsed, advance)
        }
        position = positionMs; time = clockMs; wasPlaying = playing
        if (!offered && durationMs > 0 && listened >= durationMs * 4 / 5) { offered = true; return true }
        return false
    }
}
