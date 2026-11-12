package ru.dedseckms.music.desktop

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossfadeTest {
    @Test fun transitionHasNoPowerDipAndDoesNotExceedEitherDeckVolume() {
        for (step in 0..100) {
            val (old, fresh) = crossfadeGains(step / 100.0)
            assertTrue(old in 0.0..1.0 && fresh in 0.0..1.0)
            assertEquals(1.0, old * old + fresh * fresh, .000001)
        }
        assertEquals(1.0 to 0.0, crossfadeGains(-1.0))
        assertEquals(1.0, crossfadeGains(2.0).second, .000001)
    }
}
