package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [PtsRebaser] with a virtual clock.
 *
 * The reported TV failure (VAVA 2026-11-16 18:08 session, device time
 * 10:07:23–10:08:04): mirror froze on frame 1 — In 49 fps, Out 0 fps,
 * drain released=1 dequeues=1 errors=0 with a steady 5 s heartbeat, and
 * at teardown the decoder's flushOutput returned 4 decoded frames it had
 * been holding (logcat 10:08:04.829). Root cause: the sender's PTS is its
 * NTP clock (Unix-epoch microseconds, ~1.79e15 us) while the Amlogic
 * decoder's display thread schedules output against CLOCK_MONOTONIC
 * (device uptime). Feeding NTP-epoch timestamps made the decoder hold
 * every decoded frame ~57 years.
 *
 * These tests pin the rebase with the real scenario numbers: sender NTP
 * PTS ~1.79e15 us, VAVA uptime 99,404 s (~27.6 h) at diagnosis time.
 *
 * Round 4 (VAVA 2026-11-16 18:38 retest still frozen on frame 1): the
 * round-3 fix still fed 0 for pre-sync frames and then jumped straight to
 * device uptime on the first valid PTS — `0 → 0 → <tens of hours in us>`.
 * The rebaser now guarantees one continuous, strictly increasing fed-PTS
 * timeline per session: 0-PTS frames get synthetic timestamps and the
 * first valid PTS continues the fed timeline instead of jumping to now.
 */
class PtsRebaserTest {

    // Sender NTP clock, microseconds (~2026 Unix time). Real anchor value.
    private val ptsBaseUs = 1_790_000_000_000_000L
    // VAVA monotonic clock at diagnosis time: 99,404 s uptime, in us.
    private val nowUs = 99_404_000_000L
    private val frameUs = 20_408L // ~49fps in us

    @Test
    fun rawNtpPtsIsDecadesAheadOfMonotonic() {
        // The mismatch itself: the raw sender PTS sits ~1.79e9 s (~57 years)
        // ahead of the decoder's monotonic clock. Feeding it raw is what
        // wedged the decoder's display thread.
        val gapSeconds = (ptsBaseUs - nowUs) / 1_000_000L
        assertTrue("expected ~57y gap, got ${gapSeconds}s", gapSeconds > 1_700_000_000L)
    }

    @Test
    fun firstValidPtsAnchorsToNow() {
        val rebaser = PtsRebaser()
        assertEquals(nowUs, rebaser.rebasePtsUs(ptsBaseUs, nowUs))
        assertEquals(0L, rebaser.reanchorCount)
    }

    @Test
    fun nextFrameSchedulesOnPace() {
        val rebaser = PtsRebaser()
        assertEquals(nowUs, rebaser.rebasePtsUs(ptsBaseUs, nowUs))
        // Next frame, one frame-interval later in PTS: +20.408ms in the
        // monotonic domain, not +1.79e15 us.
        assertEquals(nowUs + frameUs, rebaser.rebasePtsUs(ptsBaseUs + frameUs, nowUs))
        assertEquals(0L, rebaser.reanchorCount)
    }

    @Test
    fun zeroPtsFeedsSyntheticContinuously() {
        // The round-4 fix: pre-sync 0-PTS frames no longer feed as 0.
        // They get synthetic timestamps on the fed timeline, and the first
        // valid PTS continues that timeline instead of jumping to nowUs.
        val rebaser = PtsRebaser()
        val f1 = rebaser.rebasePtsUs(0L, nowUs)
        val f2 = rebaser.rebasePtsUs(0L, nowUs + frameUs)
        val f3 = rebaser.rebasePtsUs(0L, nowUs + 2 * frameUs)
        val f4 = rebaser.rebasePtsUs(ptsBaseUs, nowUs + 3 * frameUs)
        val f5 = rebaser.rebasePtsUs(ptsBaseUs + frameUs, nowUs + 4 * frameUs)
        assertEquals(nowUs, f1)
        assertEquals(nowUs + 20_000L, f2) // default interval before learning
        assertEquals(nowUs + 40_000L, f3)
        assertEquals(nowUs + 60_000L, f4) // continues the fed timeline
        assertEquals(nowUs + 60_000L + frameUs, f5) // real interval learned
        assertTrue(f1 < f2 && f2 < f3 && f3 < f4 && f4 < f5)
        assertTrue("no fed step may exceed the jump threshold",
            listOf(f2 - f1, f3 - f2, f4 - f3, f5 - f4).all { it in 1L..500_000L })
        assertEquals(0L, rebaser.jumpCount)
        assertEquals(0L, rebaser.reanchorCount)
    }

    @Test
    fun negativePtsFeedsSyntheticToo() {
        val rebaser = PtsRebaser()
        assertEquals(nowUs, rebaser.rebasePtsUs(-5L, nowUs))
        assertEquals(nowUs + 20_000L, rebaser.rebasePtsUs(-5L, nowUs + frameUs))
        assertEquals(0L, rebaser.jumpCount)
    }

    @Test
    fun farFutureJumpReanchors() {
        val rebaser = PtsRebaser()
        assertEquals(nowUs, rebaser.rebasePtsUs(ptsBaseUs, nowUs))
        // PTS jumps 10 s ahead (stall / seek forward): re-anchor to now
        // instead of scheduling the frame 10 s out. The returned value may
        // be nowUs + 1 (strictly-increasing enforcement over the first
        // frame's nowUs), never nowUs itself and never 10 s out.
        val jumped = rebaser.rebasePtsUs(ptsBaseUs + 10_000_000L, nowUs)
        assertTrue("re-anchored value must be ~now, got $jumped", jumped in nowUs..nowUs + frameUs)
        assertEquals(1L, rebaser.reanchorCount)
        // Pacing resumes from the new anchor.
        assertEquals(nowUs + frameUs, rebaser.rebasePtsUs(ptsBaseUs + 10_000_000L + frameUs, nowUs))
    }

    @Test
    fun farPastJumpReanchors() {
        val rebaser = PtsRebaser()
        assertEquals(nowUs, rebaser.rebasePtsUs(ptsBaseUs, nowUs))
        // PTS jumps 10 s behind (seek back / new stream): re-anchor.
        val jumped = rebaser.rebasePtsUs(ptsBaseUs - 10_000_000L, nowUs)
        assertTrue("re-anchored value must be ~now, got $jumped", jumped in nowUs..nowUs + frameUs)
        assertEquals(1L, rebaser.reanchorCount)
    }

    @Test
    fun forwardJumpOverThresholdIsCounted() {
        val rebaser = PtsRebaser()
        assertEquals(nowUs, rebaser.rebasePtsUs(ptsBaseUs, nowUs))
        // A 600 ms forward step stays inside the re-anchor window (1 s),
        // so it feeds as-is — but it counts as a jump for the live log.
        assertEquals(nowUs + 600_000L, rebaser.rebasePtsUs(ptsBaseUs + 600_000L, nowUs))
        assertEquals(1L, rebaser.jumpCount)
        assertEquals(600_000L, rebaser.lastJumpUs)
        assertEquals(0L, rebaser.reanchorCount)
    }

    @Test
    fun smallJitterDoesNotReanchor() {
        val rebaser = PtsRebaser()
        assertEquals(nowUs, rebaser.rebasePtsUs(ptsBaseUs, nowUs))
        // A frame 100 ms late (arrival jitter / pacer delay) feeds with its
        // rebased PTS — which is still ~80 ms in the past, so the decoder
        // displays it immediately — and does not disturb the anchor.
        assertEquals(nowUs + frameUs, rebaser.rebasePtsUs(ptsBaseUs + frameUs, nowUs + 100_000L))
        assertEquals(0L, rebaser.reanchorCount)
        assertEquals(0L, rebaser.jumpCount)
    }

    @Test
    fun resetClearsAnchor() {
        val rebaser = PtsRebaser()
        assertEquals(nowUs, rebaser.rebasePtsUs(ptsBaseUs, nowUs))
        rebaser.reset()
        val laterUs = nowUs + 60_000_000L
        assertEquals(laterUs, rebaser.rebasePtsUs(ptsBaseUs + 60_000_000L, laterUs))
    }

    @Test
    fun resetClearsFedTimeline() {
        val rebaser = PtsRebaser()
        assertEquals(nowUs, rebaser.rebasePtsUs(0L, nowUs))
        assertEquals(nowUs + 20_000L, rebaser.rebasePtsUs(0L, nowUs + frameUs))
        rebaser.reset()
        val laterUs = nowUs + 60_000_000L
        // After a reset the synthetic timeline restarts at the new now,
        // it does not continue the old session's fed values.
        assertEquals(laterUs, rebaser.rebasePtsUs(0L, laterUs))
        assertEquals(laterUs + 20_000L, rebaser.rebasePtsUs(ptsBaseUs + 60_000_000L, laterUs + frameUs))
    }

    // Round-5 "rebase" mode: the round-3 semantics, kept for A/B
    // comparison against the continuous timeline above.

    @Test
    fun legacyZeroFeedsZero() {
        val rebaser = PtsRebaser()
        assertEquals(0L, rebaser.rebasePtsUsLegacy(0L, nowUs))
        assertEquals(0L, rebaser.rebasePtsUsLegacy(-5L, nowUs))
        // A 0-PTS frame must not touch the anchor.
        assertEquals(nowUs, rebaser.rebasePtsUsLegacy(ptsBaseUs, nowUs))
    }

    @Test
    fun legacyFirstValidAnchorsToNow() {
        val rebaser = PtsRebaser()
        assertEquals(nowUs, rebaser.rebasePtsUsLegacy(ptsBaseUs, nowUs))
        assertEquals(0L, rebaser.reanchorCount)
    }

    @Test
    fun legacyNextFrameOnPace() {
        val rebaser = PtsRebaser()
        assertEquals(nowUs, rebaser.rebasePtsUsLegacy(ptsBaseUs, nowUs))
        assertEquals(nowUs + frameUs, rebaser.rebasePtsUsLegacy(ptsBaseUs + frameUs, nowUs))
    }

    @Test
    fun legacyFarJumpReanchors() {
        val rebaser = PtsRebaser()
        assertEquals(nowUs, rebaser.rebasePtsUsLegacy(ptsBaseUs, nowUs))
        assertEquals(nowUs, rebaser.rebasePtsUsLegacy(ptsBaseUs + 10_000_000L, nowUs))
        assertEquals(1L, rebaser.reanchorCount)
    }
}
