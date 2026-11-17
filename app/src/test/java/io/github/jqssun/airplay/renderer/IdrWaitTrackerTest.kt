package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [IdrWaitTracker] (round 6 P1-2), with a virtual clock.
 *
 * The rule: an ordinary session start clears the IDR wait, but a
 * recovery-triggered session restart re-arms a fresh deadline — the
 * restart must not disarm the only no-IDR timeout.
 */
class IdrWaitTrackerTest {

    @Test
    fun armThenDueAtDeadline() {
        val t = IdrWaitTracker(timeoutNs = 3_000L)
        t.arm(1_000L)
        assertTrue(t.isWaiting())
        assertEquals(1_000L, t.waitStartedNs)
        assertEquals(4_000L, t.deadlineNs)
        assertFalse(t.isDue(3_999L))
        assertTrue(t.isDue(4_000L))
    }

    @Test
    fun ordinarySessionStartClears() {
        val t = IdrWaitTracker(timeoutNs = 3_000L)
        t.arm(0L)
        assertFalse(t.onSessionStart(5_000L))
        assertFalse(t.isWaiting())
        assertFalse(t.isDue(100_000L))
    }

    @Test
    fun recoveryRestartRearmsDeadline() {
        val t = IdrWaitTracker(timeoutNs = 3_000L)
        t.arm(0L)
        t.markRecoveryRestartPending()
        assertTrue(t.onSessionStart(10_000L))
        assertTrue(t.isWaiting())
        assertEquals(10_000L, t.waitStartedNs)
        assertEquals(13_000L, t.deadlineNs)
        assertFalse(t.isDue(12_999L))
        assertTrue(t.isDue(13_000L))
    }

    @Test
    fun pendingFlagConsumedByFirstStart() {
        val t = IdrWaitTracker(timeoutNs = 3_000L)
        t.arm(0L)
        t.markRecoveryRestartPending()
        assertTrue(t.onSessionStart(10_000L))
        // The next start is an ordinary one again: the wait clears.
        assertFalse(t.onSessionStart(20_000L))
        assertFalse(t.isWaiting())
    }

    @Test
    fun clearKeepsPendingFlag() {
        // The restart posts stopSession() then startSession(): the stop
        // clears the deadline, but the flag must survive it so the
        // start can re-arm the wait.
        val t = IdrWaitTracker(timeoutNs = 3_000L)
        t.arm(0L)
        t.markRecoveryRestartPending()
        t.clear()
        assertFalse(t.isWaiting())
        assertTrue(t.recoveryRestartPending)
        assertTrue(t.onSessionStart(5_000L))
        assertTrue(t.isWaiting())
    }

    @Test
    fun clearAfterCodecStarted() {
        val t = IdrWaitTracker(timeoutNs = 3_000L)
        t.arm(0L)
        t.clear()
        assertFalse(t.isWaiting())
        assertFalse(t.isDue(Long.MAX_VALUE))
        assertEquals(0L, t.deadlineNs)
        assertEquals(0L, t.waitStartedNs)
    }
}
