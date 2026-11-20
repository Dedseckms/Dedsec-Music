package ru.dedseckms.music.desktop

import kotlin.test.*
import org.junit.Test

class RepeatTransitionTest {
    @Test fun enteringRadioDropsQueueRepeatAndShuffle() {
        val next = DesktopState(repeat = true, shuffle = true).beginQueue(true, "Моя волна")
        assertTrue(next.wave); assertFalse(next.repeat); assertFalse(next.repeatOne); assertFalse(next.shuffle)
    }
    @Test fun aNewContextDoesNotInheritSingleTrackLoop() {
        assertFalse(DesktopState(wave = true, repeatOne = true).beginQueue(false, "Альбом").repeatOne)
        assertFalse(DesktopState(repeatOne = true).beginQueue(true, "Моя волна").repeatOne)
        assertTrue(DesktopState(repeatOne = true).beginQueue(false, "Плейлист").repeatOne)
    }
}
