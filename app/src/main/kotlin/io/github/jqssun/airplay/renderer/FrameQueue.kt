package io.github.jqssun.airplay.renderer

/** One mirror frame waiting to be decoded. [generation] is the resync
 * epoch it was enqueued in (see VideoRenderer._generation): a frame
 * polled before a resync must never reach the post-resync decoder. */
data class QueuedFrame(
    val data: ByteArray,
    val ntpTimeNs: Long,
    val isH265: Boolean,
    val arrivalNs: Long,
    val isKeyframe: Boolean,
    var generation: Long = 0L,
)

/**
 * Bounded FIFO for mirror frames with keyframe-aware overflow.
 *
 * When full, [offer] purges everything up to the next queued keyframe —
 * a keyframe is the only thing that can resync the decoder, so it is
 * protected whenever possible. If the queue is still full with a keyframe
 * at the head, a non-keyframe arrival is dropped instead; a keyframe
 * arrival replaces the oldest queued non-keyframe (or the head keyframe
 * when the queue holds nothing but keyframes).
 * [offer] returns how many frames it dropped (0 or more) so the caller can
 * count them in the diagnostics overlay.
 *
 * Any drop breaks the decoder's reference chain until the next IDR: the
 * frames ahead of it in the queue reference a frame the decoder will never
 * see, so feeding them can only produce corruption — and on the VAVA's
 * Amlogic decoder a broken chain wedged output entirely (2026-11-16: the
 * session kept dropping and never recovered). So [offer] now purges
 * everything up to the next queued keyframe when it has to drop, and sets
 * [resyncNeeded]: the renderer must flush the decoder and restart it on
 * that keyframe (see VideoRenderer._doResync) instead of feeding the
 * surviving pre-IDR frames. When the queue holds no keyframe at all,
 * everything is dropped and the renderer waits for the sender's next IDR.
 *
 * Pure Kotlin, no Android imports — unit-testable on the JVM.
 * Not thread-safe by itself; callers guard it with the renderer's lock.
 */
class FrameQueue(private val capacity: Int) {
    companion object { const val MIRROR_CAPACITY = 60 }
    private val deque = ArrayDeque<QueuedFrame>()

    val size: Int get() = deque.size
    val isEmpty: Boolean get() = deque.isEmpty()
    val isNotEmpty: Boolean get() = deque.isNotEmpty()

    /**
     * Set when a drop broke the decoder's reference chain; the renderer
     * must flush the decoder and wait for the next keyframe. Cleared by
     * [clearResyncFlag] once the resync has run.
     */
    var resyncNeeded = false
        private set

    fun clearResyncFlag() {
        resyncNeeded = false
    }

    /** Offers a frame, dropping older ones first if full. Returns frames dropped. */
    fun offer(frame: QueuedFrame): Int {
        if (deque.size < capacity) {
            deque.addLast(frame)
            return 0
        }
        // Full: dropping anything breaks the reference chain until the next
        // IDR, so the pre-IDR frames still queued are undecodable anyway.
        resyncNeeded = true
        var dropped = dropToNextKeyframe()
        if (deque.size >= capacity) {
            // Still full: the head is now a keyframe, and it is the resync
            // point — it must survive. A non-keyframe arrival is
            // expendable; a keyframe arrival replaces the oldest queued
            // non-keyframe (or the head keyframe when there is nothing
            // else to give up).
            if (!frame.isKeyframe) return dropped + 1
            val victim = deque.indexOfFirst { !it.isKeyframe }
            if (victim < 0) deque.removeFirst() else deque.removeAt(victim)
            dropped++
        }
        deque.addLast(frame)
        return dropped
    }

    /**
     * Drops frames up to (not including) the next keyframe, or everything
     * when the queue holds no keyframe. Returns frames dropped.
     */
    fun dropToNextKeyframe(): Int {
        var dropped = 0
        while (deque.isNotEmpty() && !deque.first().isKeyframe) {
            deque.removeFirst()
            dropped++
        }
        return dropped
    }

    fun poll(): QueuedFrame? = deque.removeFirstOrNull()
    fun peek(): QueuedFrame? = deque.firstOrNull()

    /**
     * Re-stamps every still-queued frame to [generation]. Used by a
     * resync: the frames it deliberately keeps (the next IDR and what
     * follows it) belong to the new epoch, while the frame the decode
     * thread already polled keeps the old one and is dropped at the feed
     * gate instead of reaching the new decoder.
     */
    fun restampGeneration(generation: Long) {
        deque.forEach { it.generation = generation }
    }

    fun clear() = deque.clear()
}
