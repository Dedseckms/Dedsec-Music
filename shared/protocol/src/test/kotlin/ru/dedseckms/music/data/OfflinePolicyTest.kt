package ru.dedseckms.music.data

import org.junit.Assert.*
import org.junit.Test

class OfflinePolicyTest {
    @Test fun shortTrackIsEligibleAfterActualListeningAndOnlyOnce() {
        val gate = OfflineListenGate()
        assertFalse(gate.observe("account:track", true, 0, 10_000, 0))
        for (i in 1..7) assertFalse(gate.observe("account:track", true, i * 1000L, 10_000, i * 1000L))
        assertTrue(gate.observe("account:track", true, 8000, 10_000, 8000))
        assertFalse(gate.observe("account:track", true, 9000, 10_000, 9000))
    }
    @Test fun seekingToEndOrPausingDoesNotCountAsListening() {
        val gate = OfflineListenGate()
        gate.observe("a:x", true, 0, 100_000, 0)
        assertFalse(gate.observe("a:x", true, 90_000, 100_000, 1000))
        for (i in 2..100) assertFalse(gate.observe("a:x", false, 90_000, 100_000, i * 1000L))
    }
    @Test fun switchingAccountResetsAccumulationEvenForTheSameTrack() {
        val gate = OfflineListenGate()
        for (i in 0..7) gate.observe("a:x", true, i * 1000L, 10_000, i * 1000L)
        assertFalse(gate.observe("b:x", true, 8000, 10_000, 8000))
    }
}
