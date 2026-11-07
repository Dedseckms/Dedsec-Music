package ru.dedseckms.music.desktop

import ru.dedseckms.music.data.Song
import kotlin.random.Random

/** Source indices distinguish repeated songs and keep back/forward consistent after shuffle. */
class QueueState {
    var songs: List<Song> = emptyList(); private set
    var order: List<Int> = emptyList(); private set
    var cursor = 0; private set
    var shuffled = false; private set
    val current: Song? get() = order.getOrNull(cursor)?.let(songs::get)
    fun set(items: List<Song>, index: Int) { songs = items.toList(); order = songs.indices.toList(); cursor = index.coerceIn(0, (songs.size - 1).coerceAtLeast(0)); shuffled = false }
    fun shuffle(random: Random = Random.Default) {
        val currentIndex = order.getOrNull(cursor) ?: return
        shuffled = !shuffled
        order = if (shuffled) listOf(currentIndex) + songs.indices.filterNot { it == currentIndex }.shuffled(random) else songs.indices.toList()
        cursor = order.indexOf(currentIndex)
    }
    fun move(forward: Boolean, repeat: Boolean): Boolean {
        if (order.isEmpty()) return false
        val target = cursor + if (forward) 1 else -1
        if (target !in order.indices && !repeat) return false
        cursor = Math.floorMod(target, order.size)
        return true
    }
    fun select(position: Int): Boolean { if (position !in order.indices) return false; cursor = position; return true }
}
