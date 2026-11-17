package io.github.jqssun.airplay.service

/** Media end is not session end: a connected sender may choose another video. */
object ReturnHomePolicy {
    fun shouldReturn(hadMedia: Boolean, connections: Int, videoActive: Boolean, mirrorActive: Boolean, pendingVideo: Boolean): Boolean =
        hadMedia && connections == 0 && !videoActive && !mirrorActive && !pendingVideo
}
