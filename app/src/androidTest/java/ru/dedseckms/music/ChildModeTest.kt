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

    @Test fun manualRatingRefreshesOfflineAndLibraryMetadataWithoutChangingAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = java.util.UUID.randomUUID().toString().replace("-", "")
        val store = LocalStore(context, account)
        val song = Song("rating-fixture", "Test", "Artist")
        val audio = byteArrayOf(1, 3, 5, 7)
        try {
            store.cacheSongs(listOf(song)); store.cacheFavorites(listOf(song)); store.record(song); store.queueDownload(song)
            store.audioFile(song.id).writeBytes(audio); store.downloaded(song, audio.size.toLong())
            store.updateContentRating(song.id, ContentRating.CLEAN, true)
            val copies = store.cachedSongs() + store.cachedFavorites() + store.history().map { it.song } + store.pendingDownloads() + store.offline()
            assertEquals(5, copies.size)
            copies.forEach { assertEquals(ContentRating.CLEAN, it.contentRating); assertTrue(it.contentRatingOverride) }
            assertArrayEquals(audio, store.audioFile(song.id).readBytes())
            store.updateContentRating(song.id, ContentRating.UNKNOWN, false)
            assertFalse(store.offline().single().contentRatingOverride)
            assertFalse(ContentPolicy.allowed(store.offline().single(), true))
            assertArrayEquals(audio, store.audioFile(song.id).readBytes())
        } finally {
            java.io.File(context.filesDir, "accounts/$account").deleteRecursively()
            context.getSharedPreferences("account_$account", 0).edit().clear().commit()
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
