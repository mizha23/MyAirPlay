package io.github.jqssun.airplay.service
import org.junit.Assert.*
import org.junit.Test
class ReturnHomePolicyTest {
    @Test fun completedVideoWithConnectedSenderStaysInAirPlay() {
        assertFalse(ReturnHomePolicy.shouldReturn(true, 1, false, false, false))
    }
    @Test fun actualDisconnectReturnsHome() {
        assertTrue(ReturnHomePolicy.shouldReturn(true, 0, false, false, false))
    }
    @Test fun connectionProbeDoesNotNavigateHome() {
        assertFalse(ReturnHomePolicy.shouldReturn(false, 0, false, false, false))
    }
    @Test fun activeOrTransitioningPlaybackDoesNotNavigateHome() {
        assertFalse(ReturnHomePolicy.shouldReturn(true, 0, true, false, false))
        assertFalse(ReturnHomePolicy.shouldReturn(true, 0, false, true, false))
        assertFalse(ReturnHomePolicy.shouldReturn(true, 0, false, false, true))
    }
}
