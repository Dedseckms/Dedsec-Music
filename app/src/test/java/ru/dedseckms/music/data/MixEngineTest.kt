package ru.dedseckms.music.data

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class MixEngineTest {
    private val songs = (1..30).map { Song("$it", "Song $it", "Artist ${it % 6}") }
    @Test fun dislikesAndDuplicatesAreExcluded() {
        val selected = MixEngine.build(songs + songs, emptySet(), emptyList(), setOf("1", "2"), size = 100, random = Random(5))
        assertEquals(28, selected.size)
        assertEquals(selected.size, selected.map { it.id }.toSet().size)
        assertTrue(selected.none { it.id in setOf("1", "2") })
    }
    @Test fun favoriteMixCannotContainOtherSongs() {
        val selected = MixEngine.build(songs, setOf("1", "4", "6"), emptyList(), emptySet(), MixMode.FAVORITES, random = Random(1))
        assertEquals(setOf("1", "4", "6"), selected.map { it.id }.toSet())
    }
    @Test fun artistsAreSpacedWhenAlternativesExist() {
        val selected = MixEngine.build(songs, emptySet(), emptyList(), emptySet(), size = 18, random = Random(3))
        selected.windowed(3).forEach { assertEquals(3, it.map(Song::artist).distinct().size) }
    }
    @Test fun emptyLibraryAndFavoriteListAreHandled() {
        assertTrue(MixEngine.build(emptyList(), emptySet(), emptyList(), emptySet()).isEmpty())
        assertTrue(MixEngine.build(songs, emptySet(), emptyList(), emptySet(), MixMode.FAVORITES).isEmpty())
    }
}
