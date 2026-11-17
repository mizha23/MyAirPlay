package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [StallWatchdog] with a virtual clock (all times ns).
 *
 * The watchdog must fire exactly when the decoder is wedged — fed but
 * producing nothing — and never when it is merely starved or healthy.
 */
class StallWatchdogTest {
    @Test fun newFrameAfterIdleGetsItsOwnDecodeDeadline() {
        val w = StallWatchdog()
        val start = 100_000_000_000L
        val oldOutput = start + 1_000_000_000L
        val resumed = start + 10_000_000_000L
        assertFalse(w.shouldRecover(resumed, start, resumed, oldOutput, resumed))
        assertFalse(w.shouldRecover(resumed + 100_000_000L, start, resumed, oldOutput, resumed))
        assertTrue(w.shouldRecover(resumed + 2_000_000_001L, start, resumed + 2_000_000_000L, oldOutput, resumed))
    }
    @Test fun fullyConsumedStaticFrameNeverTriggersRecovery() {
        val w = StallWatchdog()
        val start = 100_000_000_000L
        assertFalse(w.shouldRecover(start + 10_000_000_000L, start, start + 9_900_000_000L, start + 1_000_000_000L, 0L))
    }


    private val t0 = 100_000_000_000L // codec start, ns
    private val sec = 1_000_000_000L

    @Test
    fun noCodecNeverFires() {
        val w = StallWatchdog()
        assertFalse(w.shouldRecover(t0 + 10 * sec, 0L, t0 + 10 * sec, 0L, t0))
    }

    @Test
    fun noOutputWithInputFlowingFiresAfter2s() {
        val w = StallWatchdog()
        // Just under 2 s with no output yet: too early.
        assertFalse(w.shouldRecover(t0 + 2 * sec - 1, t0, t0 + sec, 0L, t0))
        // Just over 2 s, input fed 500 ms ago: wedged.
        assertTrue(w.shouldRecover(t0 + 2 * sec + 1, t0, t0 + 3 * sec / 2, 0L, t0))
    }

    @Test
    fun outputRecentlyArrivedDoesNotFire() {
        val w = StallWatchdog()
        // Output 100 ms ago, however long the session has run: healthy.
        assertFalse(w.shouldRecover(t0 + 60 * sec, t0, t0 + 60 * sec, t0 + 60 * sec - sec / 10, t0))
    }

    @Test
    fun outputStalledFor2sFires() {
        val w = StallWatchdog()
        // Last output 2 s + 1 ns ago, input fed 100 ms ago: wedged.
        val now = t0 + 30 * sec
        assertTrue(w.shouldRecover(now, t0, now - sec / 10, now - 2 * sec - 1, t0))
        // Last output 1.9 s ago: not yet.
        val w2 = StallWatchdog()
        assertFalse(w2.shouldRecover(now, t0, now - sec / 10, now - 2 * sec + sec / 10, t0))
    }

    @Test
    fun inputStoppedDoesNotFire() {
        val w = StallWatchdog()
        // Nothing fed for 3 s: starved, not wedged — no recovery.
        val now = t0 + 30 * sec
        assertFalse(w.shouldRecover(now, t0, now - 3 * sec, 0L, t0))
        assertFalse(w.shouldRecover(now, t0, 0L, 0L, t0))
    }

    @Test
    fun debouncePreventsImmediateRefire() {
        val w = StallWatchdog()
        val fireAt = t0 + 2 * sec + 1
        assertTrue(w.shouldRecover(fireAt, t0, fireAt - sec / 10, 0L, t0))
        // Still stalled 5 s later: inside the 10 s debounce, no refire.
        assertFalse(w.shouldRecover(fireAt + 5 * sec, t0, fireAt + 5 * sec - sec / 10, 0L, t0))
        // 10 s after the firing, still stalled: fires again.
        assertTrue(w.shouldRecover(fireAt + 10 * sec + 1, t0, fireAt + 10 * sec, 0L, t0))
    }

    @Test
    fun resetClearsDebounce() {
        val w = StallWatchdog()
        val fireAt = t0 + 2 * sec + 1
        assertTrue(w.shouldRecover(fireAt, t0, fireAt - sec / 10, 0L, t0))
        w.reset()
        assertTrue(w.shouldRecover(fireAt + sec, t0, fireAt + sec - sec / 10, 0L, t0))
    }
}
