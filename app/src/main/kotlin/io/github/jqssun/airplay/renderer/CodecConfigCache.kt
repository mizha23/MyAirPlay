package io.github.jqssun.airplay.renderer

/**
 * Pure cache of the decoder parameter sets (SPS/PPS, plus VPS for
 * H.265) parsed out of the Annex-B stream, kept separate from
 * random-access (IDR) detection (see NalUtils).
 *
 * Why it exists (round 6 P1-3): after a resync VideoRenderer destroys
 * the decoder and starts a fresh one on the next IDR, but the fresh
 * decoder was created with no csd-0/csd-1. The native layer only
 * prepends SPS/PPS to the first VCL packet after they arrive
 * (raop_rtp_mirror.c: prepend_sps_pps, cleared after one prepend), so
 * a later bare IDR carries no parameter sets and a bare IDR cannot
 * configure a new decoder. This cache keeps the latest parameter sets
 * so the new decoder can be created with them replayed.
 *
 * Update on every incoming frame, before queueing — the scan is one
 * linear pass and only the parameter-set NALs are retained. A frame
 * that is later dropped by a queue overflow still leaves its
 * parameter sets in the cache, which is exactly the case that needs
 * them. Invalidate on format/session changes; a resync must NOT
 * invalidate it (replaying after a resync is the whole point).
 *
 * Stored NALs are the raw units without start codes. [csdFor] adds a
 * 4-byte Annex-B start code (00 00 00 01) in front of every parameter
 * set it exports: Android requires each parameter set in csd-0/csd-1
 * to begin with one (MediaCodec CSD docs). Round 6 exported the bare
 * NALs and its tests asserted exactly that — the passing tests were
 * validating the wrong output format (round 7 P1-1).
 */
class CodecConfigCache {
    companion object {
        /** Annex-B start code: every exported parameter set starts with it. */
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }

    /** [nal] prefixed with the 4-byte Annex-B start code. */
    private fun startCoded(nal: ByteArray): ByteArray {
        val out = ByteArray(START_CODE.size + nal.size)
        START_CODE.copyInto(out, 0)
        nal.copyInto(out, START_CODE.size)
        return out
    }

    var sps: ByteArray? = null
        private set
    var pps: ByteArray? = null
        private set
    var vps: ByteArray? = null
        private set
    var isH265: Boolean = false
        private set

    /** True when [csdFor] can produce a complete configuration. */
    val isComplete: Boolean
        get() = if (isH265) vps != null && sps != null && pps != null
        else sps != null && pps != null

    /**
     * Parse the Annex-B NALs out of [data] and keep any parameter
     * sets. Returns true if the cache changed. A codec-type switch
     * drops the old config first (parameter sets from the other codec
     * must never be replayed).
     */
    fun update(data: ByteArray, isH265: Boolean): Boolean {
        if (isH265 != this.isH265 && (sps != null || pps != null || vps != null)) {
            invalidate()
        }
        this.isH265 = isH265
        var changed = false
        NalUtils.forEachNal(data, isH265) { type, nalStart, nalEnd ->
            val isParamSet = when {
                isH265 -> type == NalUtils.NAL_H265_VPS || type == NalUtils.NAL_H265_SPS ||
                    type == NalUtils.NAL_H265_PPS
                else -> type == NalUtils.NAL_H264_SPS || type == NalUtils.NAL_H264_PPS
            }
            if (!isParamSet) return@forEachNal
            val nal = data.copyOfRange(nalStart, nalEnd)
            when {
                isH265 && type == NalUtils.NAL_H265_VPS -> if (!vps.contentEquals(nal)) { vps = nal; changed = true }
                isH265 && type == NalUtils.NAL_H265_SPS -> if (!sps.contentEquals(nal)) { sps = nal; changed = true }
                isH265 && type == NalUtils.NAL_H265_PPS -> if (!pps.contentEquals(nal)) { pps = nal; changed = true }
                !isH265 && type == NalUtils.NAL_H264_SPS -> if (!sps.contentEquals(nal)) { sps = nal; changed = true }
                !isH265 && type == NalUtils.NAL_H264_PPS -> if (!pps.contentEquals(nal)) { pps = nal; changed = true }
            }
        }
        return changed
    }

    /**
     * The csd-0 / csd-1 pair to set on a freshly created MediaFormat,
     * or null when the cache is incomplete or belongs to the other
     * codec. H.264: csd-0 = SPS, csd-1 = PPS. H.265: csd-0 = VPS+SPS+PPS
     * concatenated, csd-1 = null (the standard HEVC layout). Every
     * exported parameter set is prefixed with the 4-byte Annex-B start
     * code (00 00 00 01) — Android requires it for csd-0/csd-1
     * (round 7 P1-1); the internally cached bytes stay raw.
     */
    fun csdFor(h265: Boolean): Pair<ByteArray, ByteArray?>? {
        if (h265 != isH265 || !isComplete) return null
        return if (h265) {
            val parts = listOf(vps!!, sps!!, pps!!)
            val combined = ByteArray(parts.sumOf { START_CODE.size + it.size })
            var off = 0
            for (nal in parts) {
                START_CODE.copyInto(combined, off)
                off += START_CODE.size
                nal.copyInto(combined, off)
                off += nal.size
            }
            combined to null
        } else {
            startCoded(sps!!) to startCoded(pps!!)
        }
    }

    /**
     * Session-start policy (round 7 P1-2). Call from
     * VideoRenderer.startSession with the same recoveryRestart flag
     * IdrWaitTracker reports. A recovery restart only restarts the
     * renderer — the RAOP sender connection is unchanged, so the
     * sender will NOT resend parameter sets just because we restarted;
     * the cache must survive it, because it is what the next bare IDR
     * needs. Invalidate only for a genuinely new session, whose first
     * IDR brings its own SPS/PPS. A format change invalidates through
     * [invalidate] (setResolution), and a codec-type switch drops the
     * old config in [update]; this method covers the session axis only.
     */
    fun onSessionStart(recoveryRestart: Boolean) {
        if (!recoveryRestart) invalidate()
    }

    /** Drop the cached parameter sets (format or session change). */
    fun invalidate() {
        sps = null
        pps = null
        vps = null
    }
}
