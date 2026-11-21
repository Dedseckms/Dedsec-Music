@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package ru.dedseckms.music

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import ru.dedseckms.music.data.*
import ru.dedseckms.music.playback.MusicService

class ChildModeTest {
    @Test fun childPreferencePersistsPerAccountAndDefaultsOff() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstId = "cccccccccccccccccccccccccccccccc"
        val secondId = "dddddddddddddddddddddddddddddddd"
        try {
            context.getSharedPreferences("account_$firstId", 0).edit().clear().commit()
            context.getSharedPreferences("account_$secondId", 0).edit().clear().commit()
            assertFalse(LocalStore(context, firstId).childMode())
            LocalStore(context, firstId).childMode(true)
            assertTrue(LocalStore(context, firstId).childMode())
            assertFalse(LocalStore(context, secondId).childMode())
        } finally {
            context.getSharedPreferences("account_$firstId", 0).edit().clear().commit()
            context.getSharedPreferences("account_$secondId", 0).edit().clear().commit()
        }
    }

    @Test fun mediaSessionSongExtrasRetainRatingsAndFailClosedForOldSongs() {
        for (rating in ContentRating.entries) {
            val song = Song("test", "Test", "Artist", contentRating = rating)
            val restored = MusicService.songFromItem(MusicService.item(song))!!
            assertEquals(rating, restored.contentRating)
            assertEquals(rating == ContentRating.CLEAN, ContentPolicy.allowed(restored, true))
        }
        val legacy = Song.parse(org.json.JSONObject().put("id", "old"))
        assertFalse(ContentPolicy.allowed(MusicService.songFromItem(MusicService.item(legacy))!!, true))
    }
}
