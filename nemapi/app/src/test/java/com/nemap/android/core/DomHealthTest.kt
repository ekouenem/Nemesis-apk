package com.nemap.android.core

import org.junit.Assert.*
import org.junit.Test

/** JVM, hors reseau : aucun provider, aucun compte, aucune session. */
class DomHealthTest {
    @Test fun twoTimeoutsRemainTransient() {
        DomHealth.noteRestart()
        assertFalse(DomHealth.noteTimeout(8_000L, 0L))
        assertFalse(DomHealth.noteTimeout(8_000L, 50L))
        assertEquals(2, DomHealth.consecutiveTimeouts)
        assertFalse(DomHealth.unresponsive)
    }

    @Test fun thirdConsecutiveTimeoutFlagsTheEngine() {
        DomHealth.noteRestart()
        assertFalse(DomHealth.noteTimeout(8_000L, 0L))
        assertFalse(DomHealth.noteTimeout(8_000L, 0L))
        assertTrue(DomHealth.noteTimeout(8_000L, 0L))
        assertTrue(DomHealth.unresponsive)
    }

    @Test fun aSuccessResetsConsecutiveFailures() {
        DomHealth.noteRestart()
        DomHealth.noteTimeout(8_000L, 0L)
        DomHealth.noteTimeout(8_000L, 0L)
        DomHealth.noteSuccess()
        assertEquals(0, DomHealth.consecutiveTimeouts)
        assertFalse(DomHealth.unresponsive)
        assertFalse(DomHealth.noteTimeout(8_000L, 0L))
    }

    @Test fun explicitRestartClearsTheUnresponsiveState() {
        DomHealth.noteRestart()
        repeat(3) { DomHealth.noteTimeout(8_000L, 10L) }
        assertTrue(DomHealth.unresponsive)
        val before = DomHealth.restarts
        DomHealth.noteRestart()
        assertFalse(DomHealth.unresponsive)
        assertEquals(0, DomHealth.consecutiveTimeouts)
        assertEquals(before + 1, DomHealth.restarts)
    }
}
