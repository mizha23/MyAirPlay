package io.github.jqssun.airplay.renderer

/**
 * Rebases the sender's presentation timestamps into the decoder's clock
 * domain before they are fed to MediaCodec (see VideoRenderer._feedToCodec).
 * Pure Kotlin, no Android imports — unit-testable on the JVM with a
 * virtual clock (see PtsRebaserTest).
 *
 * The problem (round 3): the sender stamps mirror frames with its NTP clock
 * (Unix-epoch microseconds, ~1.79e15 us). The Amlogic decoder on the VAVA
 * (OMX.amlogic.avc.decoder.awesome) schedules decoded output against
 * CLOCK_MONOTONIC (device uptime, ~1e5 s at the time) in its display
 * thread: a frame is only returned to the app once its presentation time
 * arrives on that clock. Feeding NTP-epoch timestamps therefore makes the
 * decoder hold every decoded frame ~57 years (1.79e9 s Unix time vs ~1e5 s
 * uptime). Observed on the VAVA 2026-11-16 18:08 session (device time
 * 10:07:23–10:08:04): the decoder consumed ~700 input frames and returned
 * exactly ONE output buffer (drain: released=1 dequeues=1 errors=0,
 * heartbeat steady every 5 s), while at teardown flushOutput returned 4
 * decoded frames the display thread had been holding the whole session
 * (logcat 10:08:04.829, 4x displayThread__flushOutput__fillBufferDone).
 * The one frame that did display was the first one, whose PTS was 0
 * (the native layer reports 0 before NTP sync completes, see
 * raop_ntp_convert_remote_time) — a 0 timestamp means "display now".
 * Input kept flowing (feedFrame ~0.7 ms, no "Decoder input queue full")
 * because the decoder drops frames internally once its display queue is
 * full (low-latency mode + KEY_ALLOW_FRAME_DROP).
 *
 * The round-3 fix anchored the first valid PTS to System.nanoTime() and fed
 * the decoder `monoBase + (pts - ptsBase)` — same relative frame timing, but
 * in the monotonic domain the decoder actually schedules against. The
 * pacer (FramePacer) and the release clock (DrainClock) only ever use PTS
 * deltas, so they are unaffected by the rebase; the decoder-returned
 * presentationTimeUs is now monotonic-scale too, which DrainClock also
 * expects.
 *
 * The remaining jump (round 4, VAVA 2026-11-16 18:38 retest still frozen
 * on frame 1): round 3 still fed 0 for every pre-sync frame and then
 * jumped straight to device uptime on the first valid PTS, so the decoder
 * saw `0 → 0 → <tens of hours in us> → increasing` — the big value was
 * gone, but the discontinuity stayed, and it matches "first frame fine,
 * nothing after" (causality still needs a live-device comparison).
 * This version therefore guarantees one continuous, strictly increasing
 * fed-PTS timeline per session:
 * - 0/negative-PTS frames get synthetic timestamps: the first one feeds
 *   at `nowUs`, later ones one estimated frame interval after the
 *   previously fed PTS — never 0.
 * - The first valid PTS anchors so it continues the fed timeline (one
 *   interval after the last fed PTS, capped at `nowUs`), instead of
 *   jumping to the current monotonic time.
 * - The real frame interval is learned from consecutive valid PTS deltas
 *   (clamped to 5–100 ms) so the synthetic spacing tracks the stream.
 * - The returned value is always strictly greater than the previously
 *   fed one (enforced with a +1 us floor).
 * Jumps are still possible from real sender behaviour (stall / seek), so
 * any fed step over [JUMP_THRESHOLD_US] — or any regression that had to
 * be clamped — is counted in [jumpCount] with the delta in [lastJumpUs];
 * VideoRenderer logs those (see _feedToCodec) for the live comparison.
 *
 * Safety: even if a decoder used the wall clock instead of monotonic, the
 * rebased timestamps (monotonic, ~1e5 s) would land far in ITS past and
 * display immediately — so the rebase cannot make a working decoder worse,
 * it can only unstick a monotonic-scheduled one.
 */
class PtsRebaser {

    @Volatile var ptsBaseUs = Long.MIN_VALUE; private set
    @Volatile var monoBaseUs = 0L; private set
    @Volatile var reanchorCount = 0L; private set
    /** Last PTS actually fed to the decoder (monotonic domain). */
    @Volatile var lastFedUs = Long.MIN_VALUE; private set
    /** Fed steps over [JUMP_THRESHOLD_US], or regressions that were clamped. */
    @Volatile var jumpCount = 0L; private set
    /** Signed fed-step delta (us) of the most recent counted jump. */
    @Volatile var lastJumpUs = 0L; private set

    private var lastValidPtsUs = Long.MIN_VALUE
    private var frameIntervalUs = DEFAULT_FRAME_INTERVAL_US

    fun reset() {
        ptsBaseUs = Long.MIN_VALUE
        monoBaseUs = 0L
        lastFedUs = Long.MIN_VALUE
        lastValidPtsUs = Long.MIN_VALUE
        frameIntervalUs = DEFAULT_FRAME_INTERVAL_US
        // reanchorCount / jumpCount are lifetime counters, not per-session.
    }

    /**
     * Monotonic-clock presentation timestamp (us) for a frame with this
     * sender PTS — never 0, always strictly greater than the previously
     * returned value. [nowUs] is System.nanoTime()/1000 (explicit for
     * testability). Re-anchors — and counts it — when the PTS jumps far
     * ahead (stall / seek forward) or far behind (seek back / new stream),
     * instead of scheduling the frame seconds out or decades in the past.
     */
    fun rebasePtsUs(ptsUs: Long, nowUs: Long): Long {
        val fed: Long = if (ptsUs <= 0) {
            // No timestamp: synthesize the next slot on the fed timeline
            // instead of feeding 0 — a 0 followed later by uptime-scale
            // timestamps is the discontinuity round 3 left behind.
            if (lastFedUs == Long.MIN_VALUE) nowUs else lastFedUs + frameIntervalUs
        } else {
            // Learn the real frame interval from consecutive valid PTS.
            if (lastValidPtsUs != Long.MIN_VALUE) {
                val dt = ptsUs - lastValidPtsUs
                if (dt in MIN_FRAME_INTERVAL_US..MAX_FRAME_INTERVAL_US) frameIntervalUs = dt
            }
            lastValidPtsUs = ptsUs
            if (ptsBaseUs == Long.MIN_VALUE) {
                // First valid PTS: continue the fed timeline (one interval
                // after the last fed PTS, capped at now) instead of
                // jumping to the current monotonic time.
                ptsBaseUs = ptsUs
                monoBaseUs = if (lastFedUs == Long.MIN_VALUE) nowUs
                    else minOf(lastFedUs + frameIntervalUs, nowUs)
                monoBaseUs
            } else {
                var monoUs = monoBaseUs + (ptsUs - ptsBaseUs)
                if (monoUs - nowUs > MAX_FUTURE_US || nowUs - monoUs > MAX_PAST_US) {
                    // PTS jumped far ahead or far behind: re-anchor so the schedule
                    // tracks real time instead of pinning the decoder's display
                    // thread on a timestamp it will never reach.
                    ptsBaseUs = ptsUs
                    monoBaseUs = nowUs
                    reanchorCount++
                    monoBaseUs
                } else monoUs
            }
        }
        // Count jumps on the raw candidate, before monotonic enforcement.
        if (lastFedUs != Long.MIN_VALUE) {
            val delta = fed - lastFedUs
            if (delta > JUMP_THRESHOLD_US || delta <= 0L) {
                jumpCount++
                lastJumpUs = delta
            }
        }
        // Guarantee a strictly increasing fed timeline.
        val out = if (lastFedUs == Long.MIN_VALUE) fed else maxOf(fed, lastFedUs + 1L)
        lastFedUs = out
        return out
    }

    /**
     * Round-3 rebase semantics, kept for A/B comparison (round 5 PTS mode
     * "rebase"): 0/negative PTS feeds as 0, the first valid PTS anchors
     * to [nowUs]. Unlike [rebasePtsUs] it does NOT guarantee a continuous
     * fed timeline — the 0 -> uptime jump is exactly what round 4 removed,
     * and this mode exists to isolate whether that change (rather than the
     * resync fixes) triggered the round-4 black screen. Shares the anchor
     * state with [rebasePtsUs]; the mode setting is restart=true, so a
     * mode change starts a fresh session with reset state.
     */
    fun rebasePtsUsLegacy(ptsUs: Long, nowUs: Long): Long {
        if (ptsUs <= 0) return 0L
        if (ptsBaseUs == Long.MIN_VALUE) {
            ptsBaseUs = ptsUs
            monoBaseUs = nowUs
            lastFedUs = nowUs
            return nowUs
        }
        val monoUs = monoBaseUs + (ptsUs - ptsBaseUs)
        if (monoUs - nowUs > MAX_FUTURE_US || nowUs - monoUs > MAX_PAST_US) {
            ptsBaseUs = ptsUs
            monoBaseUs = nowUs
            reanchorCount++
            lastFedUs = nowUs
            return nowUs
        }
        lastFedUs = monoUs
        return monoUs
    }

    companion object {
        /** Re-anchor when a frame's rebased PTS lies this far ahead of now (us). */
        const val MAX_FUTURE_US = 1_000_000L
        /** Re-anchor when a frame's rebased PTS lies this far behind now (us). */
        const val MAX_PAST_US = 5_000_000L
        /** A fed step larger than this (us) counts as a jump for logging. */
        const val JUMP_THRESHOLD_US = 500_000L
        /** Nominal inter-frame interval before the real rate is learned (us). */
        const val DEFAULT_FRAME_INTERVAL_US = 20_000L
        /** Plausible inter-frame interval when learning the rate (us). */
        const val MIN_FRAME_INTERVAL_US = 5_000L
        const val MAX_FRAME_INTERVAL_US = 100_000L
    }
}
