package io.github.jqssun.airplay.renderer

/**
 * Pure render-timestamp math for the mirror drain path. No Android imports,
 * fully deterministic — unit-testable on the JVM (see DrainClockTest).
 *
 * The drain thread releases each decoded frame to the display surface with a
 * render timestamp derived from the sender's PTS (see VideoRenderer).
 * Two properties of that PTS make a naive computation dangerous:
 *
 * 1. The PTS is the sender's NTP clock (us). Before the NTP sync completes
 *    the native layer reports 0 (`raop_ntp_convert_remote_time` returns 0
 *    while `client_time_received` is false), so a session legitimately
 *    starts with 0-PTS frames — and the conversion can also wrap, yielding
 *    a negative PTS. A 0 (or negative) PTS is "no timestamp", not a point
 *    ~10^18 ns in the past, and it must never poison the anchor.
 * 2. The platform only guarantees render-timestamp behaviour within ~1s
 *    of `System.nanoTime` (see `MediaCodec.releaseOutputBuffer`
 *    documentation). A timestamp computed from a regressed PTS can land
 *    ~10^18 ns in the past; what a vendor OMX component does with that is
 *    undefined, and on the VAVA it coincided with the decoder producing
 *    zero output after frame 1 (2026-11-16: In 50 fps, Out 0 fps, queue
 *    filling with "Jitter buffer full" drops, no drain-side log lines).
 *
 * So: PTS <= 0 releases immediately with no timestamp and never touches
 * the anchor; any computed timestamp is clamped to [now, now + 100ms]; a
 * timestamp far in the future re-anchors instead of scheduling decades
 * out (a stall / seek forward, not a reason to pin every output buffer).
 * The anchor only ever holds PTS > 0, so `(ptsUs - ptsBaseUs) * 1000L`
 * cannot overflow for any realistic NTP-scale value, and even a garbage
 * input still yields a return inside the clamped window.
 */
class DrainClock(private val maxLateNs: Long = Long.MAX_VALUE) {

    @Volatile var ptsBaseUs = Long.MIN_VALUE; private set
    @Volatile var wallBaseNs = 0L; private set
    @Volatile var reanchorCount = 0L; private set

    fun reset() {
        ptsBaseUs = Long.MIN_VALUE
        wallBaseNs = 0L
    }

    /**
     * Render timestamp (ns, monotonic clock) for a frame with this PTS, or
     * null to release it immediately with no timestamp. Never returns a
     * value in the past or more than [MAX_RELEASE_AHEAD_NS] ahead of now.
     * Re-anchors (and counts it) when the PTS jumps far ahead.
     */
    fun releaseTimestampNs(ptsUs: Long, nowNs: Long): Long? {
        if (ptsUs <= 0) return null
        if (ptsBaseUs == Long.MIN_VALUE) {
            ptsBaseUs = ptsUs
            wallBaseNs = nowNs
            return nowNs
        }
        val scheduledNs = wallBaseNs + (ptsUs - ptsBaseUs) * 1000L
        if (scheduledNs - nowNs > MAX_RELEASE_AHEAD_NS) {
            // PTS jumped far ahead (stall / seek / new stream): re-anchor so
            // the schedule tracks real time instead of pinning buffers.
            ptsBaseUs = ptsUs
            wallBaseNs = nowNs
            reanchorCount++
            return nowNs
        }
        if (nowNs - scheduledNs > maxLateNs) {
            ptsBaseUs = ptsUs
            wallBaseNs = nowNs
            reanchorCount++
            return nowNs
        }
        // Never in the past: a regressed PTS (0-PTS frame after a real
        // anchor, clock jump) must not produce a negative render timestamp.
        return scheduledNs.coerceAtLeast(nowNs)
    }

    companion object {
        /** A render timestamp may lie at most this far ahead of now (ns). */
        const val MAX_RELEASE_AHEAD_NS = 100_000_000L
    }
}
