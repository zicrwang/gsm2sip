package com.callagent.gateway.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAttemptTrackerTest {
    @Test
    fun timeoutFromPreviousCallCannotMatchNextCall() {
        val tracker = CallAttemptTracker()
        val firstCall = tracker.begin()

        tracker.invalidate()
        val secondCall = tracker.begin()

        assertFalse(tracker.isCurrent(firstCall))
        assertTrue(tracker.isCurrent(secondCall))
    }
}
