package io.github.jqssun.airplay.renderer

/**
 * True-IDR detection for mirror frames (round 5). Pure Kotlin, no Android
 * imports — unit-testable on the JVM (see NalUtilsTest).
 *
 * The old check counted parameter sets as keyframes: H.264 SPS (NAL type
 * 7) and H.265 VPS (32) / SPS (33). That is wrong — a parameter set is
 * not a picture and cannot restart a decoder. It matters because the
 * native layer prepends the sender's SPS+PPS to the next VCL packet
 * (raop_rtp_mirror.c: the unencrypted 0x01 SPS+PPS packet is held and
 * prepended to the following encrypted packet), so an SPS-prepended
 * P-slice reached the app and passed the old keyframe gate. After a
 * resync destroyed the codec, that P-slice "restarted" it with no IDR
 * behind it: the decoder produced nothing, the watchdog fired another
 * resync, and the session looped 11 times into a black screen
 * (VAVA 2026-11-16).
 *
 * Only a genuine IDR opens the gate now: H.264 IDR slice (type 5),
 * H.265 IDR_W_RADL (19) / IDR_N_LP (20). H.265 CRA (21) is deliberately
 * excluded — it is a random-access point, not an IDR, and its leading
 * pictures may reference pre-CRA frames, so it cannot guarantee a clean
 * restart. SEI (6), SPS (7), PPS (8) / VPS (32), SPS (33), PPS (34)
 * never count.
 */
object NalUtils {
    const val NAL_H264_SPS = 7
    const val NAL_H264_PPS = 8
    const val NAL_H265_VPS = 32
    const val NAL_H265_SPS = 33
    const val NAL_H265_PPS = 34

    /** NAL type of the unit whose first byte (after the start code) is at [nalStart]. */
    fun nalType(data: ByteArray, nalStart: Int, isH265: Boolean): Int =
        if (isH265) (data[nalStart].toInt() shr 1) and 0x3F
        else data[nalStart].toInt() and 0x1F

    /**
     * Visit every Annex-B NAL unit in [data] in order, invoking [action]
     * with (type, nalStart, nalEnd) — the raw NAL bytes without the
     * start code, i.e. data[nalStart..nalEnd).
     *
     * Accepts 3-byte (00 00 01) and 4-byte (00 00 00 01) start codes;
     * the 4-byte form is matched at its first 0x00, so its leading zero
     * is never counted as trailing data of the previous NAL.
     * Emulation prevention (00 00 03) inside a NAL payload is not a
     * start code and never splits.
     */
    fun forEachNal(data: ByteArray, isH265: Boolean, action: (type: Int, nalStart: Int, nalEnd: Int) -> Unit) {
        var pos = 0
        var nalStart = -1
        while (pos + 3 <= data.size) {
            val scLen = when {
                data[pos] == 0.toByte() && data[pos + 1] == 0.toByte() && data[pos + 2] == 1.toByte() -> 3
                pos + 4 <= data.size && data[pos] == 0.toByte() && data[pos + 1] == 0.toByte() &&
                    data[pos + 2] == 0.toByte() && data[pos + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (scLen > 0) {
                if (nalStart >= 0 && pos > nalStart) {
                    action(nalType(data, nalStart, isH265), nalStart, pos)
                }
                nalStart = pos + scLen
                pos += scLen
            } else {
                pos++
            }
        }
        if (nalStart >= 0 && data.size > nalStart) {
            action(nalType(data, nalStart, isH265), nalStart, data.size)
        }
    }

    fun isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 5) return false
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val key = if (isH265) {
                    val type = (data[i + 4].toInt() shr 1) and 0x3F
                    type == 19 || type == 20
                } else {
                    val type = data[i + 4].toInt() and 0x1F
                    type == 5
                }
                if (key) return true
            }
            i++
        }
        return false
    }
}
