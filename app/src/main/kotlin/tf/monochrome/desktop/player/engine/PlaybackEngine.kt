package tf.monochrome.desktop.player.engine

import android.util.Log
import androidx.media3.common.C
import androidx.core.net.toFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.datasource.DataSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.locks.LockSupport
import javax.swing.SwingUtilities
import tf.monochrome.desktop.audio.atmos.AtmosFrameBuffer
import tf.monochrome.desktop.audio.atmos.AtmosTapQueue
import tf.monochrome.desktop.audio.sink.AudioSink
import tf.monochrome.desktop.audio.sink.JavaSoundSink
import tf.monochrome.desktop.audio.sink.SinkException
import tf.monochrome.desktop.audio.usb.AudioProcessorChain
import tf.monochrome.desktop.audio.usb.SpeedTimeline

/**
 * The desktop player: what ExoPlayer plus `PlaybackService` were on Android,
 * as one in-process object.
 *
 * Threads:
 *  - one **decode thread per open stream** ([Source]): opens the decoder,
 *    seeks to the start position and keeps a PCM ring topped up;
 *  - one **render thread** for the engine's life: executes the commands the
 *    [Player] API enqueues, pulls from the current ring, runs the processor
 *    chain (the same [AudioProcessorChain] the Android USB path drove), packs to
 *    the sink's format with the volume applied and writes with back-pressure.
 *    Only this thread touches the sink, the chain and the speed timeline;
 *    other threads read what it publishes ([positionUs]);
 *  - listener callbacks are posted to [callbackExecutor] (the Swing event
 *    thread by default), as Media3 posted them to the application looper.
 *
 * Gapless: [preloadNext] opens the next track on its own decode thread; when
 * the current ring runs dry and the formats match, the render thread simply
 * continues from the next ring without draining the chain, so the join is
 * seamless and the DSP tails carry across. A format change drains, reconfigures
 * and continues. The media-item transition is reported when the device clock
 * reaches the splice, so the UI changes when the listener hears the change.
 *
 * Crossfade: [crossfadeTo] arms a blend into the next track. Its decode
 * starts at once, and when the current track reaches one blend length of
 * heard time before its end the render thread sums both tracks, frame by
 * frame along [tf.monochrome.desktop.player.CrossfadeRamp]'s equal-power
 * curves, and feeds the sum to the chain ([CrossfadeMix]): one processor
 * chain, one DSP state, one output. As on Android the next track becomes the
 * current one where the blend begins (there the main player moved on at the
 * hand-off and only the outgoing tail played elsewhere), reported when that
 * point is heard. A blend needs both tracks at the same rate, channel count
 * and sample encoding; otherwise, or when the next track is not ready in time,
 * the tracks join the gapless or reconfiguring way above.
 *
 * Position = the source's position where the item began playing, plus the
 * device clock since then, mapped through [SpeedTimeline] when the speed is
 * not 1.
 */
class PlaybackEngine(
    private val processors: List<AudioProcessor>,
    private var sinkFactory: () -> AudioSink,
    private val callbackExecutor: Executor = Executor { SwingUtilities.invokeLater(it) },
    /**
     * Where an E-AC-3 stream's raw access units go for the Atmos renderer
     * ([tf.monochrome.desktop.audio.atmos.AtmosAudioProcessor]); null leaves
     * them untapped.
     */
    private val atmosFrames: AtmosFrameBuffer? = null,
) : Player {

    /**
     * Turns a MediaItem into a decoder. [floatOutput] asks for float PCM even
     * from a 16-bit codec: a crossfade sums two tracks, and they must reach the
     * chain in the same encoding.
     */
    fun interface SourceOpener {
        fun open(item: MediaItem, floatOutput: Boolean): FfmpegDecoder
    }

    /** Default opener: file and http(s) URIs straight into libavformat. */
    var sourceOpener: SourceOpener = SourceOpener { item, floatOutput ->
        val uri = item.localConfiguration?.uri ?: throw IllegalArgumentException("MediaItem without a uri: $item")
        // toFile, not uri.path: on Windows the path of file:///C:/x is "/C:/x".
        val target = if (uri.scheme == "file") uri.toFile().absolutePath else uri.toString()
        FfmpegDecoder.open(target, userAgent = userAgent, floatOutput = floatOutput)
    }

    /** Opener for items whose scheme needs one of the app's DataSources (qobuz://, deezer://, ...). */
    fun dataSourceOpener(factory: DataSource.Factory): SourceOpener = SourceOpener { item, floatOutput ->
        val uri = item.localConfiguration?.uri ?: throw IllegalArgumentException("MediaItem without a uri: $item")
        FfmpegDecoder.open(factory.createDataSource(), uri, floatOutput)
    }

    /**
     * Sent on every direct network open. The Android app's radio agent, kept
     * as it was: a fair number of Icecast servers answer a generic agent with
     * a 403, and this one is known to get through.
     */
    var userAgent: String = RADIO_USER_AGENT

    /**
     * The listener's blend length, heard milliseconds; 0 = tracks join
     * gaplessly. While it is set, streams open as float, 16-bit ones included
     * (widening is exact), so any two tracks at the same rate and channel
     * count can be blended without reconfiguring the chain mid-join. With it
     * at 0 a 16-bit source stays 16-bit end to end.
     */
    @Volatile var crossfadeMs: Long = 0L

    /**
     * Where [seekToNext] and [seekToPrevious] go. The engine holds one track
     * (and at most the next), never the queue, so a skip is the queue owner's
     * to make: Android wrapped its player in a QueueForwardingPlayer for the
     * same reason.
     */
    interface QueueNavigator {
        fun next()
        fun previous()
    }

    @Volatile var queueNavigator: QueueNavigator? = null

    // ── Observable state (volatile, read from any thread) ────────────────────
    @Volatile private var _playbackState = Player.STATE_IDLE
    @Volatile private var _playWhenReady = false
    @Volatile private var _isPlaying = false
    @Volatile private var _currentItem: MediaItem? = null
    @Volatile private var _duration = C.TIME_UNSET
    @Volatile private var _volume = 1f
    @Volatile private var _playbackParameters = PlaybackParameters.DEFAULT
    @Volatile private var _error: PlaybackException? = null
    @Volatile private var publishedPositionUs = 0L
    @Volatile override var repeatMode: Int = Player.REPEAT_MODE_OFF
    @Volatile override var shuffleModeEnabled: Boolean = false

    /** The chain's current input format (for sampleRatesMatch / gapless eligibility). */
    @Volatile var audioFormat: AudioFormat? = null
        private set
    /** What the sink actually takes. */
    @Volatile var sinkFormat: AudioFormat? = null
        private set
    /** The decoder's description of the playing stream. */
    @Volatile var streamInfo: DecodedStreamInfo? = null
        private set

    private val listeners = CopyOnWriteArrayList<Player.Listener>()

    // ── Render-thread state ──────────────────────────────────────────────────
    private sealed interface Command {
        class SetItem(val item: MediaItem, val startMs: Long) : Command
        object Play : Command
        object Pause : Command
        object Stop : Command
        class Seek(val positionMs: Long) : Command
        class Preload(val item: MediaItem?) : Command
        class Crossfade(val item: MediaItem, val heardMs: Long, val fromMediaId: String?) : Command
        object CancelCrossfade : Command
        class SetSink(val factory: () -> AudioSink) : Command
        class SetSpeed(val parameters: PlaybackParameters) : Command
        class SetLive(val input: LiveInput?, val sampleRate: Int) : Command
        object Release : Command
    }

    /** A blend under way: the outgoing track's [tail] fading out under the current one. */
    private class Fade(val tail: Source, val totalFrames: Long) {
        var doneFrames = 0L
    }

    /**
     * A join written to the sink but not heard yet: [to] is already the
     * current source, [from] is what the listener still hears and sees. [to]'s
     * first frame is sink frame [atFrame] (timeline value [atTimelineUs]) and
     * plays media time [toStartUs]; [fadeHeardMs] is the blend it began, or 0.
     */
    private class Pending(
        val from: Source,
        val to: Source,
        val atFrame: Long,
        val atTimelineUs: Long,
        val toStartUs: Long,
        val fadeHeardMs: Long,
    )

    private val commands = ConcurrentLinkedQueue<Command>()
    private val renderThread = Thread(::renderLoop, "tryptify-render").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }
    @Volatile private var alive = true

    private var chain = AudioProcessorChain(processors) { Log.d(TAG, it) }
    private var sink: AudioSink? = null
    /** The sink is the engine's own Java Sound line because every output of [sinkFactory] refused the format. */
    private var sinkIsLastResort = false
    /** Whether [sink] has been started since it was last configured, flushed or paused. */
    private var sinkRunning = false
    private var packer: PcmPacker? = null
    private var chainConfigured = false
    @Volatile private var current: Source? = null
    @Volatile private var next: Source? = null
    private var fade: Fade? = null
    private var pending: Pending? = null
    private var pendingProcessed: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputBuf: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var tailBuf: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var mixIncoming = FloatArray(0)
    private var mixOutgoing = FloatArray(0)
    private var draining = false
    private var drainStartedNs = 0L
    // Desktop: the DJ console's stream (see [setLiveInput]).
    private var live: LiveInput? = null
    private var liveFormat: AudioFormat = AudioFormat.NOT_SET
    private var liveBlock = FloatArray(0)

    // The sink clock. Everything here counts from the sink's last configure or
    // flush, when the device's played-frame counter restarts too.
    /** Frames this engine has written since. */
    private var framesWritten = 0L
    /**
     * Frames the sink queued ahead of the first one we wrote: an exclusive
     * WASAPI stream primes a buffer of silence when it starts. Measured at the
     * first write, so sink frame numbers and ours agree.
     */
    private var clockOffsetFrames = 0L
    private var clockAnchored = false
    /** Playout time since the sink restarted → media time, across speed changes. */
    private val timeline = SpeedTimeline()
    /** The playing item's media time [baseMediaUs] plays at timeline value [baseTimelineUs]; UNSET until its first frame is written. */
    private var baseMediaUs = C.TIME_UNSET
    private var baseTimelineUs = 0L
    /** What [positionUs] reports before the base is known: the start or seek target. */
    private var positionHoldUs = 0L

    init {
        renderThread.start()
    }

    // ── Player API (any thread) ──────────────────────────────────────────────
    override val playbackState: Int get() = _playbackState
    override val isPlaying: Boolean get() = _isPlaying
    override val playWhenReady: Boolean get() = _playWhenReady
    override val isLoading: Boolean get() = _playbackState == Player.STATE_BUFFERING
    override val currentMediaItem: MediaItem? get() = _currentItem
    override val mediaMetadata: MediaMetadata get() = _currentItem?.mediaMetadata ?: MediaMetadata.EMPTY
    override val mediaItemCount: Int get() = (if (_currentItem != null) 1 else 0) + (if (next != null) 1 else 0)
    override val currentMediaItemIndex: Int get() = 0
    override val duration: Long get() = _duration
    override val currentPosition: Long get() = positionUs() / 1000
    override val bufferedPosition: Long get() = (positionUs() + (current?.bufferedUs() ?: 0L)) / 1000
    override var volume: Float
        get() = _volume
        set(value) { _volume = value.coerceIn(0f, 1f); notify { it.onVolumeChanged(_volume) } }
    override var playbackParameters: PlaybackParameters
        get() = _playbackParameters
        set(value) { commands.add(Command.SetSpeed(value)) }
    override val playerError: PlaybackException? get() = _error

    override fun addListener(listener: Player.Listener) { listeners.addIfAbsent(listener) }
    override fun removeListener(listener: Player.Listener) { listeners.remove(listener) }
    override fun setMediaItem(mediaItem: MediaItem) = setMediaItem(mediaItem, 0L)
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) { commands.add(Command.SetItem(mediaItem, startPositionMs)) }
    override fun setMediaItems(mediaItems: List<MediaItem>) {
        mediaItems.firstOrNull()?.let { setMediaItem(it) }
        preloadNext(mediaItems.getOrNull(1))
    }
    override fun addMediaItem(mediaItem: MediaItem) = preloadNext(mediaItem)
    override fun removeMediaItem(index: Int) { if (index == 1) preloadNext(null) }
    override fun getMediaItemAt(index: Int): MediaItem = (if (index == 0) _currentItem else next?.item) ?: MediaItem.EMPTY
    override fun prepare() { /* opening happens on setMediaItem */ }
    override fun play() { commands.add(Command.Play) }
    override fun pause() { commands.add(Command.Pause) }
    override fun stop() { commands.add(Command.Stop) }
    override fun release() { commands.add(Command.Release) }
    override fun seekTo(positionMs: Long) { commands.add(Command.Seek(positionMs)) }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = seekTo(positionMs)
    override fun seekToNext() { queueNavigator?.next() }
    override fun seekToPrevious() { queueNavigator?.previous() ?: seekTo(0) }
    override fun seekToNextMediaItem() { queueNavigator?.next() }
    override fun seekToPreviousMediaItem() { queueNavigator?.previous() ?: seekTo(0) }
    override fun hasNextMediaItem(): Boolean = next != null
    override fun hasPreviousMediaItem(): Boolean = false
    override fun clearMediaItems() = stop()

    /** Gapless pre-roll of the following track; null drops it (an armed crossfade included). */
    fun preloadNext(item: MediaItem?) { commands.add(Command.Preload(item)) }

    /**
     * Arms a crossfade of [crossfadeMs] heard milliseconds into [item], which
     * starts decoding now. [fromMediaId] is the item the blend leaves: if
     * something else is playing by the time the render thread gets this (a
     * skip overtook it), it is ignored. The blend itself starts when the
     * current track gets there; see the class comment.
     */
    fun crossfadeTo(item: MediaItem, crossfadeMs: Long, fromMediaId: String? = null) {
        commands.add(Command.Crossfade(item, crossfadeMs, fromMediaId))
    }

    /**
     * Drops an armed crossfade and cuts a running one short: the outgoing
     * track stops and the current one plays on at full level, as Android's
     * CrossfadeController.cancel did.
     */
    fun cancelCrossfade() { commands.add(Command.CancelCrossfade) }

    /** Switches output (WASAPI shared/exclusive, Java Sound, libusb); reopens at the current position. */
    fun setSink(factory: () -> AudioSink) { commands.add(Command.SetSink(factory)) }

    /**
     * Desktop: a live stream the render thread plays in place of the item:
     * the DJ console's deck A. It runs through the same chain and sink as a
     * track, so the mixer's buses, the EQs and the visualizer all hear it.
     */
    fun interface LiveInput {
        /**
         * Fills [dst] with [frames] frames of interleaved float stereo. Runs on
         * the render thread once per block, so it must not block.
         */
        fun read(dst: FloatArray, frames: Int)
    }

    /**
     * Desktop: plays [input] at [sampleRate] instead of the item, or, with
     * null, hands the output back. The item pauses where it was and stays
     * there (play is ignored while a live input runs; seeks and new items
     * still land, unheard) and resumes from there when the output comes back.
     */
    fun setLiveInput(input: LiveInput?, sampleRate: Int = preferredLiveRate) {
        commands.add(Command.SetLive(input, sampleRate))
    }

    /** Desktop: the rate a live input should run at: the device's, once one is open, so nothing resamples twice. */
    val preferredLiveRate: Int get() = sinkFormat?.sampleRate ?: DEFAULT_LIVE_RATE

    /**
     * Media time, microseconds, as of the render thread's last reading of the
     * device clock (it reads it on every pass, every few milliseconds while
     * playing). The sink itself is never called from another thread: the
     * render thread releases and reopens it, and a native stream closed under
     * a reader on another thread is a use-after-free.
     */
    fun positionUs(): Long = publishedPositionUs

    // ── Render thread ────────────────────────────────────────────────────────
    private fun renderLoop() {
        while (alive) {
            try {
                val cmd = commands.poll()
                if (cmd != null) { handle(cmd); continue }
                live?.let { liveStep(it); continue }
                val src = current
                if (src == null || !_playWhenReady || _playbackState == Player.STATE_IDLE || _playbackState == Player.STATE_ENDED) {
                    if (src != null && !_playWhenReady && _playbackState == Player.STATE_BUFFERING) settleWhilePaused(src)
                    LockSupport.parkNanos(5_000_000)
                    continue
                }
                step()
            } catch (e: Throwable) {
                Log.e(TAG, "render loop error", e)
                // Nothing may escape this loop: it is the engine's only thread.
                try {
                    fail(PlaybackException(e.message, e, if (e is SinkException) PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED else PlaybackException.ERROR_CODE_UNSPECIFIED))
                } catch (inner: Throwable) {
                    Log.e(TAG, "could not report the error", inner)
                }
            }
        }
        closeAll()
        sink?.release()
    }

    private fun handle(cmd: Command) {
        when (cmd) {
            is Command.SetItem -> openItem(cmd.item, cmd.startMs * 1000)
            Command.Play -> {
                // Desktop: the DJ console has the output; the item stays paused.
                if (live != null) return
                _playWhenReady = true
                _error = null
                if (_playbackState == Player.STATE_ENDED) { current?.let { openItem(it.item, 0) } }
                // Resume what the device already holds. After a flush it holds
                // nothing, and starting it would only play an underrun until the
                // decoder catches up; the first write starts it instead.
                if (chainConfigured && clockAnchored) startSink()
                updateIsPlaying()
                notify { it.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
            }
            Command.Pause -> {
                _playWhenReady = false
                if (sinkRunning && live == null) { sink?.pause(); sinkRunning = false }
                publishPosition()
                updateIsPlaying()
                notify { it.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
            }
            Command.Stop -> stopInternal()
            is Command.Seek -> seekInternal(cmd.positionMs * 1000)
            is Command.Preload -> preloadInternal(cmd.item)
            is Command.Crossfade -> crossfadeInternal(cmd.item, cmd.heardMs, cmd.fromMediaId)
            Command.CancelCrossfade -> cancelCrossfadeInternal()
            is Command.SetSink -> {
                // Read before the sink goes: with no sink there is no clock, and
                // the position would fall back to where the item began.
                val resumeAt = computePositionUs()
                sinkFactory = cmd.factory
                sink?.release(); sink = null
                sinkIsLastResort = false
                sinkRunning = false
                chainConfigured = false
                pendingProcessed = AudioProcessor.EMPTY_BUFFER
                // Re-anchor the clock on the new device from where the listener
                // was. Only while something is playing or about to: re-seeking an
                // ended track would play its last moment again and end it a
                // second time, which the controller takes as the next track ending.
                if (current != null && (_playbackState == Player.STATE_READY || _playbackState == Player.STATE_BUFFERING)) {
                    seekInternal(resumeAt)
                }
            }
            is Command.SetSpeed -> {
                _playbackParameters = cmd.parameters
                if (sinkFormat != null) timeline.setFactor(outUs(sinkFrame()), cmd.parameters.speed.toDouble())
                notify { it.onPlaybackParametersChanged(cmd.parameters) }
            }
            is Command.SetLive -> setLiveInternal(cmd.input, cmd.sampleRate)
            Command.Release -> alive = false
        }
    }

    /**
     * Desktop: starts, swaps or ends the live input. Starting pauses the item
     * (reported as a pause, so the controls agree) and keeps its position;
     * ending re-anchors it there, as a sink switch does.
     */
    private fun setLiveInternal(input: LiveInput?, sampleRate: Int) {
        val was = live
        if (input != null) {
            if (was == null) {
                if (_playWhenReady) {
                    _playWhenReady = false
                    notify { it.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
                }
                positionHoldUs = computePositionUs()
                publishedPositionUs = positionHoldUs
                baseMediaUs = C.TIME_UNSET
                flushOutput()
                updateIsPlaying()
            }
            live = input
            val format = AudioFormat(sampleRate.coerceAtLeast(8_000), 2, C.ENCODING_PCM_FLOAT)
            if (format != liveFormat) { liveFormat = format; chainConfigured = false }
            Log.i(TAG, "live input on at ${format.sampleRate} Hz")
            return
        }
        if (was == null) return
        live = null
        flushOutput()
        chainConfigured = false
        liveFormat = AudioFormat.NOT_SET
        Log.i(TAG, "live input off")
        if (current != null && (_playbackState == Player.STATE_READY || _playbackState == Player.STATE_BUFFERING)) {
            seekInternal(positionHoldUs)
        }
    }

    /** Desktop: one pass of the live input: a block of it through the chain, written with back-pressure. */
    private fun liveStep(input: LiveInput) {
        ensureConfigured(liveFormat)
        if (pendingProcessed.hasRemaining()) {
            writeOut(pendingProcessed)
            if (pendingProcessed.hasRemaining()) { LockSupport.parkNanos(2_000_000); return }
        }
        val frames = liveFormat.sampleRate / LIVE_BLOCKS_PER_SECOND
        val samples = frames * 2
        if (liveBlock.size < samples) liveBlock = FloatArray(samples)
        input.read(liveBlock, frames)
        inputBuf.clear()
        for (i in 0 until samples) inputBuf.putFloat(liveBlock[i])
        inputBuf.flip()
        process(inputBuf)
    }

    private fun openItem(item: MediaItem, startUs: Long) {
        val reuse = next?.takeIf { it.item.mediaId == item.mediaId && it.item.localConfiguration?.uri == item.localConfiguration?.uri && startUs == 0L }
        closeAll(keep = reuse)
        flushOutput()
        _currentItem = item
        _duration = C.TIME_UNSET
        streamInfo = null
        _error = null
        val src = reuse ?: Source(item, startUs, floatOutput = crossfadeMs > 0L)
        src.fadeHeardMs = 0L
        current = src
        if (reuse == null) src.start()
        baseMediaUs = C.TIME_UNSET
        positionHoldUs = startUs
        publishedPositionUs = startUs
        setState(Player.STATE_BUFFERING)
        notify { it.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
        notify { it.onMediaMetadataChanged(item.mediaMetadata) }
    }

    private fun preloadInternal(item: MediaItem?) {
        if (item == null) { next?.close(); next = null; return }
        val existing = next
        if (existing != null && existing.item.mediaId == item.mediaId) { existing.fadeHeardMs = 0L; return }
        existing?.close()
        next = Source(item, 0L, floatOutput = crossfadeMs > 0L).also { it.start() }
    }

    private fun crossfadeInternal(item: MediaItem, heardMs: Long, fromMediaId: String?) {
        val cur = current ?: return
        if (fromMediaId != null && cur.item.mediaId != fromMediaId) return
        // A join already under way owns the next item.
        if (fade != null || pending != null || heardMs <= 0L) return
        val existing = next
        if (existing != null && existing.item.mediaId == item.mediaId && existing.item.localConfiguration?.uri == item.localConfiguration?.uri) {
            existing.fadeHeardMs = heardMs
            return
        }
        existing?.close()
        // In the chain's encoding: float when the chain runs float, so the two
        // always match; the codec's own when it runs 16-bit (blending is enabled
        // mid-track), so a 16-bit next track still can.
        val float = !cur.opened || cur.format.encoding == C.ENCODING_PCM_FLOAT
        next = Source(item, 0L, floatOutput = float).also { it.fadeHeardMs = heardMs; it.start() }
    }

    private fun cancelCrossfadeInternal() {
        next?.takeIf { it.fadeHeardMs > 0L }?.let { it.close(); next = null }
        endFade()
    }

    private fun stopInternal() {
        closeAll()
        flushOutput()
        baseMediaUs = C.TIME_UNSET
        positionHoldUs = 0L
        publishedPositionUs = 0L
        _currentItem = null
        _duration = C.TIME_UNSET
        setState(Player.STATE_IDLE)
        updateIsPlaying()
    }

    private fun seekInternal(positionUs: Long) {
        var src = current ?: return
        val target = positionUs.coerceAtLeast(0)
        val p = pending
        if (p != null) {
            // The listener still hears, and the UI still shows, the outgoing
            // track: the seek is into it. Undo the join: it becomes current
            // again and the next track goes back to its start, re-armed if it
            // was a blend, so the join happens again when the end comes round.
            pending = null
            fade = null
            p.to.close()
            next = Source(p.to.item, p.to.startUs, p.to.floatOutput).also { it.fadeHeardMs = p.fadeHeardMs; it.start() }
            p.from.followTaps(true)
            current = p.from
            src = p.from
        } else if (fade != null) {
            // Past the hand-off the seek is into the incoming track; the tail
            // is dropped, as Android's skip and seek paths left only the main
            // player's track.
            endFade()
        }
        src.requestSeek(target)
        flushOutput()
        baseMediaUs = C.TIME_UNSET
        positionHoldUs = target
        publishedPositionUs = target
        setState(Player.STATE_BUFFERING)
        val info = Player.PositionInfo(src.item, 0, target / 1000)
        notify { it.onPositionDiscontinuity(info, info, Player.DISCONTINUITY_REASON_SEEK) }
    }

    /**
     * Drops everything queued between the decoder and the device: the
     * processed buffer, the sink's queue, the processors' state and the Atmos
     * frames (as Android's sink flush did, through the processor's flush).
     * The sink is left stopped and its clock restarts.
     */
    private fun flushOutput() {
        // Desktop: the live input's stream owns the output while it runs; what
        // was flushed for the item happens when it gets the output back.
        if (live != null) return
        pendingProcessed = AudioProcessor.EMPTY_BUFFER
        sink?.flush()
        sinkRunning = false
        chain.flush()
        atmosFrames?.clear()
        draining = false
        resetClock()
    }

    private fun resetClock() {
        framesWritten = 0L
        clockOffsetFrames = 0L
        clockAnchored = false
        timeline.reset(0, _playbackParameters.speed.toDouble())
    }

    /** Sink frame number of the next frame written, in the device clock's count. */
    private fun sinkFrame(): Long = clockOffsetFrames + framesWritten

    private fun outUs(frames: Long): Long = frames * C.MICROS_PER_SECOND / (sinkFormat?.sampleRate ?: 1).coerceAtLeast(1)

    /**
     * Starts the device if playback wants it and it is stopped; the first time
     * after a configure or flush, also learns how far the sink's own frame
     * count runs ahead of ours.
     */
    private fun startSink() {
        val s = sink ?: return
        if (!_playWhenReady && live == null) return
        if (!sinkRunning) { s.play(); sinkRunning = true }
        if (!clockAnchored) {
            clockOffsetFrames = (s.playedFrames() + s.pendingFrames() - framesWritten).coerceAtLeast(0)
            clockAnchored = true
        }
    }

    private fun ensureConfigured(src: Source) = ensureConfigured(src.format)

    private fun ensureConfigured(format: AudioFormat) {
        if (chainConfigured && audioFormat == format) return
        val out = chain.configure(format)
        audioFormat = format
        var s = sink
        if (s == null || sinkIsLastResort) {
            // A track every output refused fell back to the engine's own Java
            // Sound line; the next format gets the listener's outputs again.
            s?.release()
            s = sinkFactory()
            sink = s
            sinkIsLastResort = false
        }
        val negotiated = try {
            s.configure(out)
        } catch (e: SinkException) {
            Log.w(TAG, "sink refused ${out}; falling back to Java Sound", e)
            s.release()
            val fallback = JavaSoundSink()
            sink = fallback
            sinkIsLastResort = true
            fallback.configure(out)
        }
        sinkFormat = negotiated
        packer = PcmPacker(out, negotiated)
        val chunkBytes = (format.sampleRate / 50) * format.bytesPerFrame   // 20 ms of input
        if (inputBuf.capacity() < chunkBytes) {
            inputBuf = ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.nativeOrder())
            tailBuf = ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.nativeOrder())
        }
        chainConfigured = true
        // A (re)configured sink is a new stream: stopped, its clock at zero.
        // The item's base goes with the clock; the next read sets it again
        // from the source's own read position.
        sinkRunning = false
        if (baseMediaUs != C.TIME_UNSET) { positionHoldUs = publishedPositionUs; baseMediaUs = C.TIME_UNSET }
        resetClock()
        val active = sink
        Log.i(TAG, "configured chain in=$format out=$out sink=$negotiated (${active?.javaClass?.simpleName}${if (active?.isExclusive == true) " exclusive" else ""})")
    }

    private fun step() {
        val src = current ?: return
        if (!src.opened) {
            src.error?.let { fail(PlaybackException("cannot open ${src.item.localConfiguration?.uri}", it, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)); return }
            LockSupport.parkNanos(5_000_000)
            return
        }
        // While a join is unheard the outgoing item's duration stands.
        if (_duration == C.TIME_UNSET && pending == null && src.durationUs > 0) {
            _duration = src.durationUs / 1000
            streamInfo = src.info
        }
        ensureConfigured(src)
        checkTransition()
        publishPosition()

        if (pendingProcessed.hasRemaining()) {
            writeOut(pendingProcessed)
            if (pendingProcessed.hasRemaining()) { LockSupport.parkNanos(2_000_000); return }
        }

        if (draining) { drainStep(src); return }

        // A seek is the decode thread's to carry out; nothing in the ring is
        // usable until it says it has.
        if (!src.seekSettled()) {
            if (_playbackState == Player.STATE_READY) setState(Player.STATE_BUFFERING)
            LockSupport.parkNanos(2_000_000)
            return
        }

        fade?.let { fadeStep(src, it); return }

        val bytesPerFrame = src.format.bytesPerFrame
        if (src.ring.available() < bytesPerFrame) {
            if (src.eof) { onSourceExhausted(src); return }
            if (src.error != null) { fail(PlaybackException("decode failed", src.error, PlaybackException.ERROR_CODE_DECODING_FAILED)); return }
            if (_playbackState == Player.STATE_READY) setState(Player.STATE_BUFFERING)
            LockSupport.parkNanos(5_000_000)
            return
        }
        if (!readyToPlay(src)) return

        var maxFrames = inputBuf.capacity() / bytesPerFrame
        // An armed blend begins exactly at its point: read up to it and no further.
        val armed = next?.takeIf { it.fadeHeardMs > 0L }
        if (armed != null) {
            when (val plan = planBlend(src, armed)) {
                is CrossfadeMix.Plan.Wait -> if (plan.frames < maxFrames) maxFrames = plan.frames.toInt()
                is CrossfadeMix.Plan.Start -> if (blendReady(src, armed)) { startFade(src, armed, plan.frames); return }
                CrossfadeMix.Plan.Skip -> {
                    Log.i(TAG, "no blend into ${armed.item.mediaId}: too close to the end")
                    armed.fadeHeardMs = 0L
                }
            }
        }

        startSink()
        if (baseMediaUs == C.TIME_UNSET && pending == null) setBase(src.readPositionUs(), sinkFrame())
        inputBuf.clear()
        val bytes = src.read(inputBuf, maxFrames * bytesPerFrame)
        inputBuf.flip()
        if (bytes == 0) return
        process(inputBuf)
    }

    /**
     * A track opened or seeked while paused is READY once it is buffered, as
     * Media3 reports a prepared player: the UI shows a paused track, not a
     * spinner, until play.
     */
    private fun settleWhilePaused(src: Source) {
        if (!src.opened || !src.seekSettled() || src.error != null) return
        if (!src.eof && src.bufferedUs() < MIN_BUFFER_US) return
        if (_duration == C.TIME_UNSET && pending == null && src.durationUs > 0) {
            _duration = src.durationUs / 1000
            streamInfo = src.info
        }
        setState(Player.STATE_READY)
    }

    /** Leaves BUFFERING once enough is decoded; false while still waiting. */
    private fun readyToPlay(src: Source): Boolean {
        if (_playbackState == Player.STATE_BUFFERING) {
            if (!src.eof && src.bufferedUs() < MIN_BUFFER_US) { LockSupport.parkNanos(5_000_000); return false }
            setState(Player.STATE_READY)
            updateIsPlaying()
        }
        return true
    }

    private fun process(buf: ByteBuffer) {
        // Membership is re-read every block, not only at configure: stages whose
        // activity follows a live control (VariRate's ratio, the stretch stage's
        // semitones) would otherwise stay out of a chain configured while they
        // were idle. Android got this from DefaultAudioSink re-flushing on a
        // parameter change; nothing re-flushes this chain.
        chain.refreshActive()
        val processed = if (chain.anyActive()) chain.process(buf) else buf
        if (processed.hasRemaining()) {
            pendingProcessed = processed
            writeOut(processed)
        }
    }

    // ── Crossfade ────────────────────────────────────────────────────────────
    private fun planBlend(src: Source, armed: Source): CrossfadeMix.Plan = CrossfadeMix.plan(
        readPositionUs = src.readPositionUs(),
        durationUs = src.durationUs,
        crossfadeMs = armed.fadeHeardMs,
        speed = _playbackParameters.speed,
        sampleRate = src.format.sampleRate,
        incomingDurationUs = if (armed.opened) armed.durationUs else C.TIME_UNSET,
    )

    /**
     * Whether the armed next track can be blended in now. Not yet opened or
     * buffered: keep playing and ask again (the blend starts late and runs
     * shorter, or is skipped). A different rate, channel count or encoding:
     * no blend, and the track follows on the gapless or reconfiguring way.
     */
    private fun blendReady(src: Source, armed: Source): Boolean {
        if (armed.error != null) { armed.fadeHeardMs = 0L; return false }
        if (!armed.opened || !armed.seekSettled()) return false
        if (armed.format != src.format) {
            Log.i(TAG, "no blend into ${armed.item.mediaId}: ${src.format} -> ${armed.format}")
            armed.fadeHeardMs = 0L
            return false
        }
        return armed.eof || armed.bufferedUs() >= MIN_BUFFER_US
    }

    private fun startFade(src: Source, incoming: Source, totalFrames: Long) {
        Log.i(TAG, "crossfade into ${incoming.item.mediaId} over ${totalFrames * 1000 / src.format.sampleRate} ms")
        startSink()
        commitPending()
        if (baseMediaUs == C.TIME_UNSET) setBase(src.readPositionUs(), sinkFrame())
        val at = sinkFrame()
        pending = Pending(
            from = src, to = incoming, atFrame = at,
            atTimelineUs = timeline.mediaAtWritePosition(outUs(at)),
            toStartUs = incoming.readPositionUs(), fadeHeardMs = incoming.fadeHeardMs,
        )
        fade = Fade(src, totalFrames)
        incoming.fadeHeardMs = 0L
        current = incoming
        next = null
        src.followTaps(false)
    }

    /** One block of a blend: both tracks read in step, summed, and the sum processed. */
    private fun fadeStep(incoming: Source, f: Fade) {
        val tail = f.tail
        val format = incoming.format
        val bytesPerFrame = format.bytesPerFrame
        val inFrames = incoming.ring.available() / bytesPerFrame
        if (inFrames == 0) {
            // The incoming track is shorter than its blend could be told: finish
            // the blend and let it end the ordinary way.
            if (incoming.eof) { endFade(); return }
            if (incoming.error != null) { fail(PlaybackException("decode failed", incoming.error, PlaybackException.ERROR_CODE_DECODING_FAILED)); return }
            if (_playbackState == Player.STATE_READY) setState(Player.STATE_BUFFERING)
            LockSupport.parkNanos(5_000_000)
            return
        }
        val tailAvailable = tail.ring.available() / bytesPerFrame
        if (tailAvailable == 0 && !tail.eof && tail.error == null) {
            // The outgoing track's decoder is behind (a network stall): wait for it rather than drop its audio.
            LockSupport.parkNanos(2_000_000)
            return
        }
        if (!readyToPlay(incoming)) return
        startSink()

        var frames = minOf(inFrames.toLong(), (inputBuf.capacity() / bytesPerFrame).toLong(), f.totalFrames - f.doneFrames).toInt()
        // In step: while the tail has audio, never run the incoming ahead of it.
        if (!tail.eof && tailAvailable in 1 until frames) frames = tailAvailable
        val tailFrames = minOf(frames, tailAvailable)
        val samples = frames * format.channelCount
        if (mixIncoming.size < samples) { mixIncoming = FloatArray(samples); mixOutgoing = FloatArray(samples) }

        inputBuf.clear()
        incoming.read(inputBuf, frames * bytesPerFrame)
        inputBuf.flip()
        CrossfadeMix.toFloat(inputBuf, mixIncoming, samples, format.encoding)
        if (tailFrames > 0) {
            tailBuf.clear()
            tail.read(tailBuf, tailFrames * bytesPerFrame)
            tailBuf.flip()
            CrossfadeMix.toFloat(tailBuf, mixOutgoing, tailFrames * format.channelCount, format.encoding)
        }
        CrossfadeMix.mix(mixIncoming, mixOutgoing, frames, tailFrames, format.channelCount, f.doneFrames, f.totalFrames)
        inputBuf.clear()
        CrossfadeMix.fromFloat(mixIncoming, samples, inputBuf, format.encoding)
        inputBuf.flip()
        f.doneFrames += frames
        if (f.doneFrames >= f.totalFrames) endFade()
        process(inputBuf)
    }

    /** Ends a blend; the tail closes now, or once the join is heard if it is still what the listener hears. */
    private fun endFade() {
        val f = fade ?: return
        fade = null
        if (pending?.from !== f.tail) f.tail.close()
    }

    // ── Joins ────────────────────────────────────────────────────────────────
    /** The current ring ran dry at end of stream: splice to the next track or drain. */
    private fun onSourceExhausted(src: Source) {
        val n = next
        if (n != null && n.opened && n.seekSettled() && n.error == null && n.format == src.format) {
            // Seamless: keep the chain running, swap rings, report the transition when heard.
            Log.i(TAG, "gapless splice to ${n.item.mediaId}")
            commitPending()
            if (baseMediaUs == C.TIME_UNSET) setBase(src.readPositionUs(), sinkFrame())
            val at = sinkFrame()
            pending = Pending(
                from = src, to = n, atFrame = at,
                atTimelineUs = timeline.mediaAtWritePosition(outUs(at)),
                toStartUs = n.readPositionUs(), fadeHeardMs = 0L,
            )
            n.fadeHeardMs = 0L
            current = n
            next = null
            return
        }
        draining = true
        drainStartedNs = System.nanoTime()
        chain.queueEndOfStream()
    }

    private fun drainStep(src: Source) {
        if (!chain.isEnded() && chain.anyActive()) {
            val out = chain.process(AudioProcessor.EMPTY_BUFFER)
            if (out.hasRemaining()) { pendingProcessed = out; writeOut(out); return }
            if (!chain.isEnded()) {
                // A stage that never reports ended must not hold the queue forever.
                if (System.nanoTime() - drainStartedNs < DRAIN_TIMEOUT_NS) { LockSupport.parkNanos(1_000_000); return }
                Log.w(TAG, "processor chain did not finish draining; ending the track without its tail")
            }
        }
        startSink()
        val s = sink
        s?.drain()
        if (s != null && s.pendingFrames() > 0 && _playWhenReady) { LockSupport.parkNanos(5_000_000); return }
        draining = false
        commitPending()
        val n = next
        if (n != null && n.error == null) {
            // Different format (or not ready in time for a splice): reconfigure and continue.
            Log.i(TAG, "transition into ${n.item.mediaId} after draining ${src.format}${if (n.opened) " -> ${n.format}" else " (still opening)"}")
            src.close()
            n.fadeHeardMs = 0L
            current = n
            next = null
            chainConfigured = false
            baseMediaUs = C.TIME_UNSET
            positionHoldUs = n.startUs
            publishedPositionUs = n.startUs
            _currentItem = n.item
            _duration = C.TIME_UNSET
            streamInfo = null
            setState(Player.STATE_BUFFERING)
            notify { it.onMediaItemTransition(n.item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
            notify { it.onMediaMetadataChanged(n.item.mediaMetadata) }
            return
        }
        publishPosition()
        setState(Player.STATE_ENDED)
        updateIsPlaying()
    }

    /** Reports a written join once the device clock reaches it. */
    private fun checkTransition() {
        val p = pending ?: return
        val s = sink ?: return
        if (s.playedFrames() < p.atFrame) return
        completeTransition(p)
    }

    /** Reports a join that has not been heard yet now: another is about to be written behind it. */
    private fun commitPending() {
        pending?.let { completeTransition(it) }
    }

    private fun completeTransition(p: Pending) {
        pending = null
        if (fade?.tail !== p.from) p.from.close()
        baseMediaUs = p.toStartUs
        baseTimelineUs = p.atTimelineUs
        val n = p.to
        _currentItem = n.item
        _duration = if (n.durationUs > 0) n.durationUs / 1000 else C.TIME_UNSET
        streamInfo = n.info
        publishPosition()
        notify { it.onMediaItemTransition(n.item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
        notify { it.onMediaMetadataChanged(n.item.mediaMetadata) }
    }

    // ── Clock ────────────────────────────────────────────────────────────────
    /** The playing item's [mediaUs] plays at sink frame [frame]. */
    private fun setBase(mediaUs: Long, frame: Long) {
        baseMediaUs = mediaUs
        baseTimelineUs = timeline.mediaAtWritePosition(outUs(frame))
    }

    /** Render thread: the position now, from the device clock. */
    private fun computePositionUs(): Long {
        val base = baseMediaUs
        if (base == C.TIME_UNSET) return positionHoldUs
        val s = sink ?: return base
        if (sinkFormat == null) return base
        val played = outUs(s.playedFrames())
        val media = base + (timeline.mediaAt(played) - baseTimelineUs).coerceAtLeast(0)
        val dur = _duration
        return if (dur > 0) media.coerceAtMost(dur * 1000) else media
    }

    private fun publishPosition() {
        publishedPositionUs = computePositionUs()
    }

    /** Writes as much of [buf] as the sink takes; advances [buf] by what was accepted. */
    private fun writeOut(buf: ByteBuffer) {
        val s = sink ?: return
        val p = packer ?: return
        startSink()
        val inFormat = chain.outputFormat()
        val inBpf = inFormat.bytesPerFrame
        val frames = buf.remaining() / inBpf
        if (frames <= 0) { buf.position(buf.limit()); return }
        val start = buf.position()
        val gain = _volume
        val packed = p.pack(buf, frames, gain)
        val written = if (packed === buf) {
            s.write(buf, frames)
        } else {
            val w = s.write(packed, frames)
            buf.position(start + w * inBpf)
            w
        }
        if (written > 0) framesWritten += written
    }

    private fun fail(error: PlaybackException) {
        _error = error
        Log.e(TAG, "playback error: ${error.message}", error.cause)
        closeAll()
        // The sink may be what failed (a device that went away); its flush must
        // not stop the error from being reported.
        try { flushOutput() } catch (e: Throwable) { Log.w(TAG, "flush after an error failed", e); resetClock() }
        setState(Player.STATE_IDLE)
        updateIsPlaying()
        notify { it.onPlayerError(error) }
    }

    /** Closes every open stream except [keep]: current, next, a blend's tail and an unheard join's outgoing track. */
    private fun closeAll(keep: Source? = null) {
        val open = listOfNotNull(current, next, fade?.tail, pending?.from, pending?.to)
        for (s in open.distinct()) if (s !== keep) s.close()
        current = null; next = null; fade = null; pending = null
    }

    private fun setState(state: Int) {
        if (_playbackState == state) return
        _playbackState = state
        notify { it.onPlaybackStateChanged(state) }
        updateIsPlaying()
    }

    private fun updateIsPlaying() {
        val playing = _playWhenReady && _playbackState == Player.STATE_READY
        if (playing != _isPlaying) {
            _isPlaying = playing
            notify { it.onIsPlayingChanged(playing) }
        }
    }

    private fun notify(block: (Player.Listener) -> Unit) {
        if (listeners.isEmpty()) return
        callbackExecutor.execute { for (l in listeners) try { block(l) } catch (e: Throwable) { Log.e(TAG, "listener failed", e) } }
    }

    // ── A stream being decoded ───────────────────────────────────────────────
    /**
     * One open stream and its decode thread.
     *
     * The ring is single-producer (the decode thread) and single-consumer (the
     * render thread), and only the producer ever clears it. A seek is a request
     * the decode thread carries out: it seeks the decoder, empties the ring
     * and the Atmos queue, and then acknowledges. Until the acknowledgement the
     * render thread reads nothing from the ring, so nothing decoded before the
     * seek can be played after it and the two threads never write the same
     * cursor.
     */
    private inner class Source(val item: MediaItem, val startUs: Long, val floatOutput: Boolean) {
        @Volatile var opened = false
        @Volatile var eof = false
        @Volatile var error: Throwable? = null
        @Volatile private var cancelled = false
        @Volatile private var seekTargetUs = C.TIME_UNSET
        @Volatile private var seekRequested = 0
        @Volatile private var seekHandled = 0
        /** Media time of the first frame in the ring since the last open or seek. */
        @Volatile private var ringStartUs = startUs
        @Volatile private var taps: AtmosTapQueue? = null
        @Volatile private var tapsStopped = false
        /** Blend length when this is the next item and a crossfade is armed into it; 0 for a plain next. Render thread. */
        var fadeHeardMs = 0L
        lateinit var format: AudioFormat
        lateinit var ring: PcmRing
        @Volatile var info: DecodedStreamInfo? = null
        @Volatile var durationUs = C.TIME_UNSET
        /** Frames the render thread has read since the ring started at [ringStartUs]. */
        private var consumedFrames = 0L
        private val thread = Thread(::run, "tryptify-decode").apply { isDaemon = true }

        fun start() = thread.start()

        /** True unless a seek is waiting for the decode thread. */
        fun seekSettled(): Boolean = seekHandled == seekRequested

        /** Media time of the next frame the render thread would read. */
        fun readPositionUs(): Long =
            if (!opened) ringStartUs else ringStartUs + consumedFrames * C.MICROS_PER_SECOND / format.sampleRate

        fun bufferedUs(): Long {
            if (!opened) return 0
            return ring.available() / format.bytesPerFrame * C.MICROS_PER_SECOND / format.sampleRate
        }

        /** Render thread. Asks the decode thread to seek; see the class comment. */
        fun requestSeek(positionUs: Long) {
            seekTargetUs = positionUs
            seekRequested++   // volatile: publishes the target with it
            consumedFrames = 0
            taps?.restart()
        }

        /**
         * Render thread: reads up to [maxBytes] of PCM and hands the Atmos
         * renderer the access units whose time that PCM has reached.
         */
        fun read(dst: ByteBuffer, maxBytes: Int): Int {
            val bytes = ring.read(dst, maxBytes)
            consumedFrames += bytes / format.bytesPerFrame
            val q = taps
            val frames = atmosFrames
            if (q != null && frames != null && !tapsStopped && bytes > 0) {
                q.release(ringStartUs, readPositionUs()) { timeUs, unit -> frames.put(timeUs, unit) }
            }
            return bytes
        }

        /**
         * This stream became a blend's outgoing tail: the Atmos renderer follows
         * the current track (Android's tail player had no tap either).
         */
        fun followTaps(on: Boolean) {
            tapsStopped = !on
        }

        private fun run() {
            val dec = try {
                sourceOpener.open(item, floatOutput)
            } catch (e: Throwable) {
                if (!cancelled) error = e
                return
            }
            if (cancelled) { dec.close(); return }
            format = dec.outputFormat
            info = dec.info
            durationUs = dec.info.durationUs
            ring = PcmRing(ringCapacity(format))
            // The Atmos tap: E-AC-3 only, every other codec untouched.
            if (atmosFrames != null && dec.info.isEac3) {
                val q = AtmosTapQueue()
                dec.packetTap = { ptsUs, bytes -> if (ptsUs != C.TIME_UNSET) q.offer(ptsUs, bytes) }
                taps = q
            }
            try {
                if (startUs > 0) dec.seek(startUs)
                opened = true
                val chunk = ByteBuffer.allocateDirect((format.sampleRate / 20) * format.bytesPerFrame).order(ByteOrder.nativeOrder())
                var first = true
                var handled = seekHandled
                while (!cancelled) {
                    val requested = seekRequested
                    if (requested != handled) {
                        dec.seek(seekTargetUs)
                        // The render thread is not reading until it sees the
                        // acknowledgement below, so the producer may clear.
                        ring.clear()
                        taps?.clear()
                        ringStartUs = seekTargetUs
                        first = true
                        eof = false
                        handled = requested
                        seekHandled = requested
                    }
                    if (eof) { LockSupport.parkNanos(10_000_000); continue }
                    if (ring.free() < chunk.capacity()) { LockSupport.parkNanos(5_000_000); continue }
                    chunk.clear()
                    val n = dec.decode(chunk)
                    if (n == 0) { eof = true; continue }
                    if (first) {
                        val pts = dec.lastPtsUs
                        if (pts != C.TIME_UNSET) ringStartUs = pts
                        first = false
                    }
                    chunk.flip()
                    ring.write(chunk)
                }
            } catch (e: Throwable) {
                if (!cancelled) { Log.e(TAG, "decode failed for ${item.mediaId}", e); error = e }
                eof = true
            } finally {
                dec.close()
            }
        }

        /**
         * Stops the decode thread. Never waits for it: the thread may be inside
         * a network read that takes seconds to return, and this runs on the
         * render thread (a blend's tail closes mid-playback). The thread closes
         * its decoder itself on the way out.
         */
        fun close() {
            cancelled = true
            thread.interrupt()
        }
    }

    companion object {
        private const val TAG = "PlaybackEngine"
        /** The Android app's PlaybackService.RADIO_USER_AGENT, verbatim. */
        const val RADIO_USER_AGENT = "Tryptify/1.0 ( https://github.com/tryptz/tryptify )"
        /** Resume from a stall once this much is buffered again. */
        private const val MIN_BUFFER_US = 500_000L
        /** Longest the chain may take to give up its tail at the end of a track. */
        private const val DRAIN_TIMEOUT_NS = 2_000_000_000L
        /** Desktop: a live input's rate before any device has been opened. */
        const val DEFAULT_LIVE_RATE = 48_000
        /** Desktop: a live input is pulled in 10 ms blocks, so a deck answers its controls within one. */
        private const val LIVE_BLOCKS_PER_SECOND = 100
        /** Ten seconds of PCM per source, capped for very high rates and channel counts. */
        private fun ringCapacity(format: AudioFormat): Int =
            (format.sampleRate.toLong() * format.bytesPerFrame * 10).coerceAtMost(24L * 1024 * 1024).toInt()
    }
}
