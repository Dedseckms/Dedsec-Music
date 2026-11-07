package ru.dedseckms.music.data

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SearchAndMoodTest {
    @Test fun partialArtistAndTrackWordsMatchBeforeTheNameIsComplete() {
        assertTrue(SearchText.matches("sti", "Stint"))
        assertTrue(SearchText.matches("neff sho", "6 Shots NEFFEX"))
        assertTrue(SearchText.matches("ТИХ си", "Тихий сигнал"))
        assertFalse(SearchText.matches("neff hello", "6 Shots NEFFEX"))
    }
    @Test fun searchIgnoresExtraWhitespaceYoAndCompatibilityCharacters() {
        assertTrue(SearchText.matches("  ел  ", "Ёлки"))
        assertTrue(SearchText.matches("Ｍｒ", "Mr.Kitty"))
    }
    @Test fun focusMoodPrefersInstrumentalTagsButStillRespectsLikesAndDislikes() {
        val ambient = Song("ambient", "Instrument", "A", genres = listOf("Ambient"))
        val metal = Song("metal", "Loud", "B", genre = "Metal")
        val selected = (0..99).count { MixEngine.build(listOf(ambient, metal), emptySet(), emptyList(), emptySet(), size = 1, random = Random(it), mood = MixMood.FOCUS).single().id == "ambient" }
        assertTrue("Mood should alter selection, not just its label", selected > 85)
        assertEquals(listOf(metal), MixEngine.build(listOf(ambient, metal), setOf("metal"), emptyList(), emptySet(), MixMode.FAVORITES, mood = MixMood.FOCUS))
        assertEquals(listOf(metal), MixEngine.build(listOf(ambient, metal), emptySet(), emptyList(), setOf("ambient"), mood = MixMood.FOCUS))
    }
    @Test fun scarceMoodTagsNeverMakeAnOtherwiseAvailableLibraryEmpty() {
        val unknown = Song("u", "Unknown", "Artist")
        MixMood.entries.forEach { mood -> assertEquals(listOf(unknown), MixEngine.build(listOf(unknown), emptySet(), emptyList(), emptySet(), mood = mood)) }
    }
    @Test fun moodIsTransferredWithTheAccountBoundQueueForContinuation() {
        val requests = PlayRequests()
        val token = requests.offer("account", listOf(Song("1", "Track", "Artist")), 0, true, MixMode.DISCOVERY, MixMood.CHILL)
        assertNull(requests.take(token, "other"))
        assertEquals(MixMood.CHILL, requests.take(token, "account")!!.mood)
    }
}
