package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for [RecoveryEscalator] (round 6; ladder and reset rules
 * reworked from round 5).
 *
 * The ladder is three-step: the first two consecutive failed
 * recoveries restart the video session, the third restarts the server.
 * A recovery is "failed" until *sustained* output is confirmed
 * ([RecoveryEscalator.SUSTAINED_OUTPUT_FRAMES] consecutive drained
 * buffers). IDR admission is NOT recovery success (round 6 P1-1):
 * [RecoveryEscalator.onValidIdr] is a no-op and must never reset the
 * failed-restart count. A single output buffer resets the watchdog
 * streak (the decoder is not wedged right now) but not the
 * failed-restart count.
 */
class RecoveryEscalatorTest {

    @Test
    fun watchdogStreakEscalatesOnThird() {
        val e = RecoveryEscalator()
        assertNull(e.noteWatchdogResync())
        assertNull(e.noteWatchdogResync())
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteWatchdogResync())
    }

    @Test
    fun thirdStreakEscalatesToServer() {
        val e = RecoveryEscalator()
        repeat(3) { e.noteWatchdogResync() } // -> SESSION_RESTART
        assertNull(e.noteWatchdogResync())
        assertNull(e.noteWatchdogResync())
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteWatchdogResync())
        assertNull(e.noteWatchdogResync())
        assertNull(e.noteWatchdogResync())
        assertEquals(RecoveryEscalator.Level.SERVER_RESTART, e.noteWatchdogResync())
    }

    @Test
    fun outputResetsStreak() {
        val e = RecoveryEscalator()
        e.noteWatchdogResync()
        e.noteWatchdogResync()
        e.onOutput()
        assertNull(e.noteWatchdogResync())
        assertNull(e.noteWatchdogResync())
        // Still a first failure, not a server restart.
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteWatchdogResync())
    }

    @Test
    fun singleOutputResetsStreakButNotFailedRestarts() {
        val e = RecoveryEscalator()
        repeat(3) { e.noteWatchdogResync() } // -> SESSION_RESTART
        assertEquals(1, e.failedRestartCount)
        e.noteWatchdogResync()
        e.noteWatchdogResync()
        e.onOutput() // one output: streak reset, count NOT reset
        assertEquals(0, e.noOutputStreakCount)
        assertEquals(1, e.failedRestartCount)
        assertNull(e.noteWatchdogResync())
        assertNull(e.noteWatchdogResync())
        // Second consecutive failure: still a session restart (three-step ladder).
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteWatchdogResync())
        assertEquals(2, e.failedRestartCount)
    }

    @Test
    fun idrTimeoutEscalatesToServerOnThird() {
        val e = RecoveryEscalator()
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteIdrTimeout())
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteIdrTimeout())
        assertEquals(RecoveryEscalator.Level.SERVER_RESTART, e.noteIdrTimeout())
    }

    @Test
    fun validIdrDoesNotBreakChain() {
        // Round 6 P1-1: IDR admission must NOT reset the failed-restart
        // count — the old test asserted the opposite (the bug itself).
        val e = RecoveryEscalator()
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteIdrTimeout())
        assertEquals(1, e.failedRestartCount)
        repeat(3) { e.onValidIdr() }
        assertEquals(1, e.failedRestartCount)
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteIdrTimeout())
        assertEquals(2, e.failedRestartCount)
        assertEquals(RecoveryEscalator.Level.SERVER_RESTART, e.noteIdrTimeout())
        assertEquals(0, e.failedRestartCount)
    }

    @Test
    fun sustainedOutputResetsChain() {
        val e = RecoveryEscalator()
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteIdrTimeout())
        assertEquals(1, e.failedRestartCount)
        repeat(RecoveryEscalator.SUSTAINED_OUTPUT_FRAMES) { e.onOutput() }
        assertEquals(0, e.failedRestartCount)
        // Fresh chain after confirmed recovery.
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteIdrTimeout())
        assertEquals(1, e.failedRestartCount)
    }

    @Test
    fun fewOutputsDoNotResetChain() {
        val e = RecoveryEscalator()
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteIdrTimeout())
        repeat(RecoveryEscalator.SUSTAINED_OUTPUT_FRAMES - 1) { e.onOutput() }
        assertEquals(1, e.failedRestartCount)
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteIdrTimeout())
        assertEquals(RecoveryEscalator.Level.SERVER_RESTART, e.noteIdrTimeout())
    }

    @Test
    fun outputAfterSessionRestartResets() {
        val e = RecoveryEscalator()
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteIdrTimeout())
        e.onSessionStart()
        assertEquals(1, e.failedRestartCount)
        repeat(RecoveryEscalator.SUSTAINED_OUTPUT_FRAMES) { e.onOutput() }
        assertEquals(0, e.failedRestartCount)
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteIdrTimeout())
    }

    @Test
    fun resetClears() {
        val e = RecoveryEscalator()
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteIdrTimeout())
        assertEquals(1, e.failedRestartCount)
        e.reset()
        assertEquals(0, e.failedRestartCount)
        assertEquals(0, e.outputStreak)
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteIdrTimeout())
    }

    @Test
    fun sessionStartKeepsFailedRestartCount() {
        // onSessionStart resets the watchdog streak and the output run
        // but not the failed-restart count, or consecutive failures
        // would never reach the server restart.
        val e = RecoveryEscalator()
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteIdrTimeout())
        e.onSessionStart()
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteIdrTimeout())
        assertEquals(RecoveryEscalator.Level.SERVER_RESTART, e.noteIdrTimeout())
    }

    @Test
    fun customStreakLimit() {
        val e = RecoveryEscalator(2)
        assertNull(e.noteWatchdogResync())
        assertEquals(RecoveryEscalator.Level.SESSION_RESTART, e.noteWatchdogResync())
    }

    @Test
    fun reviewerReproThreeIdrRoundsReachServerRestart() {
        // Round 6 P1-1 review repro: repeat three times (three IDR
        // admissions + watchdog signals -> restart -> onSessionStart).
        // Before the fix every round returned SESSION_RESTART because
        // onValidIdr reset the count; now the ladder advances to
        // SERVER_RESTART on the third round.
        val e = RecoveryEscalator()
        val results = mutableListOf<RecoveryEscalator.Level>()
        repeat(3) {
            repeat(3) { e.onValidIdr() }
            var level: RecoveryEscalator.Level? = null
            repeat(3) { level = e.noteWatchdogResync() }
            results += level!!
            e.onSessionStart()
        }
        assertEquals(
            listOf(
                RecoveryEscalator.Level.SESSION_RESTART,
                RecoveryEscalator.Level.SESSION_RESTART,
                RecoveryEscalator.Level.SERVER_RESTART,
            ),
            results,
        )
    }
}
