package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * JVM tests for [FramePacer] with a virtual clock.
 *
 * The reported TV failure: arrival jitter 27-41ms (as large as the 33.3ms
 * frame interval) while sender PTS jitter is ~0. These tests feed the pacer
 * that exact shape — steady PTS, bursty arrival — and check the output is
 * paced back to ~33.3ms.
 */
class FramePacerTest {
    @Test fun senderClockPreservesPacingWhenLocalNtpClockIsUnavailable() {
        val pts = List(120) { 105_000_000_000L + it * 33_333L }
        val arrivals = List(120) { (it / 2) * 66_666_000L }
        val old = simulate(List(120) { 0L }, arrivals, FramePacer(100_000_000L))
        val fixed = simulate(pts, arrivals, FramePacer(100_000_000L))
        assertTrue(old.zipWithNext { a, b -> b - a }.any { it == 0L })
        assertTrue(fixed.zipWithNext { a, b -> b - a }.all { it == 33_333_000L })
    }


    private val delayNs = 100_000_000L // 100ms target buffer depth
    private val frameUs = 33_333L // 30fps in us

    /** Simulates the decode loop: frames taken in arrival order, fed at max(arrival, target). */
    private fun simulate(ptsUs: List<Long>, arrivalNs: List<Long>, pacer: FramePacer): List<Long> {
        var now = 0L
        val feedTimes = mutableListOf<Long>()
        for (i in ptsUs.indices) {
            now = maxOf(now, arrivalNs[i])
            now += pacer.waitNs(ptsUs[i], arrivalNs[i], now)
            feedTimes.add(now)
        }
        return feedTimes
    }

    private fun stddevNs(values: List<Long>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun intervalsMs(feedTimes: List<Long>): List<Long> =
        feedTimes.zipWithNext { a, b -> (b - a) / 1_000_000L }

    @Test
    fun burstyArrivalIsSmoothedToSteadyPace() {
        val pacer = FramePacer(delayNs)
        val n = 90
        // Synthetic PTS starts at one frame (ptsUs == 0 means "no timestamp"
        // in the pacer and feeds immediately without anchoring).
        val ptsUs = List(n) { (it + 1) * frameUs }
        // deterministic pseudo-random arrival offsets in [-48ms, +48ms]
        val offsetsNs = List(n) { (((it * 7919) % 97) - 48) * 1_000_000L }
        val arrivalNs = List(n) { it * frameUs * 1000L + offsetsNs[it] }

        val arrivalIntervals = arrivalNs.zipWithNext { a, b -> b - a }
        assertTrue("test setup: arrival must actually be jittery", stddevNs(arrivalIntervals) > 20_000_000.0)

        val feedTimes = simulate(ptsUs, arrivalNs, pacer)
        val outIntervals = intervalsMs(feedTimes)
        assertTrue("output must be smoothed, got stddev=${stddevNs(outIntervals)}ns", stddevNs(outIntervals) < 5_000_000.0)
        for (iv in outIntervals) {
            assertTrue("output interval $iv ms outside [30, 37]", iv in 30L..37L)
        }
        // no frame dropped, total span preserved (no drift): 89 frames at
        // 33.333ms = 2966ms
        assertEquals(n, feedTimes.size)
        val spanMs = (feedTimes.last() - feedTimes.first()) / 1_000_000L
        assertTrue("span $spanMs ms, expected ~2966", spanMs in 2956L..2976L)
    }

    @Test
    fun productionNanosecondPtsConvertedToMicrosecondsIsSmoothed() {
        // Regression test for the VAVA 2026-11-16 freeze: the JNI layer
        // hands the renderer PTS in nanoseconds, on a real NTP-scale base
        // (~1.75e18 ns), and the production call site converts with /1000
        // before pacing. With that conversion the pacer must smooth the
        // bursty arrivals exactly like the synthetic-µs test above.
        val pacer = FramePacer(delayNs)
        val n = 90
        val ntpBaseNs = 1_750_000_000_000_000_000L
        val ptsNs = List(n) { ntpBaseNs + (it + 1) * frameUs * 1000L }
        val ptsUs = ptsNs.map { it / 1000 } // the production conversion
        val offsetsNs = List(n) { (((it * 7919) % 97) - 48) * 1_000_000L }
        val arrivalNs = List(n) { it * frameUs * 1000L + offsetsNs[it] }

        val feedTimes = simulate(ptsUs, arrivalNs, pacer)
        val outIntervals = intervalsMs(feedTimes)
        assertTrue("output must be smoothed, got stddev=${stddevNs(outIntervals)}ns", stddevNs(outIntervals) < 5_000_000.0)
        for (iv in outIntervals) {
            assertTrue("output interval $iv ms outside [30, 37]", iv in 30L..37L)
        }
        assertEquals(n, feedTimes.size)
        val spanMs = (feedTimes.last() - feedTimes.first()) / 1_000_000L
        assertTrue("span $spanMs ms, expected ~2966", spanMs in 2956L..2976L)
    }

    @Test
    fun nanosecondPtsPassedAsMicrosecondsIsNotSmoothed() {
        // Characterization of the old call-site bug: raw nanosecond PTS
        // values passed straight to the pacer (which expects microseconds).
        // Every PTS delta is inflated 1000x, so the pacer re-anchors on
        // every frame and the buffer degenerates into a fixed 100ms delay
        // line that passes arrival jitter through untouched. This test pins
        // the unit contract: if it ever starts passing, the pacer changed
        // its units and the call-site conversion must change with it.
        val pacer = FramePacer(delayNs)
        val n = 90
        val ntpBaseNs = 1_750_000_000_000_000_000L
        val ptsAsUs = List(n) { ntpBaseNs + (it + 1) * frameUs * 1000L } // ns, NOT converted
        val offsetsNs = List(n) { (((it * 7919) % 97) - 48) * 1_000_000L }
        val arrivalNs = List(n) { it * frameUs * 1000L + offsetsNs[it] }
        val arrivalIntervals = arrivalNs.zipWithNext { a, b -> b - a }
        assertTrue("test setup: arrival must actually be jittery", stddevNs(arrivalIntervals) > 20_000_000.0)

        val feedTimes = simulate(ptsAsUs, arrivalNs, pacer)
        val outIntervals = intervalsMs(feedTimes)
        // No smoothing at all: output jitter tracks arrival jitter.
        // (intervalsMs returns milliseconds, so the threshold is 20ms.)
        assertTrue("ns-as-us must NOT smooth, got stddev=${stddevNs(outIntervals)}ms", stddevNs(outIntervals) > 20.0)
        // The degenerate delay line still delays the first frame by the
        // full target depth.
        assertEquals(arrivalNs[0] + delayNs, feedTimes[0])
    }

    @Test
    fun veryLateFrameFeedsImmediatelyAndScheduleRecovers() {
        val pacer = FramePacer(delayNs)
        val n = 60
        val ptsUs = List(n) { (it + 1) * frameUs }
        val arrivalNs = ptsUs.mapIndexed { i, p ->
            p * 1000L + if (i == 30) 180_000_000L else 0L // frame 30 arrives 180ms late
        }
        val feedTimes = simulate(ptsUs, arrivalNs, pacer)
        // the late frame is fed at its arrival, not held to its (past) target
        assertEquals(arrivalNs[30], feedTimes[30])
        // after the late frame, pacing returns to 33.3ms
        val tail = intervalsMs(feedTimes).drop(32)
        assertTrue("tail stddev=${stddevNs(tail)}ns", stddevNs(tail) < 5_000_000.0)
        for (iv in tail) assertTrue("tail interval $iv ms outside [30, 37]", iv in 30L..37L)
    }

    @Test
    fun stallReanchorsInsteadOfWaitingOutTheGap() {
        val pacer = FramePacer(delayNs)
        val ptsUs = (0 until 30).map { (it + 1) * frameUs } +
            (30 until 60).map { 2_000_000L + (it - 30) * frameUs } // 2s PTS jump
        val arrivalNs = ptsUs.map { it * 1000L }
        var now = 0L
        val waits = mutableListOf<Long>()
        for (i in ptsUs.indices) {
            now = maxOf(now, arrivalNs[i])
            val w = pacer.waitNs(ptsUs[i], arrivalNs[i], now)
            waits.add(w)
            now += w
        }
        // first frame after the gap must not wait out the 2s: capped at delay + margin
        assertTrue("gap wait ${waits[30]}ns", waits[30] <= delayNs + 100_000_000L)
        // and pacing resumes at 33.3ms afterwards
        val feedTimes = mutableListOf<Long>()
        now = 0L
        for (i in ptsUs.indices) {
            now = maxOf(now, arrivalNs[i])
            now += pacer.waitNs(ptsUs[i], arrivalNs[i], now)
            feedTimes.add(now)
        }
        val tail = intervalsMs(feedTimes).drop(32)
        for (iv in tail) assertTrue("post-gap interval $iv ms outside [30, 37]", iv in 30L..37L)
    }

    @Test
    fun ptsBackwardsReanchors() {
        val pacer = FramePacer(delayNs)
        var now = 0L
        repeat(10) { i ->
            now = maxOf(now, i * frameUs * 1000L)
            now += pacer.waitNs((i + 1) * frameUs, i * frameUs * 1000L, now)
        }
        // PTS jumps back to half a frame (positive, but below the anchor):
        // fresh anchor, wait is exactly the target delay.
        assertEquals(delayNs, pacer.waitNs(frameUs / 2, now, now))
    }

    @Test
    fun noPtsFeedsImmediately() {
        val pacer = FramePacer(delayNs)
        assertEquals(0L, pacer.waitNs(0L, 0L, 0L))
        assertEquals(0L, pacer.waitNs(-5L, 0L, 0L))
        assertTrue(!pacer.isStale(0L, 1_000_000_000L))
    }

    @Test
    fun staleDetection() {
        val pacer = FramePacer(delayNs)
        // anchor at pts=1 frame, arrival=0 -> target 100ms
        assertEquals(delayNs, pacer.waitNs(frameUs, 0L, 0L))
        // a frame ~1s later in PTS: target = 100ms + ~967ms = ~1.067s
        assertTrue(!pacer.isStale(1_000_000L, 1_300_000_000L)) // ~233ms late: not stale
        assertTrue(pacer.isStale(1_000_000L, 1_700_000_000L)) // ~633ms late: stale
    }
}
