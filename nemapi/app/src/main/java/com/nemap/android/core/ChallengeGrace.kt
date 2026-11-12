package com.nemap.android.core

/** Wait for the page itself to react; this does not submit or bypass anything. */
internal class ChallengeGrace(private val waitMs: Long = 12_000L) {
    private var record = Long.MIN_VALUE
    private var startedAt = 0L
    fun shouldWait(id: Long, now: Long): Boolean {
        if (id != record) { record = id; startedAt = now }
        return now - startedAt < waitMs
    }
}
