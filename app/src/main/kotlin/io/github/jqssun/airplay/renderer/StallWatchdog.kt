package io.github.jqssun.airplay.renderer

/**
 * Pure stall detector for the mirror drain path. No Android imports —
 * unit-testable on the JVM with a virtual clock (see StallWatchdogTest).
 *
 * The failure it catches (VAVA 2026-11-16: mirror frozen on frame 1 with
 * input still flowing at ~50 fps): the codec started fine, so the
 * start-exception fallback in VideoRenderer never ran, and nothing watched
 * for "started but no output". A decoder that is fed but produces nothing
 * for [noOutputNs] while input keeps flowing is wedged, not starved —
 * starved input (nothing fed recently) is explicitly excluded, so a paused
 * sender or a session gap never triggers a recovery.
 *
 * Firing only requests a resync (flush the decoder, reset the anchors,
 * wait for the next IDR); the caller owns the recovery and this class owns
 * the debounce ([debounceNs] between firings), so a persistently wedged
 * decoder cannot spin flush/restart in a tight loop.
 *
 * All times are System.nanoTime() (ns). [codecStartNs] is 0 when no codec
 * is running; [lastFeedNs] / [lastOutputNs] are 0 when nothing has been
 * fed / output yet this session.
 */
class StallWatchdog(
    private val noOutputNs: Long = DEFAULT_NO_OUTPUT_NS,
    private val debounceNs: Long = DEFAULT_DEBOUNCE_NS,
) {
    private var lastRecoverNs = 0L

    /** True when a recovery should fire now. Records the firing for debounce. */
    fun shouldRecover(nowNs: Long, codecStartNs: Long, lastFeedNs: Long, lastOutputNs: Long, firstPendingFeedNs: Long): Boolean {
        if (codecStartNs == 0L || firstPendingFeedNs == 0L) return false
        if (lastRecoverNs != 0L && nowNs - lastRecoverNs < debounceNs) return false
        // Input must be flowing: nothing fed recently means starved, not wedged.
        if (lastFeedNs == 0L || nowNs - lastFeedNs > noOutputNs) return false
        val stalled = nowNs - maxOf(firstPendingFeedNs, lastOutputNs, codecStartNs) > noOutputNs
        if (!stalled) return false
        lastRecoverNs = nowNs
        return true
    }

    fun reset() {
        lastRecoverNs = 0L
    }

    companion object {
        /** No decoder output for this long (ns) with input flowing = wedged. */
        const val DEFAULT_NO_OUTPUT_NS = 2_000_000_000L
        /** Minimum time (ns) between two recovery firings. */
        const val DEFAULT_DEBOUNCE_NS = 10_000_000_000L
    }
}
