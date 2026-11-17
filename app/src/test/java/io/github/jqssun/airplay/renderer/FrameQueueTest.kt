package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQueueTest {
    @Test fun startupBurstPreservesReferenceChainWhileCodecStarts() {
        val old = FrameQueue(10)
        val current = FrameQueue(FrameQueue.MIRROR_CAPACITY)
        for (i in 1..24) {
            old.offer(frame(false, i * 16_667L))
            assertEquals(0, current.offer(frame(false, i * 16_667L)))
        }
        assertTrue(old.resyncNeeded)
        assertFalse(current.resyncNeeded)
        for (i in 1..24) assertEquals(i * 16_667L, current.poll()?.ntpTimeNs)
        assertTrue(current.isEmpty)
    }


    private fun frame(keyframe: Boolean, ptsUs: Long = 0L, generation: Long = 0L) =
        QueuedFrame(ByteArray(0), ptsUs, false, 0L, keyframe, generation)

    @Test
    fun overflowWithoutKeyframeDropsAllAndFlagsResync() {
        // No keyframe queued: every queued frame references a frame the
        // decoder will never see, so all of them go and the renderer must
        // wait for the sender's next IDR.
        val q = FrameQueue(3)
        q.offer(frame(false, 0L))
        q.offer(frame(false, 1L))
        q.offer(frame(false, 2L))
        assertEquals(3, q.offer(frame(false, 3L)))
        assertEquals(1, q.size)
        assertEquals(3L, q.peek()?.ntpTimeNs)
        assertTrue(q.resyncNeeded)
    }

    @Test
    fun overflowDropsToNextKeyframe() {
        val q = FrameQueue(3)
        q.offer(frame(true, 0L)) // keyframe must survive
        q.offer(frame(false, 1L))
        q.offer(frame(false, 2L))
        // Full with the keyframe at the head: the incoming non-keyframe is
        // expendable, the queued keyframe is the resync point and stays.
        assertEquals(1, q.offer(frame(false, 3L)))
        assertEquals(3, q.size)
        assertEquals(0L, q.poll()?.ntpTimeNs) // keyframe still first
        assertEquals(1L, q.poll()?.ntpTimeNs)
        assertEquals(2L, q.poll()?.ntpTimeNs)
        assertTrue(q.isEmpty)
        assertTrue(q.resyncNeeded)
        q.clearResyncFlag()
        assertFalse(q.resyncNeeded)
    }

    @Test
    fun overflowPurgesPreIdrFrames() {
        val q = FrameQueue(3)
        q.offer(frame(false, 0L))
        q.offer(frame(false, 1L))
        q.offer(frame(true, 2L))
        assertEquals(2, q.offer(frame(false, 3L)))
        assertEquals(2, q.size)
        assertEquals(2L, q.poll()?.ntpTimeNs) // keyframe first
        assertEquals(3L, q.poll()?.ntpTimeNs)
        assertTrue(q.resyncNeeded)
    }

    @Test
    fun allKeyframesDropsHead() {
        val q = FrameQueue(2)
        q.offer(frame(true, 0L))
        q.offer(frame(true, 1L))
        assertEquals(1, q.offer(frame(true, 2L)))
        assertEquals(2, q.size)
        assertEquals(1L, q.poll()?.ntpTimeNs)
        assertEquals(2L, q.poll()?.ntpTimeNs)
        assertTrue(q.resyncNeeded)
    }

    @Test
    fun dropToNextKeyframeKeepsKeyframe() {
        val q = FrameQueue(10)
        q.offer(frame(false, 0L))
        q.offer(frame(false, 1L))
        q.offer(frame(true, 2L))
        q.offer(frame(false, 3L))
        assertEquals(2, q.dropToNextKeyframe())
        assertEquals(2, q.size)
        assertEquals(2L, q.peek()?.ntpTimeNs)
        assertTrue(q.peek()?.isKeyframe == true)
        // Dropping to the keyframe on its own does not flag a resync —
        // only offer() flags it, so a resync cannot re-trigger itself.
        assertFalse(q.resyncNeeded)
    }

    @Test
    fun fifoOrderAndClear() {
        val q = FrameQueue(10)
        q.offer(frame(false, 0L))
        q.offer(frame(true, 1L))
        q.offer(frame(false, 2L))
        assertEquals(0L, q.poll()?.ntpTimeNs)
        assertEquals(1L, q.poll()?.ntpTimeNs)
        assertEquals(2L, q.poll()?.ntpTimeNs)
        assertTrue(q.isEmpty)
        q.offer(frame(false, 3L))
        q.clear()
        assertTrue(q.isEmpty)
        assertEquals(0, q.size)
        assertFalse(q.resyncNeeded)
    }

    @Test
    fun offerPreservesGeneration() {
        val q = FrameQueue(10)
        q.offer(frame(false, 0L, generation = 3L))
        assertEquals(3L, q.poll()?.generation)
    }

    @Test
    fun restampGenerationUpdatesQueued() {
        val q = FrameQueue(10)
        q.offer(frame(false, 0L))
        q.offer(frame(true, 1L))
        q.restampGeneration(7L)
        assertEquals(7L, q.poll()?.generation)
        assertEquals(7L, q.poll()?.generation)
        assertTrue(q.isEmpty)
    }

    @Test
    fun restampAfterDropKeepsIdrGeneration() {
        // The resync flow: drop to the next IDR, then re-stamp the
        // survivors to the new epoch so the feed gate lets them through.
        val q = FrameQueue(10)
        q.offer(frame(false, 0L))
        q.offer(frame(false, 1L))
        q.offer(frame(true, 2L))
        q.offer(frame(false, 3L))
        assertEquals(2, q.dropToNextKeyframe())
        q.restampGeneration(5L)
        assertEquals(5L, q.peek()?.generation)
        assertTrue(q.peek()?.isKeyframe == true)
    }
}
