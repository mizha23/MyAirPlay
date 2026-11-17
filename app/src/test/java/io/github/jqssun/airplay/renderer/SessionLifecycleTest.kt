package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle tests for the renderer session policy (round 7 P1-2).
 *
 * VideoRenderer.startSession/stopSession themselves cannot run on the
 * JVM (android.util.Log stubs throw), so these tests drive the two pure
 * components through the exact sequence the renderer runs, with the
 * exact values it passes:
 *
 *   ordinary start:  IdrWaitTracker.onSessionStart -> false,
 *                    CodecConfigCache.onSessionStart(false) -> invalidate
 *   stopSession:     IdrWaitTracker.clear() (deadline only; the pending
 *                    flag survives), cache untouched
 *   recovery start:  IdrWaitTracker.onSessionStart -> true (flag was set
 *                    by markRecoveryRestartPending in _escalate),
 *                    CodecConfigCache.onSessionStart(true) -> preserve
 *
 * The old round-6 test only showed the cache surviving a simulated
 * resync that never called invalidate() — it never exercised the
 * startSession policy that was actually destroying the cache.
 */
class SessionLifecycleTest {

    /** One H.264 NAL: 4-byte start code + header byte + payload bytes. */
    private fun h264Nal(type: Int, vararg payload: Int): ByteArray {
        val out = mutableListOf<Byte>()
        out += listOf(0, 0, 0, 1, type).map { it.toByte() }
        out += payload.map { it.toByte() }
        return out.toByteArray()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var off = 0
        for (p in parts) {
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }

    private val sps = h264Nal(7, 0x64, 0x00, 0x1F)
    private val pps = h264Nal(8, 0xEE, 0x06)
    private val idr = h264Nal(5, 0x88, 0x99, 0xAA)
    private val pSlice = h264Nal(1, 0x41, 0x42)

    /** The start-coded SPS the renderer replays as csd-0 (round 7 P1-1). */
    private val startCodedSps = concat(byteArrayOf(0, 0, 0, 1), byteArrayOf(7, 0x64, 0x00, 0x1F))

    /** Mirrors VideoRenderer.stopSession: clears the IDR-wait deadline,
     * keeps the recovery-pending flag, leaves the config cache alone. */
    private fun stopSession(wait: IdrWaitTracker) {
        wait.clear()
    }

    /** Mirrors VideoRenderer.startSession's cache decision. Returns the
     * recoveryRestart flag the tracker reports for this start. */
    private fun startSession(wait: IdrWaitTracker, cache: CodecConfigCache, nowNs: Long): Boolean {
        val recoveryRestart = wait.onSessionStart(nowNs)
        cache.onSessionStart(recoveryRestart)
        return recoveryRestart
    }

    @Test
    fun recoveryRestartLifecyclePreservesCache() {
        // Full lifecycle: initial SPS/PPS+IDR -> P frames -> resync ->
        // recovery-triggered stopSession/startSession -> bare IDR.
        // The new decoder must still be created with the cached
        // configuration (start-coded), and the bare IDR must still pass
        // the keyframe gate.
        val wait = IdrWaitTracker()
        val cache = CodecConfigCache()
        var now = 1_000_000L

        // Session 1, ordinary start: cache fills from SPS/PPS+IDR.
        assertFalse(startSession(wait, cache, now))
        cache.update(concat(sps, pps, idr), false)
        cache.update(pSlice, false)
        assertTrue(cache.isComplete)

        // Resync destroys the codec; the cache survives it (round 6 P1-3).
        // (No invalidate() call — that is the point of the resync path.)

        // Escalation requests a session restart; the renderer posts
        // stopSession() then startSession().
        wait.markRecoveryRestartPending()
        stopSession(wait)
        now += 1_000_000L
        assertTrue(startSession(wait, cache, now))

        // A bare IDR after the recovery restart still gets its config.
        assertTrue(cache.isComplete)
        val (csd0, csd1) = cache.csdFor(false)!!
        assertArrayEquals(startCodedSps, csd0)
        assertArrayEquals(concat(byteArrayOf(0, 0, 0, 1), byteArrayOf(8, 0xEE.toByte(), 0x06)), csd1)
        assertFalse(cache.update(idr, false))
        assertTrue(NalUtils.isKeyframe(idr, false))
    }

    @Test
    fun ordinaryStartAfterRecoveryInvalidatesCache() {
        // Once the recovery flag is consumed, the next start is an
        // ordinary (genuinely new) session and must invalidate — and
        // the new session's own IDR refills the cache.
        val wait = IdrWaitTracker()
        val cache = CodecConfigCache()
        var now = 1_000_000L

        assertFalse(startSession(wait, cache, now))
        cache.update(concat(sps, pps, idr), false)
        assertTrue(cache.isComplete)

        wait.markRecoveryRestartPending()
        stopSession(wait)
        now += 1_000_000L
        assertTrue(startSession(wait, cache, now))
        assertTrue(cache.isComplete)

        // Fresh sender/session: no pending flag -> ordinary start.
        stopSession(wait)
        now += 1_000_000L
        assertFalse(startSession(wait, cache, now))
        assertFalse(cache.isComplete)
        assertNull(cache.csdFor(false))

        // The new session's IDR brings its own parameter sets.
        assertTrue(cache.update(concat(sps, pps, idr), false))
        assertTrue(cache.isComplete)
        assertArrayEquals(startCodedSps, cache.csdFor(false)!!.first)
    }

    @Test
    fun formatChangeInvalidatesEvenAfterRecovery() {
        // A format change (setResolution path -> invalidate()) clears
        // the cache even when a recovery restart just preserved it:
        // "preserve on recovery" covers the session axis only.
        val wait = IdrWaitTracker()
        val cache = CodecConfigCache()
        var now = 1_000_000L

        assertFalse(startSession(wait, cache, now))
        cache.update(concat(sps, pps, idr), false)
        assertTrue(cache.isComplete)

        wait.markRecoveryRestartPending()
        stopSession(wait)
        now += 1_000_000L
        assertTrue(startSession(wait, cache, now))
        assertTrue(cache.isComplete)

        // Format change: the setResolution path invalidates directly.
        cache.invalidate()
        assertFalse(cache.isComplete)
        assertNull(cache.csdFor(false))
    }

    @Test
    fun recoveryFlagIsConsumedExactlyOnce() {
        // The pending flag must not leak into the following ordinary
        // start: one recovery restart consumes it.
        val wait = IdrWaitTracker()
        val cache = CodecConfigCache()
        var now = 1_000_000L

        assertFalse(startSession(wait, cache, now))
        cache.update(concat(sps, pps, idr), false)

        wait.markRecoveryRestartPending()
        stopSession(wait)
        now += 1_000_000L
        assertTrue(startSession(wait, cache, now))
        assertTrue(cache.isComplete)

        // No new escalation: the next start is ordinary again.
        stopSession(wait)
        now += 1_000_000L
        assertFalse(startSession(wait, cache, now))
        assertFalse(cache.isComplete)
    }
}
