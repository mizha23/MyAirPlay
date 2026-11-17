package io.github.jqssun.airplay.renderer

import org.junit.Assert.*
import org.junit.Test

class CodecInputWaitTest {
    @Test fun transientFullBufferDoesNotDropReferenceFrameAtSixtyMs() {
        val wait = CodecInputWait(0L, false)
        var now = 0L
        // A decoder releases the next buffer at 160ms during rotation.
        // The old 3x20ms policy lost this frame and forced IDR recovery.
        while (now < 160_000_000L) {
            val us = wait.nextTimeoutUs(now, true, true)
            assertTrue(us > 0)
            now += us * 1000L
        }
        assertTrue(wait.nextTimeoutUs(now, true, true) > 0)
    }
    @Test fun permanentStallRemainsBounded() {
        val wait = CodecInputWait(10_000_000L, false)
        assertTrue(wait.nextTimeoutUs(509_000_000L, true, true) > 0)
        assertEquals(0L, wait.nextTimeoutUs(510_000_000L, true, true))
    }
    @Test fun stoppedSessionCancelsImmediately() {
        assertEquals(0L, CodecInputWait(0L, true).nextTimeoutUs(1L, false, true))
    }
    @Test fun replacedCodecCancelsImmediately() {
        assertEquals(0L, CodecInputWait(0L, true).nextTimeoutUs(1L, true, false))
    }
    @Test fun firstFrameGetsStartupBudget() {
        val wait = CodecInputWait(0L, true)
        assertEquals(20_000L, wait.nextTimeoutUs(800_000_000L, true, true))
        assertEquals(0L, wait.nextTimeoutUs(1_000_000_000L, true, true))
    }
}
