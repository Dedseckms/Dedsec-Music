package ru.dedseckms.music.data

import org.junit.Assert.*
import org.junit.Test

class PlaybackSourceTest {
    @Test fun requestsAndQueueWindowsKeepTheSelectedOrigin() {
        val requests = PlayRequests()
        val songs = (0..100).map { Song("$it", "Song", "Artist") }
        val token = requests.offer("owner", songs, 50, false, MixMode.BALANCED, source = collectionSource("playlist", "Ночной маршрут"))
        val request = requests.take(token, "owner")!!
        val window = QueueWindow(request)
        window.next(); window.previous()
        assertEquals("Плейлист · Ночной маршрут", window.request.source)
        assertEquals("Плейлист · Ночной маршрут", playbackSourceLabel(request.source, false, MixMood.SAD))
    }
    @Test fun waveUsesItsActualMoodAndAnOrdinaryQueueDoesNotInheritIt() {
        assertEquals("Моя волна · Чилл", playbackSourceLabel("Альбом · Test", true, MixMood.CHILL))
        assertEquals("Моя волна · Любое настроение", playbackSourceLabel("", true, MixMood.ANY))
        assertEquals("Альбом · Test", playbackSourceLabel(collectionSource("album", "Test"), false, MixMood.CHILL))
        assertEquals("Исполнитель · Stint", collectionSource("artist", "Stint"))
        assertEquals("Сейчас играет", playbackSourceLabel("", false, MixMood.ANY))
    }
}
