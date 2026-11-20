package ru.dedseckms.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRepeatTest {
    @Test fun ordinaryQueueCyclesThroughAllAndOneThenOff() {
        var mode = PlaybackRepeat.OFF
        val actual = (1..6).map { mode = mode.next(false); mode }
        assertEquals(listOf(PlaybackRepeat.ALL, PlaybackRepeat.ONE, PlaybackRepeat.OFF, PlaybackRepeat.ALL, PlaybackRepeat.ONE, PlaybackRepeat.OFF), actual)
    }
    @Test fun waveNeverEnablesQueueRepeatIncludingInheritedAll() {
        assertEquals(PlaybackRepeat.ONE, PlaybackRepeat.OFF.next(true))
        assertEquals(PlaybackRepeat.OFF, PlaybackRepeat.ONE.next(true))
        assertEquals(PlaybackRepeat.ONE, PlaybackRepeat.ALL.next(true))
    }
}
