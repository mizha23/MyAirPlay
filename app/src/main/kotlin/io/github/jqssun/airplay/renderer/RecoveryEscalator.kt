package io.github.jqssun.airplay.renderer

/**
 * Pure escalation ladder for mirror recovery. No Android imports —
 * unit-testable on the JVM (see RecoveryEscalatorTest).
 *
 * Three-step ladder (round 6; round 5 was two-step): the first two
 * consecutive failed recoveries restart the video session; the third
 * restarts the whole AirPlay server (which disconnects the sender — the
 * destructive step, so it gets two session-restart chances first).
 * A recovery is "failed" when the decoder still produces no output
 * after it, not when a new IDR shows up.
 *
 * The failed-restart count is preserved until *sustained* output is
 * confirmed ([SUSTAINED_OUTPUT_FRAMES] consecutive drained output
 * buffers). Round 6 P1-1: the old code cleared the count in onValidIdr,
 * and VideoRenderer called that right after startCodec succeeded — even
 * before the IDR was queued into the codec, let alone produced output.
 * A decoder that accepts IDRs but never outputs then reset the ladder
 * on every attempt, so it could never reach SERVER_RESTART. IDR
 * admission is not recovery success; only sustained output is.
 *
 * The watchdog streak ([noOutputStreak]) is separate: it counts
 * consecutive watchdog resyncs with no output at all, so a single
 * output buffer does reset it (the decoder is not currently wedged).
 * The two counters answer different questions — "is it wedged right
 * now" vs "did the last recovery actually fix it" — and must not be
 * conflated.
 *
 * A session start does not reset the failed-restart count either (a
 * restart that produced no output is a failed restart); it only resets
 * the watchdog streak and the sustained-output run, since the new
 * session starts a fresh output run.
 */
class RecoveryEscalator(
    private val watchdogStreakLimit: Int = DEFAULT_WATCHDOG_STREAK_LIMIT,
) {
    private var noOutputStreak = 0
    private var failedRestarts = 0
    private var consecutiveOutputs = 0

    /** Consecutive watchdog resyncs with no output at all. */
    val noOutputStreakCount: Int get() = noOutputStreak

    /** Consecutive failed recoveries (session/server restarts that produced no sustained output). */
    val failedRestartCount: Int get() = failedRestarts

    /** Consecutive drained output buffers since the last failure signal. */
    val outputStreak: Int get() = consecutiveOutputs

    enum class Level { SESSION_RESTART, SERVER_RESTART }

    /** Watchdog fired a resync; escalate once the streak hits the limit. */
    fun noteWatchdogResync(): Level? {
        // A failure breaks the sustained-output run.
        consecutiveOutputs = 0
        noOutputStreak++
        return if (noOutputStreak >= watchdogStreakLimit) {
            noOutputStreak = 0
            escalate()
        } else null
    }

    /** The bounded IDR wait expired with no valid IDR (see VideoRenderer._idrWaitCheck). */
    fun noteIdrTimeout(): Level {
        // A failure breaks the sustained-output run.
        consecutiveOutputs = 0
        return escalate()
    }

    /**
     * A valid IDR was admitted (the codec was (re)started on it).
     *
     * Round 6 P1-1: this is intentionally a no-op. IDR admission is not
     * recovery success — the IDR may be queued into a decoder that never
     * outputs, and clearing the failed-restart count here let such a
     * decoder reset the ladder on every attempt. The count is cleared
     * only by sustained output (see [onOutput]).
     */
    fun onValidIdr() {
        // Deliberately does not touch failedRestarts or noOutputStreak.
    }

    /**
     * One output buffer was drained. Resets the watchdog streak
     * immediately, but the failed-restart count only after sustained
     * output: [SUSTAINED_OUTPUT_FRAMES] consecutive drained buffers.
     */
    fun onOutput() {
        noOutputStreak = 0
        consecutiveOutputs++
        if (consecutiveOutputs >= SUSTAINED_OUTPUT_FRAMES) {
            failedRestarts = 0
            consecutiveOutputs = 0
        }
    }

    /** A new (re)started session: fresh output run, but the failed-restart count survives. */
    fun onSessionStart() {
        noOutputStreak = 0
        consecutiveOutputs = 0
    }

    fun reset() {
        noOutputStreak = 0
        failedRestarts = 0
        consecutiveOutputs = 0
    }

    /** Three-step ladder: two session restarts, then a server restart. */
    private fun escalate(): Level =
        if (failedRestarts < 2) {
            failedRestarts++
            Level.SESSION_RESTART
        } else {
            failedRestarts = 0
            Level.SERVER_RESTART
        }

    companion object {
        /** Watchdog resyncs with no output before escalating. */
        const val DEFAULT_WATCHDOG_STREAK_LIMIT = 3

        /** Consecutive drained output buffers that count as "recovery confirmed". */
        const val SUSTAINED_OUTPUT_FRAMES = 5
    }
}
