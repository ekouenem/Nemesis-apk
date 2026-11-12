package com.nemap.android.core
import org.junit.Assert.*
import org.junit.Test
class ChallengeGraceTest {
 @Test fun onlyWaitsForBoundedTimeAndTracksNewRequests() {
  val grace=ChallengeGrace(12000)
  assertTrue(grace.shouldWait(1,1000)); assertTrue(grace.shouldWait(1,12999))
  assertFalse(grace.shouldWait(1,13000)); assertTrue(grace.shouldWait(2,14000))
  assertFalse(grace.shouldWait(2,26000))
 }
}
