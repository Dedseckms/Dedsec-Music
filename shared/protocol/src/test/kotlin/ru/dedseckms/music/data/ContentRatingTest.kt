package ru.dedseckms.music.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContentRatingTest {
    private fun song(id: String, rating: ContentRating) = Song(id, id, "Artist", contentRating = rating)
    @Test fun ratingRoundTripsAndOldCacheIsUnknown() {
        for (rating in ContentRating.entries) assertEquals(rating, Song.parse(song("a", rating).json()).contentRating)
        assertEquals(ContentRating.UNKNOWN, Song.parse(JSONObject().put("id", "old")).contentRating)
        assertEquals(ContentRating.UNKNOWN, ContentRating.parse("adult-ish"))
    }
    @Test fun blockedSelectionNeverSilentlyPlaysAnotherRecording() {
        val songs = listOf(song("explicit", ContentRating.EXPLICIT), song("clean", ContentRating.CLEAN), song("old", ContentRating.UNKNOWN))
        assertNull(ContentPolicy.selection(songs, 0, true))
        assertNull(ContentPolicy.selection(songs, 2, true))
        assertEquals(ContentSelection(listOf(songs[1]), 0), ContentPolicy.selection(songs, 1, true))
        assertEquals(ContentSelection(songs, 0), ContentPolicy.selection(songs, 0, false))
    }
    @Test fun filteringPreservesRepeatedEntriesAndSelectedOccurrence() {
        val clean = song("repeat", ContentRating.CLEAN)
        val songs = listOf(clean, song("adult", ContentRating.EXPLICIT), clean)
        assertEquals(ContentSelection(listOf(clean, clean), 1), ContentPolicy.selection(songs, 2, true))
    }
}
