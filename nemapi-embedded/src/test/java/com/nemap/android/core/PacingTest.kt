package com.nemap.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PacingTest {
    @Test
    fun normalCadenceReturnsOnlyRemainingDelay() {
        Pacing.noteSubmit(100_000L)
        assertEquals(Pacing.MIN_INTERVAL_MS, Pacing.minimumIntervalWaitMs(100_000L))
        assertEquals(1L, Pacing.minimumIntervalWaitMs(107_999L))
        assertEquals(0L, Pacing.minimumIntervalWaitMs(108_000L))
    }

    @Test
    fun backwardsWallClockCannotCreateUnboundedWait() {
        Pacing.noteSubmit(200_000L)
        assertEquals(Pacing.MIN_INTERVAL_MS, Pacing.minimumIntervalWaitMs(100_000L))
    }
}
