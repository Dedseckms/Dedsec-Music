package ru.dedseckms.music.data

import org.junit.Assert.*
import org.junit.Test

class NeighborArtworkTest {
    private fun song(id: String, cover: String) = Song(id, id, "Artist", coverArt = cover)

    @Test fun warmsTheActualNextAndPreviousTracksAtPlayerAndNotificationSizes() {
        val targets = neighborArtwork(song("playing", "a"), song("shuffled-next", "z"), song("previous", "b"))
        assertEquals(listOf(CoverTarget("a", 192), CoverTarget("a", 768), CoverTarget("z", 768), CoverTarget("z", 192),
            CoverTarget("b", 768), CoverTarget("b", 192)), targets)
    }

    @Test fun sameAlbumAcrossNeighboursIsFetchedOnlyOncePerSize() {
        assertEquals(listOf(CoverTarget("album", 192), CoverTarget("album", 768)),
            neighborArtwork(song("1", "album"), song("2", "album"), song("3", "album")))
    }

    @Test fun missingArtAndQueueBoundariesNeverCreateInvalidRequests() {
        assertTrue(neighborArtwork(song("1", ""), null, null).isEmpty())
        assertEquals(listOf(CoverTarget("next", 768), CoverTarget("next", 192)), neighborArtwork(null, song("2", "next"), null))
    }
}
