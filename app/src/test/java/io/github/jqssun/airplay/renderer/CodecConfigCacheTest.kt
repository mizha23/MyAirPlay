package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [CodecConfigCache] (round 6 P1-3).
 *
 * The cache keeps the latest SPS/PPS (+VPS for H.265) parsed out of
 * the Annex-B stream so a decoder (re)started on a bare IDR can be
 * created with its configuration replayed as csd-0/csd-1.
 */
class CodecConfigCacheTest {

    /** One H.264 NAL: 4-byte start code + header byte + payload bytes. */
    private fun h264Nal(type: Int, vararg payload: Int): ByteArray {
        val out = mutableListOf<Byte>()
        out += listOf(0, 0, 0, 1, type).map { it.toByte() }
        out += payload.map { it.toByte() }
        return out.toByteArray()
    }

    /** The raw NAL bytes (no start code) for [h264Nal] — what the cache stores internally. */
    private fun h264NalRaw(type: Int, vararg payload: Int): ByteArray {
        val out = mutableListOf<Byte>()
        out += type.toByte()
        out += payload.map { it.toByte() }
        return out.toByteArray()
    }

    /** One H.265 NAL: 4-byte start code + 2 header bytes + payload bytes. */
    private fun h265Nal(type: Int, vararg payload: Int): ByteArray {
        val out = mutableListOf<Byte>()
        out += listOf(0, 0, 0, 1, (type shl 1) and 0x7E, 0x01).map { it.toByte() }
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

    @Test
    fun h264CapturesSpsPpsFromSpsPpsIdr() {
        val c = CodecConfigCache()
        assertTrue(c.update(concat(sps, pps, idr), false))
        assertTrue(c.isComplete)
        val (csd0, csd1) = c.csdFor(false)!!
        // Round 7 P1-1: every exported parameter set begins with the
        // 4-byte Annex-B start code (00 00 00 01), as Android requires
        // for csd-0/csd-1. The old test asserted bare NALs here — that
        // passing result was validating the wrong output format.
        assertArrayEquals(concat(byteArrayOf(0, 0, 0, 1), h264NalRaw(7, 0x64, 0x00, 0x1F)), csd0)
        assertArrayEquals(concat(byteArrayOf(0, 0, 0, 1), h264NalRaw(8, 0xEE, 0x06)), csd1)
    }

    @Test
    fun pFramesDoNotDisturbCache() {
        val c = CodecConfigCache()
        c.update(concat(sps, pps, idr), false)
        assertFalse(c.update(pSlice, false))
        assertTrue(c.isComplete)
        val (csd0, csd1) = c.csdFor(false)!!
        assertArrayEquals(concat(byteArrayOf(0, 0, 0, 1), h264NalRaw(7, 0x64, 0x00, 0x1F)), csd0)
        assertArrayEquals(concat(byteArrayOf(0, 0, 0, 1), h264NalRaw(8, 0xEE, 0x06)), csd1)
    }

    @Test
    fun resyncThenBareIdrReplaysCachedConfig() {
        // The P1-3 integration sequence: initial SPS/PPS+IDR -> P
        // frames -> resync (the cache is NOT invalidated by a resync)
        // -> bare IDR. The new decoder must still be created with the
        // cached configuration, and the bare IDR must still pass the
        // keyframe gate.
        val c = CodecConfigCache()
        c.update(concat(sps, pps, idr), false)
        c.update(pSlice, false)
        // (simulated resync: no invalidate() call — that is the point)
        assertFalse(c.update(idr, false))
        assertTrue(c.isComplete)
        val (csd0, csd1) = c.csdFor(false)!!
        assertArrayEquals(concat(byteArrayOf(0, 0, 0, 1), h264NalRaw(7, 0x64, 0x00, 0x1F)), csd0)
        assertArrayEquals(concat(byteArrayOf(0, 0, 0, 1), h264NalRaw(8, 0xEE, 0x06)), csd1)
        // The bare IDR carries no parameter sets of its own...
        val types = mutableListOf<Int>()
        NalUtils.forEachNal(idr, false) { type, _, _ -> types += type }
        assertEquals(listOf(5), types)
        // ...but it is still a genuine keyframe.
        assertTrue(NalUtils.isKeyframe(idr, false))
    }

    @Test
    fun spsOnlyIsNotComplete() {
        val c = CodecConfigCache()
        assertTrue(c.update(sps, false))
        assertFalse(c.isComplete)
        assertNull(c.csdFor(false))
        assertTrue(c.update(pps, false))
        assertTrue(c.isComplete)
    }

    @Test
    fun invalidateClearsOnFormatOrSessionChange() {
        val c = CodecConfigCache()
        c.update(concat(sps, pps, idr), false)
        assertTrue(c.isComplete)
        c.invalidate()
        assertFalse(c.isComplete)
        assertNull(c.csdFor(false))
        // A fresh session's IDR brings its own parameter sets.
        assertTrue(c.update(concat(sps, pps, idr), false))
        assertTrue(c.isComplete)
    }

    @Test
    fun h265CapturesVpsSpsPps() {
        val vps = h265Nal(32, 0x0C, 0x01)
        val sps265 = h265Nal(33, 0x42, 0x01)
        val pps265 = h265Nal(34, 0x60, 0x01)
        val idr265 = h265Nal(19, 0x02, 0x03)
        val c = CodecConfigCache()
        assertTrue(c.update(concat(vps, sps265, pps265, idr265), true))
        assertTrue(c.isComplete)
        val (csd0, csd1) = c.csdFor(true)!!
        // HEVC: csd-0 = VPS+SPS+PPS concatenated, csd-1 = null — each
        // parameter set prefixed with its own 4-byte start code
        // (round 7 P1-1: the old test asserted the raw concatenation
        // with no delimiters at all).
        assertNull(csd1)
        val expected = concat(
            byteArrayOf(0, 0, 0, 1, ((32 shl 1) and 0x7E).toByte(), 0x01, 0x0C, 0x01),
            byteArrayOf(0, 0, 0, 1, ((33 shl 1) and 0x7E).toByte(), 0x01, 0x42, 0x01),
            byteArrayOf(0, 0, 0, 1, ((34 shl 1) and 0x7E).toByte(), 0x01, 0x60, 0x01),
        )
        assertArrayEquals(expected, csd0)
        // Wrong codec type: no config.
        assertNull(c.csdFor(false))
    }

    @Test
    fun codecTypeSwitchDropsOldConfig() {
        val c = CodecConfigCache()
        c.update(concat(sps, pps, idr), false)
        assertTrue(c.isComplete)
        // An H.265 SPS alone must not leave H.264 config behind.
        c.update(h265Nal(33, 0x42, 0x01), true)
        assertFalse(c.isComplete)
        assertNull(c.csdFor(false))
        assertNull(c.csdFor(true))
    }

    @Test
    fun threeByteStartCodesWork() {
        // 3-byte start codes (00 00 01) are accepted too.
        val packet = concat(
            byteArrayOf(0, 0, 1, 7, 0x64, 0x00, 0x1F),
            byteArrayOf(0, 0, 1, 8, 0xEE.toByte(), 0x06),
            byteArrayOf(0, 0, 1, 5, 0x88.toByte()),
        )
        val c = CodecConfigCache()
        assertTrue(c.update(packet, false))
        assertTrue(c.isComplete)
    }

    @Test
    fun csdExportsAreAndroidCompliant() {
        // Round 7 P1-1: every exported parameter set begins with the
        // 4-byte Annex-B start code, and re-parsing the exported bytes
        // yields exactly the original parameter sets — the assertion
        // the old bare-NAL tests were missing.
        val c = CodecConfigCache()
        c.update(concat(sps, pps, idr), false)
        val (csd0, csd1) = c.csdFor(false)!!
        // Internal storage stays raw (no start codes).
        assertArrayEquals(h264NalRaw(7, 0x64, 0x00, 0x1F), c.sps!!)
        assertArrayEquals(h264NalRaw(8, 0xEE, 0x06), c.pps!!)
        // Exported bytes: one start-coded NAL each.
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), csd0.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), csd1!!.copyOfRange(0, 4))
        val types0 = mutableListOf<Int>()
        val payloads0 = mutableListOf<ByteArray>()
        NalUtils.forEachNal(csd0, false) { type, start, end ->
            types0 += type
            payloads0 += csd0.copyOfRange(start, end)
        }
        assertEquals(listOf(7), types0)
        assertArrayEquals(h264NalRaw(7, 0x64, 0x00, 0x1F), payloads0[0])
        val types1 = mutableListOf<Int>()
        NalUtils.forEachNal(csd1!!, false) { type, _, _ -> types1 += type }
        assertEquals(listOf(8), types1)

        // HEVC: csd-0 parses back to exactly VPS, SPS, PPS in order.
        val vps = h265Nal(32, 0x0C, 0x01)
        val sps265 = h265Nal(33, 0x42, 0x01)
        val pps265 = h265Nal(34, 0x60, 0x01)
        val c265 = CodecConfigCache()
        c265.update(concat(vps, sps265, pps265, h265Nal(19, 0x02, 0x03)), true)
        val (csd0_265, csd1_265) = c265.csdFor(true)!!
        assertNull(csd1_265)
        val types265 = mutableListOf<Int>()
        val payloads265 = mutableListOf<ByteArray>()
        NalUtils.forEachNal(csd0_265, true) { type, start, end ->
            types265 += type
            payloads265 += csd0_265.copyOfRange(start, end)
        }
        assertEquals(listOf(32, 33, 34), types265)
        assertEquals(3, payloads265.size)
        assertArrayEquals(byteArrayOf(((32 shl 1) and 0x7E).toByte(), 0x01, 0x0C, 0x01), payloads265[0])
        assertArrayEquals(byteArrayOf(((33 shl 1) and 0x7E).toByte(), 0x01, 0x42, 0x01), payloads265[1])
        assertArrayEquals(byteArrayOf(((34 shl 1) and 0x7E).toByte(), 0x01, 0x60, 0x01), payloads265[2])
    }

    @Test
    fun onSessionStartPreservesCacheForRecoveryRestart() {
        // Round 7 P1-2: a recovery restart must NOT invalidate the
        // cache — the sender will not resend parameter sets.
        val c = CodecConfigCache()
        c.update(concat(sps, pps, idr), false)
        assertTrue(c.isComplete)
        c.onSessionStart(recoveryRestart = true)
        assertTrue(c.isComplete)
        assertArrayEquals(
            concat(byteArrayOf(0, 0, 0, 1), h264NalRaw(7, 0x64, 0x00, 0x1F)),
            c.csdFor(false)!!.first,
        )
    }

    @Test
    fun onSessionStartInvalidatesForOrdinaryStart() {
        // A genuinely new session clears the cache; its own IDR brings
        // fresh parameter sets.
        val c = CodecConfigCache()
        c.update(concat(sps, pps, idr), false)
        assertTrue(c.isComplete)
        c.onSessionStart(recoveryRestart = false)
        assertFalse(c.isComplete)
        assertNull(c.csdFor(false))
        assertTrue(c.update(concat(sps, pps, idr), false))
        assertTrue(c.isComplete)
    }
}
