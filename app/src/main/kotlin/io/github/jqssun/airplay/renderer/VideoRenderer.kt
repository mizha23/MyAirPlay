package io.github.jqssun.airplay.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import io.github.jqssun.airplay.renderer.DecoderSelector.Companion.videoCaps
import java.nio.ByteBuffer
import java.util.concurrent.locks.LockSupport

class VideoRenderer(ctx: Context) {

    @Volatile private var diagnosticQueued = 0L
    @Volatile private var diagnosticGated = 0L
    @Volatile private var diagnosticLastRawUs = 0L
    @Volatile private var diagnosticLastFedUs = 0L
    private var diagnosticLastLogNs = 0L
    private fun logMirrorHealth() {
        val now = System.nanoTime()
        if (now - diagnosticLastLogNs < 1_000_000_000L) return
        diagnosticLastLogNs = now
        synchronized(lock) {
            Log.i(TAG, "MirrorHealth pid=${android.os.Process.myPid()} gen=$_generation received=$frameCount queue=${frameQueue.size} queued=$diagnosticQueued gated=$diagnosticGated codecActive=${codec != null} config=${codecConfig.isComplete} idrWait=${idrWait.isWaiting()} decodeAlive=${decodeThread?.isAlive} drainAlive=$drainAlive released=$drainReleased errors=$drainErrors pendingMs=${if (_firstPendingFeedNs == 0L) 0 else (now - _firstPendingFeedNs) / 1_000_000L} rawUs=$diagnosticLastRawUs fedUs=$diagnosticLastFedUs gl=${pipeline.diagnosticState()}")
        }
    }
    @Volatile private var sessionActive = false
    private val lock = Object()
    private val pipeline = VideoPipeline()
    val selector = DecoderSelector(ctx)
    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var maxFps = 0
    private var codec: MediaCodec? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    @Volatile private var firstFrameQueued = false

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set
    // mirror diagnostics (frozen once per second for the debug overlay)
    @Volatile var outputFps = 0; private set
    @Volatile var arrivalJitterUs = 0L; private set
    @Volatile var ptsJitterUs = 0L; private set
    @Volatile var feedMeanMs = 0f; private set
    @Volatile var feedMaxMs = 0f; private set
    @Volatile var decodedWidth = 0; private set
    @Volatile var decodedHeight = 0; private set
    @Volatile var outputIntervalsMs: List<Float> = emptyList(); private set
    @Volatile var arrivalIntervalsMs: List<Float> = emptyList(); private set

    var enforceSdr = true
    var keyAllowFrameDrop = true
    // Video low-latency parameters (see DecoderSelector.lowLatencyOptions):
    // "low-latency", "vdec-lowlatency", vendor.low-latency.enable and the
    // operating-rate/priority ladder are forced on the first configure
    // unless this is off. Independent of the "Low latency" setting, which
    // is audio-only (Prefs.LOW_LATENCY). Off = plain decoder config, for
    // A/B testing whether the vendor parameters wedge the decoder.
    var videoLowLatency = true
    var scheduledOutputBufferRelease = true
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    // mirror jitter buffer: the receive thread only enqueues frames, a
    // dedicated decode thread feeds the decoder on presentation-time pace
    // (see FramePacer). Capacity 10 frames ~= 333ms at 30fps; when full the
    // queue purges up to the next keyframe and flags a decoder resync (see
    // FrameQueue) and the drop is counted in droppedFrames so the overlay
    // shows it.
    var jitterBufferEnabled = true
    var jitterBufferTargetMs = 100
    private val frameQueue = FrameQueue(capacity = FrameQueue.MIRROR_CAPACITY)
    private var pacer: FramePacer? = null
    private var decodeThread: Thread? = null
    @Volatile private var decodeRunning = false
    // Dedicated drain thread: draining the decoder's output used to happen
    // inside _feedDecoded, i.e. on the same thread (and under the same
    // lock) that feeds input. On a decoder whose output buffers are scarce
    // (the VAVA's Amlogic refused every nBufferCountActual the ladder
    // tried) a slow drain stalls the feed, the feed stalls the pacer, the
    // pacer stalls the queue, and the queue fills and drops — while the
    // first frame sits on screen. Draining on its own thread (and never
    // holding the renderer lock while blocked in a codec call) breaks
    // that loop.
    private var drainThread: Thread? = null
    @Volatile private var drainRunning = false
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    // Drain diagnostics: the 2026-11-16 VAVA freeze (In 50 fps, Out 0 fps,
    // queue filling, no drain-side log lines) was silent — a dead or stuck
    // drain thread leaves no trace unless the drain path itself reports.
    // These counters are frozen once per second for the debug overlay and
    // logged by the drain heartbeat (see _drainLoop), so a frozen session
    // produces a conclusive log instead of silence.
    @Volatile var drainReleased = 0L; private set
    @Volatile var drainDequeues = 0L; private set
    @Volatile var drainErrors = 0L; private set
    @Volatile var drainLastError = ""; private set
    @Volatile var drainFormatChanges = 0L; private set
    @Volatile var drainAlive = false; private set
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    private var _frameIntervalCount = 0
    @Volatile private var _lastOutputFrameNs = 0L
    // arrival (input) side: per-frame wall-clock deltas, same 120-frame window
    private val _arrivalIntervalsNs = LongArray(120)
    private var _arrivalIntervalIdx = 0
    private var _arrivalIntervalCount = 0
    @Volatile private var _lastArrivalNs = 0L
    // sender PTS side: deltas of ntpTimeNs (ns), tells source VFR apart from network jitter
    private val _ptsIntervalsNs = LongArray(120)
    private var _ptsIntervalIdx = 0
    private var _ptsIntervalCount = 0
    private var _lastPtsNs = Long.MIN_VALUE
    private var _outputsThisSec = 0
    private var _feedTotalUs = 0L
    private var _feedSamples = 0
    private var _feedMaxUs = 0L
    // Render-timestamp state for the drain path (see DrainClock): the
    // anchor maps decoder PTS (us) to System.nanoTime() for scheduled
    // rendering. Owned by the drain thread; reset whenever the codec is
    // torn down so a new session starts clean.
    private val drainClock = DrainClock()
    private val softwareOutputClock = DrainClock(maxLateNs = 40_000_000L)
    // PTS rebase for the feed path (see PtsRebaser): the sender's PTS is
    // its NTP clock (Unix epoch), but the Amlogic decoder schedules output
    // against CLOCK_MONOTONIC — feeding NTP-epoch timestamps made it hold
    // every decoded frame ~57 years (VAVA 2026-11-16: 1 output buffer in
    // 40 s, 4 decoded frames found held in the display thread at teardown).
    // Owned by the feed path; reset with the codec like drainClock.
    private val ptsRebaser = PtsRebaser()
    // PTS mode (round 5): which timestamp strategy feeds the decoder —
    // "continuous" (round 4, default: synthetic timestamps from session
    // start, strictly increasing), "rebase" (round 3: first valid PTS
    // anchors to now, pre-sync frames feed as 0), "raw" (original
    // passthrough, no rebase). Switchable for A/B comparison of which
    // change triggered the round-4 black screen; restart=true in Prefs,
    // so a change starts a fresh session. Only one variable changes at a
    // time — the resync fixes in this round do not touch the default.
    var ptsMode: String = PTS_MODE_CONTINUOUS
    // Stall recovery (VAVA 2026-11-16: codec started fine but produced no
    // output, and nothing watched for that). Any drop breaks the decoder's
    // reference chain until the next IDR, so a drop — in the queue, of a
    // stale frame, or at the decoder's input — requests a resync; the
    // watchdog fires the same request when input flows but no output
    // appears for 2 s. _doResync runs on the feed/decode thread under the
    // lock: it flushes the decoder, resets the PTS/pacing anchors and
    // drops queued frames up to the next keyframe.
    @Volatile private var resyncPending = false
    @Volatile private var _resyncReason = ""
    @Volatile var resyncCount = 0L; private set
    @Volatile var watchdogRecoveries = 0L; private set
    private val stallWatchdog = StallWatchdog()
    @Volatile private var _firstPendingFeedNs = 0L
    @Volatile private var _lastFeedNs = 0L
    @Volatile private var _codecStartNs = 0L
    @Volatile private var _fedPtsLogged = 0
    // Resync generation / epoch (round 5): every _doResync, session
    // restart and resolution change bumps [_generation]; frames are
    // stamped at enqueue and the survivors of a resync purge are
    // re-stamped, so a frame polled before the resync — which survives
    // the queue purge in the decode thread's local variable — is dropped
    // at the feed gate instead of reaching the new decoder.
    @Volatile private var _generation = 0L
    // IDR wait (round 5, reworked round 6): set when a resync destroys
    // the codec, cleared when a genuine IDR restarts it. The AirPlay
    // protocol has no receiver->sender IDR request channel (verified in
    // raop_rtp_mirror.c: the mirror TCP stream is receiver-read-only),
    // so the receiver must wait for the sender's periodic IDR — but the
    // wait is bounded (IDR_WAIT_TIMEOUT_NS) and escalates instead of
    // looping forever (see _idrWaitCheck / RecoveryEscalator). Round 6
    // P1-2: the wait is an IdrWaitTracker, not a bare timestamp, so a
    // recovery-triggered session restart re-arms a fresh deadline
    // instead of disarming the only no-IDR timeout.
    private val idrWait = IdrWaitTracker(IDR_WAIT_TIMEOUT_NS)
    // Decoder parameter sets (round 6 P1-3): the latest SPS/PPS (+VPS
    // for H.265) parsed out of the Annex-B stream, replayed as csd-0/
    // csd-1 when a (re)started decoder is created — a bare IDR after a
    // resync carries no parameter sets of its own. Updated on every
    // incoming frame; invalidated on format change or a genuinely new
    // session; a resync or a recovery restart must NOT invalidate it
    // (round 7 P1-2: the sender will not resend parameter sets just
    // because the local renderer restarted).
    private val codecConfig = CodecConfigCache()
    private val recoveryEscalator = RecoveryEscalator()
    @Volatile var sessionRestarts = 0L; private set
    @Volatile var serverRestarts = 0L; private set
    // Escalation handlers, wired by AirPlayService (posted to the main
    // handler there: a session restart joins threads and must not run on
    // the drain/decode thread). Null when unwired (unit tests, standalone
    // use) — escalation then only logs.
    var onRequestSessionRestart: (() -> Unit)? = null
    var onRequestServerRestart: (() -> Unit)? = null

    fun setResolution(w: Int, h: Int) = synchronized(lock) {
        if (w == videoWidth && h == videoHeight) return@synchronized
        videoWidth = w
        videoHeight = h
        stopCodec()
        pipeline.setVideoSize(w, h)
        // frames queued for the old size are useless; drop them and reset
        // the pacing anchor so the next session starts clean
        frameQueue.clear()
        _generation++
        // Format change invalidates the cached parameter sets (round 6
        // P1-3): the new size's IDR will bring its own.
        codecConfig.invalidate()
        pacer?.reset()
        lock.notifyAll()
    }

    // doesn't restart codec; decoder renders into pipeline's own persistent surface
    fun setSurface(surface: Surface) = synchronized(lock) {
        displaySurface = surface
        pipeline.setDisplaySurface(surface)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        pipeline.setDisplaySurface(null)
    }

    fun selectDecoders(w: Int, h: Int, fps: Int, h265: Boolean?): Boolean = synchronized(lock) {
        avcDecoder = selector.avc()
        hevcDecoder = if (h265 != false) selector.hevc(avcDecoder, w, h, fps, force = h265 == true) else null
        maxFps = fps
        Log.i(TAG, "decoders: avc=${avcDecoder?.name} hevc=${hevcDecoder?.name}")
        hevcDecoder != null
    }

    // unknown limits default to 1080p
    fun maxResolution(): Pair<Int, Int> =
        listOfNotNull(avcDecoder?.let { it to DecoderSelector.AVC }, hevcDecoder?.let { it to DecoderSelector.HEVC })
            .map { (info, mime) ->
                runCatching { info.videoCaps(mime).let { it.supportedWidths.upper to it.supportedHeights.upper } }
                    .getOrDefault(1920 to 1080)
            }
            .reduceOrNull { (w1, h1), (w2, h2) -> minOf(w1, w2) to minOf(h1, h2) } ?: (1920 to 1080)

    // codec per mirror session; pipeline persists across sessions
    fun startSession() = synchronized(lock) {
        sessionActive = true
        _resetStats()
        diagnosticQueued = 0; diagnosticGated = 0
        diagnosticLastLogNs = 0L
        frameQueue.clear()
        // New session, new epoch: anything polled before this point is
        // stale. The watchdog streak restarts, but the failed-restart
        // count survives so consecutive failed restarts still escalate.
        _generation++
        recoveryEscalator.onSessionStart()
        // Round 6 P1-2: an ordinary start clears the IDR wait; a
        // recovery-triggered restart (flagged by _escalate) re-arms a
        // fresh deadline instead, so the no-IDR timeout survives it.
        val recoveryRestart = idrWait.onSessionStart(System.nanoTime())
        // Round 7 P1-2: a recovery restart only restarts the renderer —
        // the RAOP sender connection is unchanged (AirPlayService's
        // recovery callback is stopSession/startSession while the sender
        // keeps streaming), so the sender will NOT resend parameter
        // sets just because we restarted. Preserve the cached
        // configuration: it is what the next bare IDR needs. Invalidate
        // only for a genuinely new session, whose first IDR brings its
        // own SPS/PPS. (Lingering-flag edge: if a restart was requested
        // but the start never ran, the next fresh start is treated as a
        // recovery and keeps the old config; the new sender's first IDR
        // normally carries SPS/PPS and refreshes the cache via update()
        // before any codec is created.)
        codecConfig.onSessionStart(recoveryRestart)
        pacer = FramePacer(jitterBufferTargetMs * 1_000_000L)
        Log.i(TAG, "Mirror session start: ptsMode=$ptsMode videoLowLatency=$videoLowLatency" +
            if (recoveryRestart) " (recovery: still waiting for IDR)" else "")
        // The drain thread owns all decoder output in both modes (the feed
        // path never drains any more), so it starts unconditionally.
        _startDrainThread()
        if (jitterBufferEnabled) _startDecodeThread()
        else Log.i(TAG, "Mirror jitter buffer off: synchronous feed path")
    }

    fun stopSession() {
        sessionActive = false
        // stop the decode thread first (join happens outside the lock: the
        // decode thread may be waiting on it) and only then tear the codec
        // down, so it can never feed into a stopped codec
        _stopDecodeThread()
        _stopDrainThread()
        synchronized(lock) {
            stopCodec()
            frameQueue.clear()
            _generation++
            idrWait.clear()
            pacer?.reset()
        }
    }

    private fun _startDecodeThread() {
        if (decodeThread?.isAlive == true) return
        decodeRunning = true
        Log.i(TAG, "Mirror jitter buffer on: target=${jitterBufferTargetMs}ms, capacity=${FrameQueue.MIRROR_CAPACITY} frames")
        decodeThread = Thread({ _decodeLoop() }, "MirrorDecode").also { it.start() }
    }

    private fun _stopDecodeThread() {
        val t = decodeThread ?: return
        decodeRunning = false
        synchronized(lock) { lock.notifyAll() }
        t.join(2000)
        decodeThread = null
    }

    // Dedicated drain thread (see the drainThread field comment). It owns
    // all decoder output: the feed path never drains any more, so a slow
    // drain can no longer block the feed or the pacer. When there is no
    // codec it idles on the lock; when a codec exists it blocks up to
    // 10ms inside dequeueOutputBuffer, so output is picked up promptly.
    // Hardening (VAVA 2026-11-16: In 50 fps, Out 0 fps, no drain-side log
    // lines at all): one bad drain pass must never kill this thread — see
    // _drainLoop's per-pass catch and the 5s heartbeat, which prove the
    // thread is alive and draining (or not) instead of leaving silence.
    private fun _startDrainThread() {
        if (drainThread?.isAlive == true) return
        drainRunning = true
        Log.i(TAG, "Mirror drain thread started")
        drainThread = Thread({ _drainLoop() }, "MirrorDrain").also { it.start() }
    }

    private fun _stopDrainThread() {
        val t = drainThread ?: return
        drainRunning = false
        synchronized(lock) { lock.notifyAll() }
        t.join(2000)
        drainThread = null
    }

    private fun _drainLoop() {
        drainAlive = true
        var lastHeartbeatNs = 0L
        try {
            while (drainRunning) {
                logMirrorHealth()
                try {
                    val hasCodec = synchronized(lock) { codec != null }
                    if (!hasCodec) {
                        // No codec: either idle, or waiting for a valid IDR
                        // after a resync — the wait must still be checked
                        // here, because the loop's continue below would
                        // otherwise skip _watchdogCheck entirely.
                        _idrWaitCheck(System.nanoTime())
                        synchronized(lock) {
                            try { lock.wait(20) } catch (_: InterruptedException) {}
                        }
                        continue
                    }
                    _drainOnce(firstTimeoutUs = 10_000)
                } catch (e: Exception) {
                    // One bad pass must not end the thread: the decoder only
                    // keeps producing output while someone drains it.
                    _recordDrainError(e)
                    synchronized(lock) {
                        try { lock.wait(20) } catch (_: InterruptedException) {}
                    }
                }
                val nowNs = System.nanoTime()
                if (nowNs - lastHeartbeatNs >= 5_000_000_000L) {
                    lastHeartbeatNs = nowNs
                    if (synchronized(lock) { codec != null }) {
                        Log.i(TAG, "MirrorDrain heartbeat: released=$drainReleased dequeues=$drainDequeues errors=$drainErrors fmtChanges=$drainFormatChanges reanchors=${drainClock.reanchorCount} resyncs=$resyncCount watchdog=$watchdogRecoveries restarts=$sessionRestarts/$serverRestarts lastErr=$drainLastError")
                    }
                }
                _watchdogCheck(nowNs)
            }
        } catch (t: Throwable) {
            // Last-resort proof: if this thread ever dies, logcat says so.
            drainErrors++
            drainLastError = "DIED: ${t.javaClass.simpleName}: ${t.message}".take(120)
            Log.e(TAG, "MirrorDrain thread DIED", t)
        } finally {
            drainAlive = false
            Log.i(TAG, "Mirror drain thread stopped")
        }
    }

    private fun _recordDrainError(e: Throwable) {
        drainErrors++
        val msg = "${e.javaClass.simpleName}: ${e.message}".take(120)
        drainLastError = msg
        // Full stack trace for the first few, then one line each — a
        // persistent drain fault must stay visible without flooding logcat.
        if (drainErrors <= 3 || drainErrors % 50 == 0L) {
            Log.w(TAG, "drain error #$drainErrors (kept draining): $msg", e)
        } else {
            Log.w(TAG, "drain error #$drainErrors (kept draining): $msg")
        }
    }

    // Stall recovery: see the resyncPending field comment. _requestResync
    // only flags it (safe from any thread); _doResync does the work on the
    // feed/decode thread under the renderer lock.
    private fun _requestResync(reason: String) {
        if (!resyncPending) Log.w(TAG, "Resync requested: $reason")
        _resyncReason = reason
        resyncPending = true
    }

    // Call with the renderer lock held. Flushes the decoder, resets the
    // PTS/pacing anchors and drops queued frames up to the next keyframe;
    // the keyframe gate in _feedDecoded restarts the codec on that
    // keyframe (or waits for the sender's next IDR when the queue holds
    // none). The wait is bounded: idrWait records when it began, and
    // _idrWaitCheck escalates if no valid IDR arrives (see there)
    // instead of looping forever. A re-entry while already waiting is
    // the normal case — the queue keeps overflowing while the codec is
    // down — so it only clears the flags and returns instead of
    // resetting the wait clock and inflating the count.
    private fun _doResync() {
        if (!resyncPending && !frameQueue.resyncNeeded) return
        val reason = _resyncReason.ifEmpty { "queue drop" }
        resyncPending = false
        _resyncReason = ""
        frameQueue.clearResyncFlag()
        if (idrWait.isWaiting()) return
        stopCodec()
        pacer?.reset()
        // Invalidate everything fed before this point, including the frame
        // the decode thread already polled and is holding through its
        // pacer wait; the frames deliberately kept (the next IDR and what
        // follows it) are re-stamped to the new generation.
        _generation++
        val dropped = frameQueue.dropToNextKeyframe()
        frameQueue.restampGeneration(_generation)
        resyncCount++
        idrWait.arm(System.nanoTime())
        Log.w(TAG, "Mirror resync: $reason; decoder flushed, dropped $dropped queued frame(s) to next keyframe (resyncs=$resyncCount)")
        lock.notifyAll()
    }

    // Runs on the drain thread: a resync destroyed the codec and we are
    // waiting for a valid IDR. The AirPlay protocol has no receiver->
    // sender IDR request channel (verified in raop_rtp_mirror.c: the
    // mirror TCP stream is receiver-read-only, conn_feedback is a no-op),
    // so the receiver must wait for the sender's periodic IDR — but only
    // up to IDR_WAIT_TIMEOUT_NS. Past that, escalate: first a full
    // video-session restart, then a server restart (which disconnects the
    // sender, so the next mirror stream necessarily starts with a fresh
    // IDR). No input arriving means the sender paused/stopped, not that
    // the IDR is missing — waiting is normal then, and no escalation
    // fires. Runs on every drain pass while codec == null (including the
    // no-codec branch of _drainLoop, which would otherwise skip it).
    private fun _idrWaitCheck(nowNs: Long) {
        if (!idrWait.isWaiting()) return
        if (synchronized(lock) { codec } != null) {
            idrWait.clear()
            return
        }
        if (nowNs - _lastArrivalNs > IDR_WAIT_INPUT_WINDOW_NS) return
        // Snapshot the start before the due check: another thread may
        // clear the wait right after isDue() (e.g. an IDR restarting
        // the codec), and the log below must still report the real wait.
        val startedNs = idrWait.waitStartedNs
        if (!idrWait.isDue(nowNs)) return
        val waitedMs = (nowNs - startedNs) / 1_000_000
        idrWait.clear()
        val level = recoveryEscalator.noteIdrTimeout()
        Log.w(TAG, "Mirror recovery: no valid IDR for ${waitedMs}ms after resync; escalating to $level")
        _escalate(level, "no valid IDR after resync")
    }

    private fun _escalate(level: RecoveryEscalator.Level, reason: String) {
        when (level) {
            RecoveryEscalator.Level.SESSION_RESTART -> {
                sessionRestarts++
                Log.w(TAG, "Mirror recovery: $reason — restarting video session (restart #$sessionRestarts)")
                val handler = onRequestSessionRestart
                if (handler != null) {
                    // Round 6 P1-2: flag the restart as recovery-triggered
                    // so startSession() re-arms the IDR-wait deadline
                    // instead of clearing it (the flag is consumed there).
                    idrWait.markRecoveryRestartPending()
                    handler.invoke()
                } else {
                    Log.w(TAG, "Mirror recovery: no session-restart handler wired; staying in resync loop")
                }
            }
            RecoveryEscalator.Level.SERVER_RESTART -> {
                serverRestarts++
                Log.w(TAG, "Mirror recovery: $reason — restarting server (restart #$serverRestarts); sender must re-select mirroring")
                onRequestServerRestart?.invoke()
                    ?: Log.w(TAG, "Mirror recovery: no server-restart handler wired; staying in resync loop")
            }
        }
    }

    // Runs on the drain thread every pass: input flowing but no output for
    // 2 s means the decoder is wedged (see StallWatchdog). The recovery
    // itself runs on the feed thread via _requestResync. A resync that
    // never produces output is a loop, not a recovery: after several
    // watchdog resyncs with still no output, escalate instead of
    // destroying the codec again.
    private fun _watchdogCheck(nowNs: Long) {
        val startNs = _codecStartNs
        if (startNs == 0L) return
        if (stallWatchdog.shouldRecover(nowNs, startNs, _lastFeedNs, _lastOutputFrameNs, _firstPendingFeedNs)) {
            watchdogRecoveries++
            Log.w(TAG, "Watchdog: input flowing but no output for 2s; requesting resync (recovery #$watchdogRecoveries)")
            _requestResync("watchdog: no output for 2s despite input")
            val level = recoveryEscalator.noteWatchdogResync()
            if (level != null) {
                _escalate(level, "no output after ${RecoveryEscalator.DEFAULT_WATCHDOG_STREAK_LIMIT} watchdog resyncs")
            }
        }
    }

    // Decode thread: take frames from the queue and feed them on PTS pace.
    // Poll-then-wait keeps ownership simple: once polled, a frame is ours
    // even if the queue is cleared (session end / resolution change).
    private fun _decodeLoop() {
        while (decodeRunning) {
            var current: QueuedFrame? = synchronized(lock) {
                if (resyncPending || frameQueue.resyncNeeded) _doResync()
                while (decodeRunning && frameQueue.isEmpty) {
                    try { lock.wait() } catch (_: InterruptedException) {}
                }
                if (!decodeRunning) null else frameQueue.poll()
            } ?: break
            // Drop stale frames while a fresher one waits behind them; if
            // this is the only frame, feed it anyway (nothing better to show).
            // A dropped frame breaks the decoder's reference chain, so a
            // stale drop requests the same resync as a queue overflow.
            var droppedStale = false
            while (current != null) {
                val p = pacer ?: break
                // FramePacer works in microseconds (presentationTimeUs);
                // ntpTimeNs is nanoseconds from the JNI layer, so convert
                // before pacing. Passing ns straight in inflated every PTS
                // delta 1000x: the pacer re-anchored on every frame and the
                // buffer degenerated into a fixed 100ms delay line that
                // passed bursts through untouched.
                val stale = p.isStale(current.ntpTimeNs / 1000, System.nanoTime())
                val hasFresher = synchronized(lock) { frameQueue.isNotEmpty }
                if (!stale || !hasFresher) break
                droppedFrames++
                droppedStale = true
                Log.w(TAG, "Jitter buffer: dropping stale frame. drops=$droppedFrames")
                current = synchronized(lock) { frameQueue.poll() }
            }
            if (droppedStale) _requestResync("dropped stale frame(s) in decode loop")
            val toFeed = current ?: continue
            // Wait until this frame's target feed time. Recomputed after
            // every wake so a stop (notifyAll) cuts the wait short.
            val p = pacer
            if (p != null) {
                val ptsUs = toFeed.ntpTimeNs / 1000
                var remaining = p.waitNs(ptsUs, toFeed.arrivalNs, System.nanoTime())
                while (decodeRunning && remaining > 0) {
                    synchronized(lock) {
                        try {
                            lock.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
                        } catch (_: InterruptedException) {}
                    }
                    remaining = p.waitNs(ptsUs, toFeed.arrivalNs, System.nanoTime())
                }
                if (!decodeRunning) break
            }
            // Feed, timed. _feedDecoded takes the lock only for the
            // keyframe gate / codec (re)start and releases it before the
            // blocking queueInputBuffer calls, so a slow feed can no longer
            // hold the lock and starve enqueueing and draining.
            val t0 = System.nanoTime()
            try {
                _feedDecoded(toFeed.data, toFeed.ntpTimeNs, toFeed.isH265, toFeed.generation)
            } finally {
                _accumulateFeedTime((System.nanoTime() - t0) / 1000)
            }
        }
    }

    private fun _resetStats() {
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        outputFps = 0; arrivalJitterUs = 0; ptsJitterUs = 0
        feedMeanMs = 0f; feedMaxMs = 0f
        decodedWidth = 0; decodedHeight = 0
        outputIntervalsMs = emptyList(); arrivalIntervalsMs = emptyList()
        drainReleased = 0; drainDequeues = 0; drainErrors = 0
        drainLastError = ""; drainFormatChanges = 0
        resyncCount = 0; watchdogRecoveries = 0
        _framesThisSec = 0; _bytesThisSec = 0; _outputsThisSec = 0
        _feedTotalUs = 0; _feedSamples = 0; _feedMaxUs = 0
        _arrivalIntervalIdx = 0; _arrivalIntervalCount = 0; _lastArrivalNs = 0L
        _ptsIntervalIdx = 0; _ptsIntervalCount = 0; _lastPtsNs = Long.MIN_VALUE
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            outputFps = _outputsThisSec
            arrivalJitterUs = _computeStdDevUs(_arrivalIntervalsNs, _arrivalIntervalCount)
            ptsJitterUs = _computeStdDevUs(_ptsIntervalsNs, _ptsIntervalCount)
            feedMeanMs = if (_feedSamples > 0) _feedTotalUs / 1000f / _feedSamples else 0f
            feedMaxMs = _feedMaxUs / 1000f
            // freeze the interval curves once per second: the overlay is history,
            // it does not need per-frame recomposition
            outputIntervalsMs = _snapshotIntervalsMs(_frameIntervalsNs, _frameIntervalIdx, _frameIntervalCount)
            arrivalIntervalsMs = _snapshotIntervalsMs(_arrivalIntervalsNs, _arrivalIntervalIdx, _arrivalIntervalCount)
            _framesThisSec = 0
            _bytesThisSec = 0
            _outputsThisSec = 0
            _feedTotalUs = 0; _feedSamples = 0; _feedMaxUs = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    // record one arrival: wall-clock delta (network/receiver side) and sender PTS delta
    private fun _recordArrival(ntpTimeNs: Long) {
        val now = System.nanoTime()
        if (_lastArrivalNs > 0) {
            _arrivalIntervalsNs[_arrivalIntervalIdx % _arrivalIntervalsNs.size] = now - _lastArrivalNs
            _arrivalIntervalIdx++
            _arrivalIntervalCount++
        }
        _lastArrivalNs = now
        if (_lastPtsNs != Long.MIN_VALUE && ntpTimeNs > _lastPtsNs) {
            _ptsIntervalsNs[_ptsIntervalIdx % _ptsIntervalsNs.size] = ntpTimeNs - _lastPtsNs
            _ptsIntervalIdx++
            _ptsIntervalCount++
        }
        _lastPtsNs = ntpTimeNs
    }

    private fun _accumulateFeedTime(dtUs: Long) {
        _feedTotalUs += dtUs
        _feedSamples++
        if (dtUs > _feedMaxUs) _feedMaxUs = dtUs
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    // Called on the receive thread. With the jitter buffer on, this only
    // enqueues (arrival stats are sampled here); the decode thread feeds
    // the decoder. With it off, the old synchronous path runs instead.
    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        if (!jitterBufferEnabled) {
            _feedFrameSync(data, ntpTimeNs, isH265)
            return
        }
        synchronized(lock) {
            // Cache the parameter sets before anything can drop this
            // frame (round 6 P1-3): even a queue-overflow drop leaves
            // its SPS/PPS behind for the next decoder.
            codecConfig.update(data, isH265)
            _recordArrival(ntpTimeNs)
            _updateStats(data.size)
            if (videoWidth == 0 || videoHeight == 0) return
            val dropped = frameQueue.offer(
                QueuedFrame(data, ntpTimeNs, isH265, System.nanoTime(), NalUtils.isKeyframe(data, isH265), _generation)
            )
            if (dropped > 0) {
                droppedFrames += dropped
                Log.w(TAG, "Jitter buffer full; dropped $dropped frame(s). drops=$droppedFrames")
                _requestResync("jitter buffer full, dropped $dropped frame(s)")
            }
            lock.notifyAll()
        }
    }

    private fun _feedFrameSync(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        synchronized(lock) {
            // See feedFrame: cache the parameter sets first (round 6 P1-3).
            codecConfig.update(data, isH265)
            _recordArrival(ntpTimeNs)
            _updateStats(data.size)
            if (videoWidth == 0 || videoHeight == 0) return
        }
        // Feed outside the lock: _feedDecoded takes it only for the
        // keyframe gate / codec (re)start, so a blocking queueInputBuffer
        // can no longer hold the lock while the drain thread needs it.
        val t0 = System.nanoTime()
        try {
            _feedDecoded(data, ntpTimeNs, isH265, _generation)
        } finally {
            _accumulateFeedTime((System.nanoTime() - t0) / 1000)
        }
    }

    // Shared by the sync path and the decode thread. Owns codec (re)start,
    // keyframe gating and feed. Must NOT be called with the lock held: it
    // takes the lock only for the keyframe gate / codec (re)start and
    // releases it before the blocking feed, so a slow feed can no longer
    // hold the lock and starve enqueueing and draining. Never drains here
    // — the MirrorDrain thread owns all decoder output.
    private fun _feedDecoded(data: ByteArray, ntpTimeNs: Long, isH265: Boolean, generation: Long) {
        // 1. Under the lock: generation check, keyframe gate and codec
        // (re)start. Capture the codec reference so the blocking feed
        // below can run without the lock (a concurrent stopCodec then
        // surfaces as IllegalStateException, which the feed's catch
        // handles).
        val c = synchronized(lock) {
            if (resyncPending || frameQueue.resyncNeeded) _doResync()
            // Pre-resync frames must never reach a post-resync decoder:
            // a frame polled before a resync survives the queue purge in
            // the decode thread's local variable (it was already out of
            // the queue, waiting on the pacer). Drop it here, under the
            // lock, before it can (re)start the codec.
            if (generation != _generation) {
                droppedFrames++
                Log.w(TAG, "Dropping stale-generation frame (gen $generation != $_generation). drops=$droppedFrames")
                return@synchronized null
            }
            if (codec == null || isH265 != currentH265) {
                // a stale reference frame decodes to corruption, so wait for a keyframe to (re)start
                if (!NalUtils.isKeyframe(data, isH265)) {
                    diagnosticGated++
                    if (codec != null) stopCodec()
                    return@synchronized null
                }
                stopCodec()
            }
            try {
                if (codec == null) {
                    startCodec(isH265)
                    // A genuine IDR restarted the codec: the IDR wait is
                    // over. Round 6 P1-1: admission alone does NOT break
                    // the failure chain (onValidIdr no longer clears the
                    // count) — only sustained output does.
                    idrWait.clear()
                    recoveryEscalator.onValidIdr()
                }
                codec
            } catch (e: Exception) {
                Log.w(TAG, "Codec error, resetting", e)
                stopCodec()
                null
            }
        } ?: return
        // 2. Outside the lock: feed (may block in dequeueInputBuffer).
        try {
            _feedToCodec(c, data, ntpTimeNs, generation)
        } catch (e: Exception) {
            Log.w(TAG, "Codec error, resetting", e)
            synchronized(lock) { if (codec === c) stopCodec() }
        }
    }

    // Keep the compressed frame during temporary backpressure. Native
    // waits never hold the renderer lock; session/codec changes cancel.
    private fun _feedToCodec(c: MediaCodec, data: ByteArray, ntpTimeNs: Long, generation: Long) {
        val beganNs = System.nanoTime()
        val wait = CodecInputWait(beganNs, !firstFrameQueued)
        while (true) {
            val current = synchronized(lock) { codec === c && generation == _generation && !resyncPending }
            val timeoutUs = wait.nextTimeoutUs(System.nanoTime(), sessionActive, current)
            if (!sessionActive || !current) return
            if (timeoutUs == 0L) break
            val idx = c.dequeueInputBuffer(timeoutUs)
            if (!sessionActive || synchronized(lock) { codec !== c || generation != _generation || resyncPending }) return
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data)
                // Rebase the sender's NTP PTS (Unix epoch) into the
                // monotonic domain before queueing: the Amlogic decoder's
                // display thread schedules output against CLOCK_MONOTONIC,
                // so a raw NTP timestamp (~1.79e9 s) is ~57 years in its
                // future and the frame is held forever — on the VAVA the
                // decoder returned exactly 1 output buffer in 40 s while
                // consuming ~700 input frames (see PtsRebaser). The pacer
                // and DrainClock only use PTS deltas, so they are
                // unaffected. Which rebase runs is switchable (round 5,
                // ptsMode) so the round-4 black screen can be attributed
                // to one change at a time: "continuous" (default, round
                // 4: synthetic timestamps from session start, strictly
                // increasing), "rebase" (round 3: first valid PTS anchors
                // to now, pre-sync frames feed as 0), "raw" (original
                // passthrough, no rebase at all).
                val beforeReanchors = ptsRebaser.reanchorCount
                val beforeJumps = ptsRebaser.jumpCount
                val ptsUs = when (ptsMode) {
                    PTS_MODE_RAW -> ntpTimeNs / 1000
                    PTS_MODE_REBASE -> ptsRebaser.rebasePtsUsLegacy(ntpTimeNs / 1000, System.nanoTime() / 1000)
                    else -> ptsRebaser.rebasePtsUs(ntpTimeNs / 1000, System.nanoTime() / 1000)
                }
                if (ptsMode != PTS_MODE_RAW) {
                    if (ptsRebaser.reanchorCount > beforeReanchors) {
                        Log.w(TAG, "PTS rebase re-anchored (sender ptsUs=${ntpTimeNs / 1000})")
                    }
                    if (ptsRebaser.jumpCount > beforeJumps) {
                        Log.w(TAG, "PTS jump fed: deltaUs=${ptsRebaser.lastJumpUs} rawPtsUs=${ntpTimeNs / 1000} fedPtsUs=$ptsUs jumps=${ptsRebaser.jumpCount}")
                    }
                }
                // Log the first few fed PTS values each session: the live
                // comparison for the 0 -> uptime jump (round 4), and now
                // also the per-mode fed timeline (round 5).
                if (_fedPtsLogged < FED_PTS_LOG_COUNT) {
                    _fedPtsLogged++
                    Log.i(TAG, "fed ptsUs=$ptsUs rawPtsUs=${ntpTimeNs / 1000} mode=$ptsMode (frame #$_fedPtsLogged)")
                }
                val queuedAtNs = System.nanoTime()
                synchronized(lock) {
                    if (!sessionActive || codec !== c || generation != _generation) return
                    if (_firstPendingFeedNs == 0L) _firstPendingFeedNs = queuedAtNs
                }
                c.queueInputBuffer(idx, 0, data.size, ptsUs, 0)
                val waitedMs = (System.nanoTime() - beganNs) / 1_000_000L
                if (waitedMs >= 60L) Log.i(TAG, "Mirror input backpressure recovered after ${waitedMs}ms without dropping frame")
                diagnosticQueued++
                diagnosticLastRawUs = ntpTimeNs / 1000
                diagnosticLastFedUs = ptsUs
                synchronized(lock) { if (codec === c && generation == _generation) _lastFeedNs = queuedAtNs }
                firstFrameQueued = true
                return
            }
        }
        droppedFrames++
        Log.w(TAG, "Decoder input timed out after ${(System.nanoTime() - beganNs) / 1_000_000L}ms; dropping frame. drops=$droppedFrames")
        _requestResync("decoder input queue full")
    }

    private fun startCodec(h265: Boolean) {
        pipeline.start()
        pipeline.setVideoSize(videoWidth, videoHeight)
        val s = pipeline.inputSurface ?: return
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("no decoder selected for $mime")

        firstFrameQueued = false
        try {
            _startWithLadder(info, mime, s, h265)
        } catch (e: Exception) {
            // strict hw decoders reject configs beyond their real limits
            val sw = selector.software(mime, videoWidth, videoHeight) ?: throw e
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            _startWithLadder(sw, mime, s, h265)
        }
        Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight} ($codecName)")
    }

    private fun _startWithLadder(info: MediaCodecInfo, mime: String, s: Surface, h265: Boolean) {
        var tryNum = 0
        while (true) {
            val format = _format(mime, info)
            // Round 6 P1-3: replay the cached decoder parameter sets
            // (SPS/PPS, +VPS for H.265) so a decoder (re)started on a
            // bare IDR still gets its configuration. A resync or a
            // recovery restart must not have invalidated the cache
            // (round 7 P1-2); a format change or a genuinely new
            // session does.
            codecConfig.csdFor(h265)?.let { (csd0, csd1) ->
                format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                if (csd1 != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
                if (tryNum == 0) Log.i(TAG, "Video codec config: replaying cached csd-0=${csd0.size}B" +
                    (if (csd1 != null) " csd-1=${csd1.size}B" else ""))
            }
            // videoLowLatency off = plain decoder config (A/B test): none
            // of the vendor low-latency parameters are applied at all.
            val more = if (videoLowLatency) selector.lowLatencyOptions(format, info, mime, tryNum) else false
            if (tryNum == 0) Log.i(TAG, "startCodec: videoLowLatency=$videoLowLatency codec=${info.name}")
            try {
                _startDecoder(MediaCodec.createByCodecName(info.name), format, s, h265)
                return
            } catch (e: Exception) {
                if (!more) throw e
                Log.w(TAG, "configure try $tryNum failed: $format", e)
                tryNum++
            }
        }
    }

    private fun _format(mime: String, info: MediaCodecInfo) = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight).apply {
        setInteger(MediaFormat.KEY_FRAME_RATE, maxFps)
        if (selector.adaptive(info, mime)) {
            setInteger(MediaFormat.KEY_MAX_WIDTH, videoWidth)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, videoHeight)
        }
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(videoWidth * videoHeight * 3 / 4, 1024 * 1024))
        if (enforceSdr) {
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
        }
    }

    private fun _startDecoder(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean) {
        try {
            c.configure(format, surface, null, 0)
            c.start()
            _codecStartNs = System.nanoTime()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
    }

    private fun stopCodec() {
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        _arrivalIntervalIdx = 0
        _arrivalIntervalCount = 0
        _lastArrivalNs = 0L
        _ptsIntervalIdx = 0
        _ptsIntervalCount = 0
        _lastPtsNs = Long.MIN_VALUE
        drainClock.reset()
        softwareOutputClock.reset()
        ptsRebaser.reset()
        stallWatchdog.reset()
        _codecStartNs = 0L
        _firstPendingFeedNs = 0L
        _lastFeedNs = 0L
        _fedPtsLogged = 0
        codec?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        codec = null
    }

    // Drains whatever output the codec has ready and returns how many
    // buffers were released. Sole owner: the MirrorDrain thread. The first
    // dequeue may block up to firstTimeoutUs so the thread wakes promptly
    // when output appears; later dequeues in the same pass are
    // non-blocking. A codec stopped concurrently (stopCodec from a session
    // teardown or a resolution change) surfaces as IllegalStateException
    // and is treated as "no codec" — the drain loop re-reads the codec
    // and idles. Anything else is also caught and counted (see below):
    // one bad frame must never kill the drain thread.
    private fun _drainOnce(firstTimeoutUs: Long): Int {
        val c = synchronized(lock) { codec } ?: return 0
        val info = MediaCodec.BufferInfo()
        var n = 0
        var timeoutUs = firstTimeoutUs
        var formatChanges = 0
        try {
            while (true) {
                val idx = c.dequeueOutputBuffer(info, timeoutUs)
                timeoutUs = 0
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    formatChanges++
                    drainFormatChanges++
                    // capture the real decoded size: the sender may ignore the advertised one
                    runCatching {
                        val fmt = c.outputFormat
                        decodedWidth = fmt.getInteger(MediaFormat.KEY_WIDTH)
                        decodedHeight = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    // A decoder that re-reports a format change on every
                    // dequeue would spin this loop forever without ever
                    // releasing a frame; bound it and let the next pass
                    // re-check. The counter in the heartbeat shows it.
                    if (formatChanges > 4) {
                        Log.w(TAG, "drain: $formatChanges consecutive format changes, yielding")
                        break
                    }
                    continue
                }
                if (idx < 0) break
                if (drainDequeues < 5) Log.i(TAG, "MirrorOutput index=$idx ptsUs=${info.presentationTimeUs} flags=${info.flags} size=${info.size}")
                drainDequeues++
                // The codec may have been stopped/replaced since we
                // captured it: releasing to a stopped codec throws
                // IllegalArgumentException (native -EINVAL, not an
                // IllegalStateException), so verify first and skip the
                // release — the teardown reclaims the buffer.
                if (synchronized(lock) { codec } !== c) {
                    drainErrors++
                    drainLastError = "stale codec on release"
                    Log.w(TAG, "drain: codec replaced under us, skipping release of buffer $idx")
                    break
                }
                if (!_releaseOutput(c, idx, info.presentationTimeUs)) break
                _recordOutputFrameTime()
                drainReleased++
                n++
            }
        } catch (e: IllegalStateException) {
            // codec was stopped concurrently; the drain loop re-reads it
            Log.w(TAG, "drain interrupted by codec stop", e)
        } catch (e: Exception) {
            // Anything else — notably IllegalArgumentException from a
            // release to a non-executing codec (native -EINVAL) or a vendor
            // quirk in dequeue/release: count it and keep draining. The
            // old code caught only IllegalStateException here, so one such
            // throw ended the thread (and, with no custom uncaught handler
            // in the app, the whole process).
            _recordDrainError(e)
        }
        return n
    }

    // Releases one decoded frame to the display surface, scheduled at its
    // PTS so bursts are spread out. The timestamp math lives in DrainClock
    // (pure, unit-tested): PTS <= 0 releases immediately with no timestamp
    // and never touches the anchor (a 0-PTS frame is "no timestamp", not a
    // point ~10^18 ns in the past — the native layer emits 0-PTS frames
    // before NTP sync completes), and any computed timestamp is clamped to
    // [now, now + 100ms]. The old code only clamped the far-future side,
    // so a PTS regression handed releaseOutputBuffer a hugely negative
    // timestamp — outside the ~1s window the platform guarantees — which
    // coincided on the VAVA with the decoder producing zero output after
    // frame 1.
    private fun _releaseOutput(c: MediaCodec, idx: Int, ptsUs: Long): Boolean {
        if (!scheduledOutputBufferRelease) {
            val targetNs = synchronized(lock) {
                softwareOutputClock.releaseTimestampNs(ptsUs, System.nanoTime())
            }
            while (targetNs != null) {
                if (!sessionActive || synchronized(lock) { codec !== c }) return false
                val remaining = targetNs - System.nanoTime()
                if (remaining <= 0L) break
                LockSupport.parkNanos(remaining.coerceAtMost(2_000_000L))
            }
            if (!sessionActive || synchronized(lock) { codec !== c }) return false
            pipeline.beforeFrameRelease(ptsUs)
            c.releaseOutputBuffer(idx, true)
            return true
        }
        val nowNs = System.nanoTime()
        val beforeReanchors = drainClock.reanchorCount
        val targetNs = synchronized(lock) { drainClock.releaseTimestampNs(ptsUs, nowNs) }
        if (drainClock.reanchorCount > beforeReanchors) {
            Log.w(TAG, "Output timestamp far in future; re-anchoring drain clock (ptsUs=$ptsUs)")
        }
        pipeline.beforeFrameRelease(ptsUs)
        if (targetNs == null) {
            // No usable PTS: render now, don't touch the anchor.
            c.releaseOutputBuffer(idx, true)
        } else {
            c.releaseOutputBuffer(idx, targetNs)
        }
        return true
    }

    fun release() {
        sessionActive = false
        _stopDecodeThread()
        _stopDrainThread()
        synchronized(lock) {
            stopCodec()
            frameQueue.clear()
            _generation++
            idrWait.clear()
            recoveryEscalator.reset()
            pacer?.reset()
            pipeline.release()
            _resetStats()
        }
    }

    private fun _recordOutputFrameTime() {
        synchronized(lock) { _firstPendingFeedNs = 0L }
        _outputsThisSec++
        // Any decoded output proves the current recovery worked: reset
        // the escalation chain (see RecoveryEscalator).
        recoveryEscalator.onOutput()
        val now = System.nanoTime()
        if (_lastOutputFrameNs > 0) {
            _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
            _frameIntervalIdx++
            _frameIntervalCount++
        }
        _lastOutputFrameNs = now
    }

    private fun _computeFramePacingJitterUs(): Long =
        _computeStdDevUs(_frameIntervalsNs, _frameIntervalCount)

    // population stddev of the last N intervals in a ring buffer, in us
    private fun _computeStdDevUs(buf: LongArray, count: Int): Long {
        val n = count.coerceAtMost(buf.size)
        if (n < 2) return 0
        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until n) {
            val v = buf[i].toDouble()
            sum += v
            sumSq += v * v
        }
        val mean = sum / n
        val variance = (sumSq / n) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    // ordered oldest-first snapshot of a ring buffer, in ms
    private fun _snapshotIntervalsMs(buf: LongArray, idx: Int, count: Int): List<Float> {
        val n = count.coerceAtMost(buf.size)
        if (n == 0) return emptyList()
        val size = buf.size
        val start = ((idx - n) % size + size) % size
        return List(n) { i -> buf[(start + i) % size] / 1_000_000f }
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"
        // PTS modes (round 5): how sender timestamps are fed to the
        // decoder. "continuous" is the round-4 behaviour and the default.
        const val PTS_MODE_CONTINUOUS = "continuous"
        const val PTS_MODE_REBASE = "rebase"
        const val PTS_MODE_RAW = "raw"
        // IDR wait (round 5): after a resync destroys the codec, wait at
        // most this long for a valid IDR before escalating. 3 s clears the
        // sender's ~2 s periodic IDR cadence with margin; a longer sender
        // period would only cause a harmless extra session restart.
        private const val IDR_WAIT_TIMEOUT_NS = 3_000_000_000L
        // Input must have arrived within this window for an IDR wait to
        // count as stuck — otherwise the sender paused/stopped.
        private const val IDR_WAIT_INPUT_WINDOW_NS = 2_000_000_000L
        // Log the first N fed PTS values each session (round-4 live check).
        private const val FED_PTS_LOG_COUNT = 5
    }
}
