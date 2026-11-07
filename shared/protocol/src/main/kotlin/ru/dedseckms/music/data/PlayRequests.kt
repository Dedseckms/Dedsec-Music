package ru.dedseckms.music.data

import java.util.UUID

data class PlayRequest(val songs: List<Song>, val selected: Int, val mix: Boolean, val mode: MixMode, val mood: MixMood = MixMood.ANY, val source: String = "")

/** Large playlists stay in-process; the controller sends a single, account-bound reference. */
class PlayRequests {
    private val pending = LinkedHashMap<String, Pair<String, PlayRequest>>()
    @Synchronized fun offer(account: String, songs: List<Song>, selected: Int, mix: Boolean, mode: MixMode, mood: MixMood = MixMood.ANY, source: String = ""): String {
        require(songs.isNotEmpty())
        val token = UUID.randomUUID().toString()
        pending[token] = account to PlayRequest(songs.toList(), selected.coerceIn(songs.indices), mix, mode, mood, source)
        while (pending.size > 4) pending.remove(pending.keys.first())
        return token
    }
    @Synchronized fun take(token: String, account: String): PlayRequest? {
        val request = pending[token]?.takeIf { it.first == account } ?: return null
        pending.remove(token)
        return request.second
    }
    @Synchronized fun clear() { pending.clear() }
}

class QueueWindow(val request: PlayRequest) {
    private var start = (request.selected - 10).coerceAtLeast(0)
    private var end = (start + 40).coerceAtMost(request.songs.size)
    val initial: List<Song> get() = request.songs.subList(start, end)
    val initialIndex: Int get() = request.selected - start
    fun next(): List<Song> {
        val nextEnd = (end + 40).coerceAtMost(request.songs.size)
        return request.songs.subList(end, nextEnd).also { end = nextEnd }
    }
    fun previous(): List<Song> {
        val nextStart = (start - 40).coerceAtLeast(0)
        return request.songs.subList(nextStart, start).also { start = nextStart }
    }
}
