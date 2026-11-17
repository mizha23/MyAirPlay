package io.github.jqssun.airplay.renderer

/** Bounded wait for a compressed frame; temporary codec backpressure is not a drop. */
class CodecInputWait(startNs: Long, firstFrame: Boolean) {
    private val deadlineNs = startNs + if (firstFrame) 1_000_000_000L else 500_000_000L

    /** Short slices let session/codec replacement cancel between native waits. */
    fun nextTimeoutUs(nowNs: Long, active: Boolean, sameCodec: Boolean): Long {
        if (!active || !sameCodec) return 0L
        val remaining = deadlineNs - nowNs
        if (remaining <= 0L) return 0L
        return ((remaining + 999L) / 1000L).coerceAtMost(20_000L)
    }
}
