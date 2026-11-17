package io.github.jqssun.airplay.renderer

/**
 * Pure state machine for the "waiting for a valid IDR" deadline.
 * No Android imports — unit-testable on the JVM with a virtual clock
 * (see IdrWaitTrackerTest).
 *
 * Why it exists (round 6 P1-2): after a resync destroys the codec,
 * VideoRenderer waits for the sender's next periodic IDR, bounded by a
 * 3 s timeout ([DEFAULT_TIMEOUT_NS]) that escalates when it expires.
 * The wait used to be a single timestamp that startSession() cleared —
 * and a recovery-triggered session restart calls stopSession() /
 * startSession() while the RAOP stream keeps flowing and non-IDRs are
 * dropped at the keyframe gate. The restart therefore disarmed the only
 * no-IDR timeout: with a well-paced stream (no queue overflow, no new
 * IDR) there was no next timeout and no server escalation, just a
 * permanent black screen. (VAVA 2026-11-16 11:15: the second timeout
 * only fired because another queue overflow re-armed the wait by luck.)
 *
 * This tracker keeps the wait separate from the ordinary start/stop
 * bookkeeping: a recovery-triggered session start re-arms a fresh
 * deadline instead of clearing the wait, while an ordinary start
 * (fresh sender session) still clears it.
 *
 * The "recovery restart pending" flag is set by the renderer just
 * before it invokes the session-restart handler, and is consumed by
 * the next [onSessionStart]. stopSession() clears the deadline but
 * not the flag — the restart posts stopSession() then startSession(),
 * and the flag must survive the stop to re-arm the wait at the start.
 * If the restart never happens (no handler), the flag is never set;
 * if the service dies mid-restart, the flag lingers until the next
 * startSession, which then arms a deadline for the fresh session —
 * harmless, since a healthy fresh session produces its IDR well
 * within the window.
 *
 * All times are System.nanoTime() (ns). A deadline of 0 means "not
 * waiting". The fields are @Volatile because the drain thread arms and
 * checks the deadline while the main thread runs start/stop.
 */
class IdrWaitTracker(
    private val timeoutNs: Long = DEFAULT_TIMEOUT_NS,
) {
    @Volatile
    var deadlineNs: Long = 0L
        private set

    @Volatile
    var waitStartedNs: Long = 0L
        private set

    @Volatile
    var recoveryRestartPending: Boolean = false
        private set

    /** Arm the wait now; the deadline is [timeoutNs] ahead. */
    fun arm(nowNs: Long) {
        waitStartedNs = nowNs
        deadlineNs = nowNs + timeoutNs
    }

    /**
     * A SESSION_RESTART escalation is about to be requested. The next
     * [onSessionStart] is therefore a recovery restart, not an ordinary
     * start, and must re-arm the wait instead of clearing it.
     */
    fun markRecoveryRestartPending() {
        recoveryRestartPending = true
    }

    /**
     * Called on session start. Returns true when this start follows a
     * recovery-triggered session restart (the wait is re-armed for a
     * fresh [timeoutNs] window); false for an ordinary start (the wait
     * is cleared).
     */
    fun onSessionStart(nowNs: Long): Boolean =
        if (recoveryRestartPending) {
            recoveryRestartPending = false
            arm(nowNs)
            true
        } else {
            clear()
            false
        }

    /** Clear the wait (codec started, IDR arrived, session ended). The pending flag is untouched. */
    fun clear() {
        deadlineNs = 0L
        waitStartedNs = 0L
    }

    fun isWaiting(): Boolean = deadlineNs != 0L

    fun isDue(nowNs: Long): Boolean = deadlineNs != 0L && nowNs >= deadlineNs

    companion object {
        /** No valid IDR for this long (ns) after a resync = escalate. */
        const val DEFAULT_TIMEOUT_NS = 3_000_000_000L
    }
}
