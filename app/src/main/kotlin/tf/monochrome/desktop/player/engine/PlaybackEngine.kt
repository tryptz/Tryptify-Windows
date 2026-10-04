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
import tf.monochrome.desktop.audio.sink.AudioSink
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
 *    the sink's format with the volume applied and writes with back-pressure;
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
 * Position = the source's start position plus the device clock since the
 * first write, mapped through [SpeedTimeline] when the speed is not 1.
 */
class PlaybackEngine(
    private val processors: List<AudioProcessor>,
    private var sinkFactory: () -> AudioSink,
    private val callbackExecutor: Executor = Executor { SwingUtilities.invokeLater(it) },
) : Player {

    /** How a MediaItem's uri is turned into bytes: the resolver's DataSource routing, or null for libavformat itself. */
    fun interface SourceOpener {
        fun open(item: MediaItem): FfmpegDecoder
    }

    /** Default opener: file and http(s) URIs straight into libavformat. */
    var sourceOpener: SourceOpener = SourceOpener { item ->
        val uri = item.localConfiguration?.uri ?: throw IllegalArgumentException("MediaItem without a uri: $item")
        // toFile, not uri.path: on Windows the path of file:///C:/x is "/C:/x".
        val target = if (uri.scheme == "file") uri.toFile().absolutePath else uri.toString()
        FfmpegDecoder.open(target, userAgent = userAgent)
    }

    /** Opener for items whose scheme needs one of the app's DataSources (qobuz://, deezer://, ...). */
    fun dataSourceOpener(factory: DataSource.Factory): SourceOpener = SourceOpener { item ->
        val uri = item.localConfiguration?.uri ?: throw IllegalArgumentException("MediaItem without a uri: $item")
        FfmpegDecoder.open(factory.createDataSource(), uri)
    }

    /**
     * Sent on every direct network open. The Android app's radio agent, kept
     * as it was: a fair number of Icecast servers answer a generic agent with
     * a 403, and this one is known to get through.
     */
    var userAgent: String = RADIO_USER_AGENT

    // ── Observable state (volatile, read from any thread) ────────────────────
    @Volatile private var _playbackState = Player.STATE_IDLE
    @Volatile private var _playWhenReady = false
    @Volatile private var _isPlaying = false
    @Volatile private var _currentItem: MediaItem? = null
    @Volatile private var _duration = C.TIME_UNSET
    @Volatile private var _volume = 1f
    @Volatile private var _playbackParameters = PlaybackParameters.DEFAULT
    @Volatile private var _error: PlaybackException? = null
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
        class SetSink(val factory: () -> AudioSink) : Command
        class SetSpeed(val parameters: PlaybackParameters) : Command
        object Release : Command
    }

    private val commands = ConcurrentLinkedQueue<Command>()
    private val renderThread = Thread(::renderLoop, "tryptify-render").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }
    @Volatile private var alive = true

    private var chain = AudioProcessorChain(processors) { Log.d(TAG, it) }
    private var sink: AudioSink? = null
    private var packer: PcmPacker? = null
    private var chainConfigured = false
    private var current: Source? = null
    private var next: Source? = null
    private var pendingProcessed: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputBuf: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var framesWritten = 0L
    private var positionBaseUs = C.TIME_UNSET
    private var playedBaseFrames = 0L
    private var transitionAtFrames = -1L
    private var transitionSource: Source? = null
    private val timeline = SpeedTimeline()
    private var draining = false

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
    override fun seekToNext() {}
    override fun seekToPrevious() { seekTo(0) }
    override fun seekToNextMediaItem() {}
    override fun seekToPreviousMediaItem() { seekTo(0) }
    override fun hasNextMediaItem(): Boolean = next != null
    override fun hasPreviousMediaItem(): Boolean = false
    override fun clearMediaItems() = stop()

    /** Gapless pre-roll of the following track; null drops it. */
    fun preloadNext(item: MediaItem?) { commands.add(Command.Preload(item)) }

    /** Switches output (WASAPI shared/exclusive, Java Sound, libusb); takes effect at the next configure. */
    fun setSink(factory: () -> AudioSink) { commands.add(Command.SetSink(factory)) }

    /** Media time, microseconds. Cheap: reads the device clock. */
    fun positionUs(): Long {
        val base = positionBaseUs
        if (base == C.TIME_UNSET) return current?.startUs ?: 0L
        val s = sink ?: return base
        val played = (s.playedFrames() - playedBaseFrames).coerceAtLeast(0)
        val rate = sinkFormat?.sampleRate ?: return base
        val playedUs = played * C.MICROS_PER_SECOND / rate
        val media = base + timeline.mediaAt(playedUs)
        val dur = _duration
        return if (dur > 0) media.coerceAtMost(dur * 1000) else media
    }

    // ── Render thread ────────────────────────────────────────────────────────
    private fun renderLoop() {
        while (alive) {
            try {
                val cmd = commands.poll()
                if (cmd != null) { handle(cmd); continue }
                val src = current
                if (src == null || !_playWhenReady || _playbackState == Player.STATE_IDLE || _playbackState == Player.STATE_ENDED) {
                    LockSupport.parkNanos(5_000_000)
                    continue
                }
                step(src)
            } catch (e: Throwable) {
                Log.e(TAG, "render loop error", e)
                fail(PlaybackException(e.message, e, if (e is SinkException) PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED else PlaybackException.ERROR_CODE_UNSPECIFIED))
            }
        }
        closeSources()
        sink?.release()
    }

    private fun handle(cmd: Command) {
        when (cmd) {
            is Command.SetItem -> openItem(cmd.item, cmd.startMs * 1000)
            Command.Play -> {
                _playWhenReady = true
                _error = null
                if (_playbackState == Player.STATE_ENDED) { current?.let { openItem(it.item, 0) } }
                sink?.play()
                updateIsPlaying()
                notify { it.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
            }
            Command.Pause -> {
                _playWhenReady = false
                sink?.pause()
                updateIsPlaying()
                notify { it.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
            }
            Command.Stop -> stopInternal()
            is Command.Seek -> seekInternal(cmd.positionMs * 1000)
            is Command.Preload -> preloadInternal(cmd.item)
            is Command.SetSink -> {
                sinkFactory = cmd.factory
                sink?.release(); sink = null
                chainConfigured = false
                pendingProcessed = AudioProcessor.EMPTY_BUFFER
                // Re-anchor the clock on the new device from the current position.
                current?.let { seekInternal(positionUs()) }
            }
            is Command.SetSpeed -> {
                _playbackParameters = cmd.parameters
                val rate = sinkFormat?.sampleRate
                if (rate != null) timeline.setFactor(framesWritten * C.MICROS_PER_SECOND / rate, cmd.parameters.speed.toDouble())
                notify { it.onPlaybackParametersChanged(cmd.parameters) }
            }
            Command.Release -> alive = false
        }
    }

    private fun openItem(item: MediaItem, startUs: Long) {
        val reuse = next?.takeIf { it.item.mediaId == item.mediaId && it.item.localConfiguration?.uri == item.localConfiguration?.uri && startUs == 0L }
        closeSources(keep = reuse)
        next = null
        pendingProcessed = AudioProcessor.EMPTY_BUFFER
        transitionAtFrames = -1; transitionSource = null
        draining = false
        _currentItem = item
        _duration = C.TIME_UNSET
        _error = null
        val src = reuse ?: Source(item, startUs)
        current = src
        positionBaseUs = C.TIME_UNSET
        if (reuse == null) src.start()
        sink?.flush()
        chain.flush()
        setState(Player.STATE_BUFFERING)
        notify { it.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
        notify { it.onMediaMetadataChanged(item.mediaMetadata) }
    }

    private fun preloadInternal(item: MediaItem?) {
        if (item == null) { next?.close(); next = null; return }
        val existing = next
        if (existing != null && existing.item.mediaId == item.mediaId) return
        existing?.close()
        next = Source(item, 0L).also { it.start() }
    }

    private fun stopInternal() {
        closeSources()
        pendingProcessed = AudioProcessor.EMPTY_BUFFER
        sink?.flush()
        chain.flush()
        positionBaseUs = C.TIME_UNSET
        _currentItem = null
        _duration = C.TIME_UNSET
        setState(Player.STATE_IDLE)
        updateIsPlaying()
    }

    private fun seekInternal(positionUs: Long) {
        val src = current ?: return
        val wasPlaying = _isPlaying
        val target = positionUs.coerceAtLeast(0)
        src.requestSeek(target)
        pendingProcessed = AudioProcessor.EMPTY_BUFFER
        sink?.flush()
        chain.flush()
        timeline.clear()
        framesWritten = 0
        positionBaseUs = C.TIME_UNSET
        transitionAtFrames = -1; transitionSource = null
        draining = false
        setState(Player.STATE_BUFFERING)
        val info = Player.PositionInfo(src.item, 0, target / 1000)
        notify { it.onPositionDiscontinuity(info, info, Player.DISCONTINUITY_REASON_SEEK) }
        if (wasPlaying) sink?.play()
    }

    private fun ensureConfigured(src: Source) {
        if (chainConfigured && audioFormat == src.format) return
        val out = chain.configure(src.format)
        audioFormat = src.format
        val s = sink ?: sinkFactory().also { sink = it }
        val negotiated = try {
            s.configure(out)
        } catch (e: SinkException) {
            Log.w(TAG, "sink refused ${out}; falling back to Java Sound", e)
            sink?.release()
            tf.monochrome.desktop.audio.sink.JavaSoundSink().also { sink = it }.configure(out)
        }
        sinkFormat = negotiated
        packer = PcmPacker(out, negotiated)
        val chunkBytes = (src.format.sampleRate / 50) * src.format.bytesPerFrame   // 20 ms of input
        if (inputBuf.capacity() < chunkBytes) inputBuf = ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.nativeOrder())
        chainConfigured = true
        timeline.reset(0, _playbackParameters.speed.toDouble())
        framesWritten = 0
        if (_playWhenReady) sink?.play()
        Log.i(TAG, "configured chain in=${src.format} out=$out sink=$negotiated (${s.javaClass.simpleName}${if (s.isExclusive) " exclusive" else ""})")
    }

    private fun step(src: Source) {
        if (!src.opened) {
            src.error?.let { fail(PlaybackException("cannot open ${src.item.localConfiguration?.uri}", it, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)); return }
            LockSupport.parkNanos(5_000_000)
            return
        }
        if (_duration == C.TIME_UNSET && src.durationUs > 0) {
            _duration = src.durationUs / 1000
            streamInfo = src.info
        }
        ensureConfigured(src)
        checkTransition()

        if (pendingProcessed.hasRemaining()) {
            writeOut(pendingProcessed)
            if (pendingProcessed.hasRemaining()) { LockSupport.parkNanos(2_000_000); return }
        }

        if (draining) { drainStep(src); return }

        val bytesPerFrame = src.format.bytesPerFrame
        if (src.ring.available() < bytesPerFrame) {
            if (src.eof) { onSourceExhausted(src); return }
            if (src.error != null) { fail(PlaybackException("decode failed", src.error, PlaybackException.ERROR_CODE_DECODING_FAILED)); return }
            if (_playbackState == Player.STATE_READY && !src.seeking) setState(Player.STATE_BUFFERING)
            LockSupport.parkNanos(5_000_000)
            return
        }
        if (_playbackState == Player.STATE_BUFFERING) {
            if (!src.eof && src.bufferedUs() < MIN_BUFFER_US) { LockSupport.parkNanos(5_000_000); return }
            setState(Player.STATE_READY)
            updateIsPlaying()
        }
        inputBuf.clear()
        val bytes = src.ring.read(inputBuf, inputBuf.capacity() / bytesPerFrame * bytesPerFrame)
        inputBuf.flip()
        if (bytes == 0) return
        if (positionBaseUs == C.TIME_UNSET && transitionAtFrames < 0) {
            positionBaseUs = src.readStartUs()
            playedBaseFrames = sink?.playedFrames() ?: 0L
            timeline.reset(0, _playbackParameters.speed.toDouble())
            framesWritten = 0
        }
        // Membership is re-read every block, not only at configure: stages whose
        // activity follows a live control (VariRate's ratio, the stretch stage's
        // semitones) would otherwise stay out of a chain configured while they
        // were idle. Android got this from DefaultAudioSink re-flushing on a
        // parameter change; nothing re-flushes this chain.
        chain.refreshActive()
        val processed = if (chain.anyActive()) chain.process(inputBuf) else inputBuf
        if (processed.hasRemaining()) {
            pendingProcessed = processed
            writeOut(processed)
        }
    }

    /** The current ring ran dry at end of stream: splice to the next track or drain. */
    private fun onSourceExhausted(src: Source) {
        val n = next
        if (n != null && n.opened && n.format == src.format && n.error == null) {
            // Seamless: keep the chain running, swap rings, report the transition when heard.
            Log.i(TAG, "gapless splice to ${n.item.mediaId}")
            transitionAtFrames = framesWritten
            transitionSource = n
            current = n
            next = null
            src.close()
            return
        }
        draining = true
        chain.queueEndOfStream()
    }

    private fun drainStep(src: Source) {
        if (!chain.isEnded() && chain.anyActive()) {
            val empty = AudioProcessor.EMPTY_BUFFER
            val out = chain.process(empty)
            if (out.hasRemaining()) { pendingProcessed = out; writeOut(out); return }
            if (!chain.isEnded()) return
        }
        val s = sink
        s?.drain()
        if (s != null && s.pendingFrames() > 0 && _playWhenReady) { LockSupport.parkNanos(5_000_000); return }
        draining = false
        val n = next
        if (n != null && n.error == null) {
            // Different format: reconfigure and continue.
            Log.i(TAG, "format change into ${n.item.mediaId}: ${src.format} -> ${n.format}")
            src.close()
            current = n
            next = null
            chainConfigured = false
            positionBaseUs = C.TIME_UNSET
            _currentItem = n.item
            _duration = C.TIME_UNSET
            setState(Player.STATE_BUFFERING)
            notify { it.onMediaItemTransition(n.item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
            notify { it.onMediaMetadataChanged(n.item.mediaMetadata) }
            return
        }
        setState(Player.STATE_ENDED)
        updateIsPlaying()
    }

    private fun checkTransition() {
        val at = transitionAtFrames
        val n = transitionSource ?: return
        if (at < 0) return
        val s = sink ?: return
        if (s.playedFrames() < at) return
        transitionAtFrames = -1
        transitionSource = null
        positionBaseUs = n.readStartUs()
        playedBaseFrames = at
        timeline.reset(0, _playbackParameters.speed.toDouble())
        framesWritten = 0
        _currentItem = n.item
        _duration = if (n.durationUs > 0) n.durationUs / 1000 else C.TIME_UNSET
        streamInfo = n.info
        notify { it.onMediaItemTransition(n.item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
        notify { it.onMediaMetadataChanged(n.item.mediaMetadata) }
    }

    /** Writes as much of [buf] as the sink takes; advances [buf] by what was accepted. */
    private fun writeOut(buf: ByteBuffer) {
        val s = sink ?: return
        val p = packer ?: return
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
        closeSources()
        pendingProcessed = AudioProcessor.EMPTY_BUFFER
        sink?.flush()
        chain.flush()
        setState(Player.STATE_IDLE)
        updateIsPlaying()
        notify { it.onPlayerError(error) }
    }

    private fun closeSources(keep: Source? = null) {
        current?.takeIf { it !== keep }?.close()
        next?.takeIf { it !== keep }?.close()
        current = null; next = null
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
    private inner class Source(val item: MediaItem, val startUs: Long) {
        @Volatile var opened = false
        @Volatile var eof = false
        @Volatile var error: Throwable? = null
        @Volatile var seeking = false
        @Volatile private var cancelled = false
        @Volatile private var seekRequestUs = C.TIME_UNSET
        @Volatile private var ringStartUs = startUs
        lateinit var format: AudioFormat
        lateinit var ring: PcmRing
        var info: DecodedStreamInfo? = null
        var durationUs = C.TIME_UNSET
        private var decoder: FfmpegDecoder? = null
        private val thread = Thread(::run, "tryptify-decode").apply { isDaemon = true }

        fun start() = thread.start()

        /** Media position of the first byte now in the ring. */
        fun readStartUs(): Long = ringStartUs

        fun bufferedUs(): Long {
            if (!opened) return 0
            return ring.available() / format.bytesPerFrame * C.MICROS_PER_SECOND / format.sampleRate
        }

        fun requestSeek(positionUs: Long) {
            seeking = true
            seekRequestUs = positionUs
            // The consumer is the render thread itself, so clearing here is safe.
            if (opened) ring.clear()
            eof = false
        }

        private fun run() {
            val dec = try {
                sourceOpener.open(item)
            } catch (e: Throwable) {
                error = e
                return
            }
            decoder = dec
            format = dec.outputFormat
            info = dec.info
            durationUs = dec.info.durationUs
            ring = PcmRing(ringCapacity(format))
            try {
                if (startUs > 0) dec.seek(startUs)
                opened = true
                val chunk = ByteBuffer.allocateDirect((format.sampleRate / 20) * format.bytesPerFrame).order(ByteOrder.nativeOrder())
                var first = true
                while (!cancelled) {
                    val seekTo = seekRequestUs
                    if (seekTo != C.TIME_UNSET) {
                        seekRequestUs = C.TIME_UNSET
                        dec.seek(seekTo)
                        ring.clear()
                        ringStartUs = seekTo
                        first = true
                        eof = false
                        seeking = false
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

        fun close() {
            cancelled = true
            thread.interrupt()
            try { thread.join(1000) } catch (_: InterruptedException) {}
        }
    }

    companion object {
        private const val TAG = "PlaybackEngine"
        /** The Android app's PlaybackService.RADIO_USER_AGENT, verbatim. */
        const val RADIO_USER_AGENT = "Tryptify/1.0 ( https://github.com/tryptz/tryptify )"
        /** Resume from a stall once this much is buffered again. */
        private const val MIN_BUFFER_US = 500_000L
        /** Ten seconds of PCM per source, capped for very high rates and channel counts. */
        private fun ringCapacity(format: AudioFormat): Int =
            (format.sampleRate.toLong() * format.bytesPerFrame * 10).coerceAtMost(24L * 1024 * 1024).toInt()
    }
}
