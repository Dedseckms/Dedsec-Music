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
            store.updateContentRating(song, ContentRating.CLEAN, true)
            val copies = store.cachedSongs() + store.cachedFavorites() + store.history().map { it.song } + store.pendingDownloads() + store.offline()
            assertEquals(5, copies.size)
            copies.forEach { assertEquals(ContentRating.CLEAN, it.contentRating); assertTrue(it.contentRatingOverride) }
            assertArrayEquals(audio, store.audioFile(song.id).readBytes())
            store.updateContentRating(song, ContentRating.UNKNOWN, false)
            assertFalse(store.offline().single().contentRatingOverride)
            assertFalse(ContentPolicy.allowed(store.offline().single(), true))
            assertArrayEquals(audio, store.audioFile(song.id).readBytes())
        } finally {
            java.io.File(context.filesDir, "accounts/$account").deleteRecursively()
            context.getSharedPreferences("account_$account", 0).edit().clear().commit()
        }
    }

    @Test fun staleDownloadAndLibraryWritersCannotUndoCompletedRatingMutation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = java.util.UUID.randomUUID().toString().replace("-", "")
        val store = LocalStore(context, account)
        val staleClean = Song("stale-download", "Test", "Artist", contentRating = ContentRating.CLEAN)
        val audio = byteArrayOf(2, 4, 6, 8)
        try {
            store.childMode(false)
            store.audioFile(staleClean.id).writeBytes(audio)
            store.updateContentRating(staleClean, ContentRating.EXPLICIT, true)
            // These are the real publication paths used by an already-running worker and older HTTP results.
            store.downloaded(staleClean, audio.size.toLong())
            store.cacheSongs(listOf(staleClean)); store.cacheFavorites(listOf(staleClean))
            store.record(staleClean); store.queueDownload(staleClean)
            val reloaded = LocalStore(context, account)
            val copies = reloaded.offline() + reloaded.cachedSongs() + reloaded.cachedFavorites() +
                reloaded.history().map { it.song } + reloaded.pendingDownloads()
            assertEquals(5, copies.size)
            copies.forEach { assertEquals(ContentRating.EXPLICIT, it.contentRating); assertTrue(it.contentRatingOverride) }
            reloaded.childMode(true)
            assertFalse(ContentPolicy.allowed(reloaded.offline().single(), reloaded.childMode()))
            assertArrayEquals(audio, reloaded.audioFile(staleClean.id).readBytes())
            reloaded.updateContentRating(staleClean, ContentRating.CLEAN, true)
            val replacedRecording = staleClean.copy(size = 123456L, contentRating = ContentRating.UNKNOWN)
            reloaded.cacheSongs(listOf(replacedRecording))
            assertEquals(ContentRating.UNKNOWN, reloaded.cachedSongs().single().contentRating)
            assertFalse(reloaded.cachedSongs().single().contentRatingOverride)
            assertFalse(ContentPolicy.allowed(reloaded.cachedSongs().single(), true))
            reloaded.updateContentRating(staleClean, ContentRating.UNKNOWN, false)
            reloaded.downloaded(staleClean, audio.size.toLong())
            assertEquals(ContentRating.UNKNOWN, reloaded.offline().single().contentRating)
            assertFalse(reloaded.offline().single().contentRatingOverride)
        } finally {
            java.io.File(context.filesDir, "accounts/$account").deleteRecursively()
            context.getSharedPreferences("account_$account", 0).edit().clear().commit()
        }
    }

    @Test fun freshAuthoritativeRatingSupersedesSnapshotButOlderResponsesDoNot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = java.util.UUID.randomUUID().toString().replace("-", "")
        val store = LocalStore(context, account)
        val song = Song("fresh-rating", "Test", "Artist", contentRating = ContentRating.UNKNOWN)
        try {
            store.updateContentRating(song, ContentRating.CLEAN, true)
            val oldResponse = song.copy(contentRating = ContentRating.EXPLICIT, contentRatingCheckedAt = 1L)
            assertEquals(ContentRating.CLEAN, store.contentRatedSong(oldResponse).contentRating)
            val freshResponse = song.copy(contentRating = ContentRating.EXPLICIT, contentRatingCheckedAt = System.currentTimeMillis() + 60_000L)
            val fresh = store.contentRatedSong(freshResponse)
            assertEquals(ContentRating.EXPLICIT, fresh.contentRating)
            assertFalse(fresh.contentRatingOverride)
            assertFalse(ContentPolicy.allowed(fresh, true))
            val staleWorker = song.copy(contentRating = ContentRating.CLEAN)
            store.audioFile(song.id).writeBytes(byteArrayOf(1, 2))
            store.downloaded(staleWorker, 2L)
            assertEquals(ContentRating.EXPLICIT, store.offline().single().contentRating)
            assertFalse(ContentPolicy.allowed(store.offline().single(), true))
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
