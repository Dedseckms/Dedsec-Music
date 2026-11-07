package ru.dedseckms.music.playback

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class EndedPlaybackTest {
    private class Fixture(var state: Int = Player.STATE_ENDED, val repeat: Int = Player.REPEAT_MODE_OFF, val count: Int = 1) {
        var ready = true
        var position = 123_000L
        val calls = mutableListOf<String>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getPlaybackState" -> state
                "getRepeatMode" -> repeat
                "getMediaItemCount" -> count
                "pause" -> { calls += "pause"; ready = false; null }
                "seekTo" -> { calls += "seek"; position = args!![0] as Long; state = Player.STATE_READY; null }
                else -> error("Unexpected player call: ${method.name}")
            }
        } as Player
    }
    @Test fun finishedSingleTrackBecomesPausedAtZero() {
        val f = Fixture()
        pauseAtEnd(f.player)
        assertEquals(listOf("pause", "seek"), f.calls)
        assertFalse(f.ready)
        assertEquals(0L, f.position)
        assertEquals(Player.STATE_READY, f.state)
    }
    @Test fun repeatedEndedCallbacksDoNotSeekTwice() {
        val f = Fixture()
        pauseAtEnd(f.player); pauseAtEnd(f.player)
        assertEquals(listOf("pause", "seek"), f.calls)
    }
    @Test fun theLastItemOfAQueueIsAlsoPreparedForReplay() {
        val f = Fixture(count = 3)
        pauseAtEnd(f.player)
        assertFalse(f.ready); assertEquals(0L, f.position)
    }
    @Test fun ordinaryPlaybackAndBufferingAreNotInterrupted() {
        for (state in listOf(Player.STATE_READY, Player.STATE_BUFFERING, Player.STATE_IDLE)) {
            val f = Fixture(state = state)
            pauseAtEnd(f.player)
            assertTrue(f.calls.isEmpty()); assertTrue(f.ready)
        }
    }
    @Test fun repeatModesKeepTheirNativeLoopingBehavior() {
        for (repeat in listOf(Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)) {
            val f = Fixture(repeat = repeat)
            pauseAtEnd(f.player)
            assertTrue(f.calls.isEmpty()); assertTrue(f.ready)
        }
    }
    @Test fun anEmptyQueueIsNotSought() {
        val f = Fixture(count = 0)
        pauseAtEnd(f.player)
        assertTrue(f.calls.isEmpty())
    }
}
