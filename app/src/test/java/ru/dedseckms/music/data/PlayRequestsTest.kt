package ru.dedseckms.music.data

import org.junit.Assert.*
import org.junit.Test

class PlayRequestsTest {
    @Test fun aLargePlaylistStartsWithASmallWindowAtTheSelectedTrack() {
        val songs = (0 until 10_000).map { Song("$it", "Track $it", "Artist") }
        val window = QueueWindow(PlayRequest(songs, 5_000, false, MixMode.BALANCED))
        assertEquals(40, window.initial.size)
        assertEquals("5000", window.initial[window.initialIndex].id)
        assertEquals((4990 until 5030).map(Int::toString), window.initial.map { it.id })
        assertEquals((5030 until 5070).map(Int::toString), window.next().map { it.id })
        assertEquals((4950 until 4990).map(Int::toString), window.previous().map { it.id })
    }

    @Test fun requestReferencesAreSingleUseAndBoundToTheirAccount() {
        val requests = PlayRequests()
        val token = requests.offer("anna", listOf(Song("song", "Song", "Artist")), 0, false, MixMode.BALANCED)
        assertNull(requests.take(token, "boris"))
        assertEquals("song", requests.take(token, "anna")!!.songs.single().id)
        assertNull(requests.take(token, "anna"))
    }

    @Test fun growingTheQueueDoesNotLoseOrDuplicateSourcePositions() {
        val songs = (0 until 107).map { Song("$it", "Song", "Artist") }
        val window = QueueWindow(PlayRequest(songs, 0, false, MixMode.BALANCED))
        val result = window.initial + window.next() + window.next()
        assertEquals(songs, result)
        assertTrue(window.next().isEmpty())
        assertTrue(window.previous().isEmpty())
    }
}
