package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [NalUtils] (round 5).
 *
 * The bug: the old keyframe check counted parameter sets as keyframes
 * (H.264 SPS type 7; H.265 VPS 32 / SPS 33), so an SPS-prepended P-slice
 * — the native layer prepends the sender's SPS+PPS to the next VCL
 * packet — passed the keyframe gate and (re)started the decoder with no
 * IDR behind it. On the VAVA that looped 11 resyncs into a black screen.
 */
class NalUtilsTest {

    /** One NAL per type: 4-byte start code + 1 header byte + 2 dummy bytes. */
    private fun h264(vararg nalTypes: Int): ByteArray {
        val out = mutableListOf<Byte>()
        for (t in nalTypes) {
            out += listOf(0, 0, 0, 1, t).map { it.toByte() }
            out += listOf(0x11, 0x22).map { it.toByte() }
        }
        return out.toByteArray()
    }

    /** H.265: header byte 0 is (type << 1), byte 1 is a dummy temporal byte. */
    private fun h265(vararg nalTypes: Int): ByteArray {
        val out = mutableListOf<Byte>()
        for (t in nalTypes) {
            out += listOf(0, 0, 0, 1, (t shl 1) and 0x7E, 0x01).map { it.toByte() }
            out += listOf(0x11, 0x22).map { it.toByte() }
        }
        return out.toByteArray()
    }

    @Test
    fun h264IdrIsKeyframe() {
        assertTrue(NalUtils.isKeyframe(h264(5), false))
    }

    @Test
    fun h264SpsIsNotKeyframe() {
        assertFalse(NalUtils.isKeyframe(h264(7), false))
    }

    @Test
    fun h264PpsIsNotKeyframe() {
        assertFalse(NalUtils.isKeyframe(h264(8), false))
    }

    @Test
    fun h264NonIdrSliceIsNotKeyframe() {
        assertFalse(NalUtils.isKeyframe(h264(1), false))
    }

    @Test
    fun h264SeiIsNotKeyframe() {
        assertFalse(NalUtils.isKeyframe(h264(6), false))
    }

    @Test
    fun h264SpsPpsIdrIsKeyframe() {
        assertTrue(NalUtils.isKeyframe(h264(7, 8, 5), false))
    }

    @Test
    fun h264SpsPpsNonIdrIsNotKeyframe() {
        // The round-5 bug: the SPS in front made the old check say yes.
        assertFalse(NalUtils.isKeyframe(h264(7, 8, 1), false))
    }

    @Test
    fun h264TooShortIsNotKeyframe() {
        assertFalse(NalUtils.isKeyframe(ByteArray(0), false))
        assertFalse(NalUtils.isKeyframe(byteArrayOf(0, 0, 0), false))
        assertFalse(NalUtils.isKeyframe(byteArrayOf(0, 0, 0, 1), false))
    }

    @Test
    fun h265Idr19IsKeyframe() {
        assertTrue(NalUtils.isKeyframe(h265(19), true))
    }

    @Test
    fun h265Idr20IsKeyframe() {
        assertTrue(NalUtils.isKeyframe(h265(20), true))
    }

    @Test
    fun h265CraIsNotKeyframe() {
        // CRA is a random-access point, not an IDR: its leading pictures
        // may reference pre-CRA frames, so it cannot guarantee recovery.
        assertFalse(NalUtils.isKeyframe(h265(21), true))
    }

    @Test
    fun h265VpsIsNotKeyframe() {
        assertFalse(NalUtils.isKeyframe(h265(32), true))
    }

    @Test
    fun h265SpsIsNotKeyframe() {
        assertFalse(NalUtils.isKeyframe(h265(33), true))
    }

    @Test
    fun h265PpsIsNotKeyframe() {
        assertFalse(NalUtils.isKeyframe(h265(34), true))
    }

    @Test
    fun h265NonIdrIsNotKeyframe() {
        assertFalse(NalUtils.isKeyframe(h265(1), true))
    }

    @Test
    fun h265VpsSpsPpsIdrIsKeyframe() {
        assertTrue(NalUtils.isKeyframe(h265(32, 33, 34, 19), true))
    }

    @Test
    fun h265VpsSpsPpsNonIdrIsNotKeyframe() {
        assertFalse(NalUtils.isKeyframe(h265(32, 33, 34, 1), true))
    }

    @Test
    fun forEachNalSplitsFourByteStartCodes() {
        val seen = mutableListOf<Triple<Int, Int, Int>>()
        NalUtils.forEachNal(h264(7, 8, 5), false) { type, start, end -> seen += Triple(type, start, end) }
        assertEquals(listOf(7, 8, 5), seen.map { it.first })
        // Each NAL is the raw unit without its start code: header byte
        // first, then the two dummy payload bytes.
        for ((type, start, end) in seen) {
            val packet = h264(7, 8, 5)
            assertEquals(3, end - start)
            assertEquals(type, packet[start].toInt())
        }
    }

    @Test
    fun forEachNalAcceptsThreeByteStartCodes() {
        val packet = byteArrayOf(
            0, 0, 1, 7, 0x11, 0x22,
            0, 0, 1, 8, 0x33, 0x44,
            0, 0, 1, 5, 0x55, 0x66,
        )
        val types = mutableListOf<Int>()
        NalUtils.forEachNal(packet, false) { type, _, _ -> types += type }
        assertEquals(listOf(7, 8, 5), types)
    }

    @Test
    fun forEachNalIgnoresEmulationPrevention() {
        // 00 00 03 inside a NAL payload is emulation prevention, not a
        // start code: the packet must stay one NAL.
        val packet = byteArrayOf(0, 0, 0, 1, 5, 0x00, 0x00, 0x03, 0x01, 0x11)
        val types = mutableListOf<Int>()
        NalUtils.forEachNal(packet, false) { type, start, end ->
            types += type
            assertEquals(6, end - start)
        }
        assertEquals(listOf(5), types)
    }
}
