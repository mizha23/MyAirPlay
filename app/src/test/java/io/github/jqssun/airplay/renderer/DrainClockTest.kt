package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [DrainClock] with a virtual clock.
 *
 * The reported TV failure (VAVA 2026-11-16, round-1 build): mirror with the
 * jitter buffer on froze on frame 1 — In 50 fps, Out 0 fps, queue filling
 * with "Jitter buffer full" drops, no drain-side log lines. The drain
 * thread computes a render timestamp from the sender's NTP PTS, and the
 * old code only clamped the far-future side: a PTS regression (a 0-PTS
 * frame — which the native layer legitimately emits before NTP sync
 * completes — arriving after a real-PTS anchor) produced a timestamp
 * ~10^18 ns in the past, which was passed to releaseOutputBuffer
 * unchecked. The platform only guarantees render-timestamp behaviour
 * within ~1s of System.nanoTime, so that value is vendor-defined.
 *
 * These tests pin the clamp with the real scenario numbers: sender NTP
 * PTS ~1.79e15 us (Unix-epoch microseconds), 0-PTS pre-sync frames.
 */
class DrainClockTest {
    @Test fun softwareReleaseSpreadsDecodedPairsWithoutVendorScheduling() {
        val clock = DrainClock(maxLateNs = 40_000_000L)
        var now = 1_000_000_000L
        val output = mutableListOf<Long>()
        for (i in 0 until 120) {
            now = maxOf(now, 1_000_000_000L + (i / 2) * 66_666_000L)
            now = maxOf(now, clock.releaseTimestampNs(10_000_000L + i * 33_333L, now)!!)
            output += now
        }
        assertTrue(output.zipWithNext { a, b -> b - a }.all { it == 33_333_000L })
    }
    @Test fun softwareReleaseReanchorsAfterDisplayStallRatherThanBursting() {
        val clock = DrainClock(maxLateNs = 40_000_000L)
        clock.releaseTimestampNs(10_000_000L, 1_000_000_000L)
        val late = 1_200_000_000L
        assertEquals(late, clock.releaseTimestampNs(10_033_333L, late))
        assertEquals(late + 33_333_000L, clock.releaseTimestampNs(10_066_666L, late))
    }


    // Sender NTP clock, microseconds (~2026 Unix time). Real anchor value.
    private val ptsBaseUs = 1_790_000_000_000_000L
    private val nowNs = 60_000_000_000_000L // ~16.7h uptime, monotonic ns
    private val frameUs = 20_000L // 50fps in us

    @Test
    fun normalFrameSchedulesOnPace() {
        val clock = DrainClock()
        // First frame anchors: released at now.
        assertEquals(nowNs, clock.releaseTimestampNs(ptsBaseUs, nowNs))
        // Next frame, one frame-interval later in PTS: +20ms.
        assertEquals(nowNs + 20_000_000L, clock.releaseTimestampNs(ptsBaseUs + frameUs, nowNs))
        assertEquals(0L, clock.reanchorCount)
    }

    @Test
    fun zeroPtsAfterRealAnchorReleasesImmediatelyWithoutPoisoning() {
        val clock = DrainClock()
        assertEquals(nowNs, clock.releaseTimestampNs(ptsBaseUs, nowNs))
        // A 0-PTS frame (pre-NTP-sync straggler) after the real anchor:
        // release immediately, no timestamp, anchor untouched.
        assertNull(clock.releaseTimestampNs(0L, nowNs + 1_000_000L))
        assertNull(clock.releaseTimestampNs(-5L, nowNs + 1_000_000L))
        assertEquals(ptsBaseUs, clock.ptsBaseUs)
        // The next real frame still paces from the original anchor.
        assertEquals(
            nowNs + 40_000_000L,
            clock.releaseTimestampNs(ptsBaseUs + 2 * frameUs, nowNs + 20_000_000L),
        )
        assertEquals(0L, clock.reanchorCount)
    }

    @Test
    fun smallPtsRegressionClampsToNowInsteadOfNegative() {
        val clock = DrainClock()
        assertEquals(nowNs, clock.releaseTimestampNs(ptsBaseUs, nowNs))
        // PTS 5 frames behind the anchor (regression, still positive):
        // the naive computation would be now - 100ms; must clamp to now.
        val ts = clock.releaseTimestampNs(ptsBaseUs - 5 * frameUs, nowNs + 20_000_000L)
        assertTrue("regressed PTS must never schedule in the past, got $ts vs now ${nowNs + 20_000_000L}", ts!! >= nowNs + 20_000_000L)
        assertTrue("regressed PTS must not re-anchor", clock.reanchorCount == 0L)
    }

    @Test
    fun farFuturePtsReanchorsAndClamps() {
        val clock = DrainClock()
        assertEquals(nowNs, clock.releaseTimestampNs(ptsBaseUs, nowNs))
        // PTS 1s ahead (stall / seek): re-anchor, release now.
        val later = nowNs + 50_000_000L
        assertEquals(later, clock.releaseTimestampNs(ptsBaseUs + 1_000_000L, later))
        assertEquals(1L, clock.reanchorCount)
        assertEquals(ptsBaseUs + 1_000_000L, clock.ptsBaseUs)
        // Pacing resumes from the new anchor.
        assertEquals(
            later + 20_000_000L,
            clock.releaseTimestampNs(ptsBaseUs + 1_000_000L + frameUs, later),
        )
    }

    @Test
    fun zeroPtsFirstFrameDoesNotAnchor() {
        val clock = DrainClock()
        // Session starts with 0-PTS frames (NTP sync not complete yet):
        // they release immediately and must not anchor at 0, or the first
        // real frame would schedule ~10^18 ns in the future.
        assertNull(clock.releaseTimestampNs(0L, nowNs))
        assertEquals(Long.MIN_VALUE, clock.ptsBaseUs)
        assertEquals(nowNs + 10_000_000L, clock.releaseTimestampNs(ptsBaseUs, nowNs + 10_000_000L))
        assertEquals(ptsBaseUs, clock.ptsBaseUs)
    }

    @Test
    fun extremePtsCannotOverflowOrEscapeWindow() {
        val clock = DrainClock()
        assertEquals(nowNs, clock.releaseTimestampNs(ptsBaseUs, nowNs))
        // A garbage PTS near the top of the range: no overflow, result
        // stays inside [now, now + 100ms] (via the re-anchor path).
        val ts = clock.releaseTimestampNs(Long.MAX_VALUE / 2, nowNs + 1_000_000L)
        assertTrue("extreme PTS must stay in the release window", ts!! in (nowNs + 1_000_000L)..(nowNs + 1_000_000L + DrainClock.MAX_RELEASE_AHEAD_NS))
    }
}
