package io.github.jqssun.airplay.renderer

/**
 * PTS-paced feed scheduler for the mirror video jitter buffer.
 *
 * Problem it solves: mirror frames arrive in bursts (arrival jitter 27-41ms,
 * as large as the 33.3ms frame interval), while the sender's PTS clock is
 * steady (jitter ~0). The old path fed each frame to the decoder the moment
 * it arrived, so arrival jitter passed straight through to the screen.
 *
 * How it works: the first frame anchors the schedule — its PTS maps to
 * (arrival time + [targetDelayNs]). Every later frame is fed at
 * `anchorWall + (pts - anchorPts)`, i.e. on presentation-time pace, not on
 * arrival pace. A frame that arrives late (target already past) is fed
 * immediately. This absorbs up to ~[targetDelayNs] of arrival jitter at the
 * cost of that much extra latency.
 *
 * Units: PTS is in MICROSECONDS (MediaCodec presentationTimeUs). The JNI
 * layer hands the renderer nanoseconds (ntpTimeNs) — callers MUST divide by
 * 1000 before calling [waitNs]/[isStale]. Passing nanoseconds inflates
 * every PTS delta 1000x, which makes the pacer re-anchor on every frame
 * and silently disables all smoothing (VAVA 2026-11-16: buffer on, picture
 * frozen on frame 1, queue filling with "Jitter buffer full" drops).
 *
 * Anchor maintenance:
 * - PTS going backwards (new session / seek) re-anchors.
 * - A PTS jump far ahead (stall / seek forward) re-anchors instead of
 *   waiting out the whole gap: if a frame's target lies more than
 *   `targetDelayNs + maxEarlyNs` beyond its arrival, the anchor is reset to
 *   that frame.
 * - A schedule that slipped far behind reality (now is more than half a
 *   buffer past the frame's target, e.g. after a network stall) re-anchors
 *   to now instead of burst-feeding frames to catch up: the late frame feeds
 *   immediately and pacing resumes at 33.3ms from here, the slip becoming
 *   extra latency rather than a visible fast-forward judder. Small slips
 *   (decoder wake-up a few ms late) do NOT re-anchor, so the schedule
 *   cannot drift later a few ms at a time.
 * - A frame whose target is more than [maxLateNs] in the past is stale;
 *   [isStale] lets the caller skip it while fresher frames wait behind it.
 * - PTS <= 0 means "no timestamp": the frame feeds immediately and never
 *   touches the anchor (a zero-timestamp frame mid-stream cannot disturb
 *   the schedule).
 *
 * Pure Kotlin, no Android imports, fully deterministic — unit-testable on the
 * JVM with a virtual clock: every method that needs "now" takes it as an
 * explicit argument, so there is no hidden clock to inject.
 * (Deliberately no `clock: () -> Long` constructor parameter: a function-type
 * constructor parameter together with any defaulted parameter makes this
 * project's standalone kotlinc 2.4.10 codegen fail with
 * `ERROR_EXPR 'Default Stub'` — bisected 2026-11-16.)
 */
class FramePacer(
    /** How far ahead of a frame's arrival its feed is scheduled (ns). */
    var targetDelayNs: Long,
    /** A frame this late is considered stale (ns). */
    private val maxLateNs: Long = 500_000_000L,
    /** Re-anchor when a frame's target lies this far beyond arrival + delay. */
    private val maxEarlyNs: Long = 100_000_000L,
) {
    private var anchorPtsUs = Long.MIN_VALUE
    private var anchorWallNs = 0L

    fun reset() {
        anchorPtsUs = Long.MIN_VALUE
        anchorWallNs = 0L
    }

    /** Wall-clock time (ns) at which a frame with this PTS should be fed. */
    fun targetFeedTimeNs(ptsUs: Long, arrivalNs: Long): Long {
        if (ptsUs <= 0) return arrivalNs // no usable PTS: feed immediately
        if (anchorPtsUs == Long.MIN_VALUE || ptsUs < anchorPtsUs) {
            anchorPtsUs = ptsUs
            anchorWallNs = arrivalNs + targetDelayNs
        }
        val target = anchorWallNs + (ptsUs - anchorPtsUs) * 1000L
        if (target - arrivalNs > targetDelayNs + maxEarlyNs) {
            // PTS jumped far ahead (stall / seek / new stream): re-anchor so
            // we resume quickly instead of waiting out the whole gap.
            anchorPtsUs = ptsUs
            anchorWallNs = arrivalNs + targetDelayNs
            return anchorWallNs
        }
        return target
    }

    /** How long (ns) to wait before feeding this frame; 0 = feed now. */
    fun waitNs(ptsUs: Long, arrivalNs: Long, nowNs: Long): Long {
        if (ptsUs <= 0) return 0
        val target = targetFeedTimeNs(ptsUs, arrivalNs)
        if (nowNs - target > targetDelayNs / 2) {
            // Schedule slipped more than half a buffer behind reality
            // (stall / long gap): re-anchor to now instead of burst-feeding
            // frames to catch up. This frame feeds immediately; pacing
            // resumes at frame pace from here.
            anchorPtsUs = ptsUs
            anchorWallNs = nowNs
            return 0
        }
        return (target - nowNs).coerceAtLeast(0L)
    }

    /** True when the frame's target time is so far in the past that showing it is pointless. */
    fun isStale(ptsUs: Long, nowNs: Long): Boolean {
        if (ptsUs <= 0 || anchorPtsUs == Long.MIN_VALUE) return false
        val target = anchorWallNs + (ptsUs - anchorPtsUs) * 1000L
        return nowNs - target > maxLateNs
    }
}
