package tf.monochrome.desktop.dj

import androidx.media3.common.C
import tf.monochrome.desktop.domain.model.Track
import tf.monochrome.desktop.player.engine.FfmpegDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A track loaded on a deck: all of it, decoded into memory, because a deck
 * reads anywhere at any moment (cues, loops, scratching backwards) and a
 * decoder can only go forwards.
 *
 * Stereo, 16-bit with TPDF dither, at the track's own rate (the deck
 * resamples as it plays, so the original rate costs nothing extra), in chunks
 * of [CHUNK_FRAMES] so a long track never needs one huge array. Mono plays on
 * both sides; more than two channels fold the centre into left and right.
 *
 * One thread decodes ([decode]) while the audio thread plays from what is
 * already there: [frames] is published after the samples it covers are
 * written, so a reader that reads [frames] first sees them all. The
 * waveform overview and the beat analysis are built in the same pass.
 */
class DeckTrack(
    val track: Track?,
    val title: String,
    val artist: String,
    val sampleRate: Int,
    /** The stream's length as its container states it, frames; 0 when unknown. */
    val expectedFrames: Long,
    /** The most frames this track may hold: the memory budget. */
    private val maxFrames: Long = defaultMaxFrames(),
) {
    @Volatile private var chunks: Array<ShortArray?> = arrayOfNulls(chunksFor(expectedFrames.coerceAtLeast(CHUNK_FRAMES.toLong())))

    /** Frames decoded so far and safe to read. */
    @Volatile var frames: Long = 0L
        private set

    /** The whole stream is in. */
    @Volatile var complete: Boolean = false
        private set

    /** The stream was longer than the memory budget; it plays up to [frames]. */
    @Volatile var truncated: Boolean = false
        private set

    /** Why decoding stopped early, or null. */
    @Volatile var error: String? = null
        private set

    /** The beat grid: provisional after [PROVISIONAL_SECONDS], final once [complete]. */
    @Volatile var grid: BeatGrid? = null

    /** A person edited [grid]: the analysis no longer replaces it. */
    @Volatile var gridLocked: Boolean = false

    /** The first frame with sound in it (about -40 dBFS), or -1 until one is found. */
    @Volatile var firstSoundFrame: Long = -1L
        private set

    /** Length in frames for display: what is decoded once complete, the stated length before. */
    val lengthFrames: Long get() = if (complete || expectedFrames <= 0L) frames else maxOf(frames, expectedFrames)

    // ── Waveform overview ──────────────────────────────────────────────

    /** Frames per waveform bin. */
    val binFrames: Int = (sampleRate / WAVE_BINS_PER_SECOND).coerceAtLeast(1)
    @Volatile private var wave: Array<ByteArray> = Array(3) { ByteArray((expectedFrames / binFrames + 16).toInt().coerceIn(1024, Int.MAX_VALUE / 2)) }

    /** Waveform bins written so far. */
    @Volatile var waveBins: Int = 0
        private set

    /**
     * Peak level of band [band] (0 low, 1 mid, 2 high) in bin [bin], 0..1 on a
     * square-root scale; 0 past [waveBins].
     */
    fun wavePeak(band: Int, bin: Int): Float {
        if (bin < 0 || bin >= waveBins) return 0f
        return (wave[band][bin].toInt() and 0xFF) / 255f
    }

    // ── Reading (any thread) ───────────────────────────────────────────

    /** Sample of channel [ch] (0 left, 1 right) at [frame]; 0 outside what is decoded. */
    fun sample(frame: Long, ch: Int): Float {
        if (frame < 0L || frame >= frames) return 0f
        val chunk = chunks[(frame ushr CHUNK_SHIFT).toInt()] ?: return 0f
        return chunk[(((frame and CHUNK_MASK).toInt()) shl 1) + ch] * (1f / 32768f)
    }

    // ── Writing (the decode thread) ────────────────────────────────────

    private var writeChunk: ShortArray? = null
    private var writeFrames = 0L
    private var dither = 0x2545F491
    // One split per side, not one of the mono sum: out-of-phase material would cancel in the sum and draw too small.
    private val splitL = WaveSplit(sampleRate)
    private val splitR = WaveSplit(sampleRate)
    private val peaks = FloatArray(3)
    private var binFill = 0

    private fun append(l: Float, r: Float) {
        val inChunk = (writeFrames and CHUNK_MASK).toInt()
        if (inChunk == 0) {
            val index = (writeFrames ushr CHUNK_SHIFT).toInt()
            var arr = chunks
            if (index >= arr.size) {
                arr = arr.copyOf(maxOf(arr.size * 2, index + 1))
                chunks = arr
            }
            val c = ShortArray(CHUNK_FRAMES * 2)
            arr[index] = c
            writeChunk = c
        }
        val c = writeChunk!!
        c[inChunk shl 1] = quantize(l)
        c[(inChunk shl 1) + 1] = quantize(r)
        writeFrames++

        splitL.process(l, peaks)
        splitR.process(r, peaks)
        if (++binFill == binFrames) {
            var w = wave
            if (waveBins >= w[0].size) {
                w = Array(3) { w[it].copyOf(w[it].size * 2) }
                wave = w
            }
            for (b in 0..2) {
                w[b][waveBins] = (sqrt(peaks[b].coerceIn(0f, 1f)) * 255f).toInt().toByte()
                peaks[b] = 0f
            }
            binFill = 0
            waveBins++
        }
    }

    private fun quantize(x: Float): Short {
        // TPDF dither: the sum of two uniform values, one step wide each.
        dither = dither xor (dither shl 13); dither = dither xor (dither ushr 17); dither = dither xor (dither shl 5)
        val a = (dither and 0xFFFF) / 65536f
        val b = ((dither ushr 16) and 0xFFFF) / 65536f
        val v = x * 32767f + (a - b)
        return Math.round(v).coerceIn(-32768, 32767).toShort()
    }

    /**
     * Decodes all of [decoder] into this track, feeding [analyzer] as it
     * goes. Returns when the stream ends, the budget is reached, [cancelled]
     * turns true, or the decoder fails. [onProgress] runs now and then on
     * this thread (after each grid update, too).
     */
    fun decode(decoder: FfmpegDecoder, analyzer: BeatAnalyzer, cancelled: () -> Boolean, onProgress: () -> Unit = {}) {
        val fmt = decoder.outputFormat
        val channels = fmt.channelCount.coerceAtLeast(1)
        val isFloat = fmt.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val buf = ByteBuffer.allocateDirect(DECODE_FRAMES * channels * bytesPerSample).order(ByteOrder.nativeOrder())
        val mono = FloatArray(DECODE_FRAMES)
        var provisionalDone = false
        var lastPublish = 0L
        try {
            while (!cancelled()) {
                buf.clear()
                val n = decoder.decode(buf)
                if (n <= 0) break
                for (i in 0 until n) {
                    val base = i * channels
                    fun s(c: Int): Float =
                        if (isFloat) buf.getFloat((base + c) * 4) else buf.getShort((base + c) * 2) / 32768f
                    val l: Float
                    val r: Float
                    when {
                        channels == 1 -> { l = s(0); r = l }
                        channels == 2 -> { l = s(0); r = s(1) }
                        else -> {
                            // L, R, C first in every layout FFmpeg decodes to.
                            val c = s(2) * CENTRE_GAIN
                            l = (s(0) + c) * FOLD_NORM
                            r = (s(1) + c) * FOLD_NORM
                        }
                    }
                    append(l, r)
                    mono[i] = (l + r) * 0.5f
                }
                analyzer.feed(mono, n)
                if (firstSoundFrame < 0) firstSoundFrame = analyzer.firstSoundFrame
                frames = writeFrames
                if (!provisionalDone && analyzer.seconds >= PROVISIONAL_SECONDS) {
                    provisionalDone = true
                    analyzer.analyze()?.let { if (!gridLocked) grid = it }
                    onProgress()
                } else if (writeFrames - lastPublish >= sampleRate * PROGRESS_SECONDS) {
                    lastPublish = writeFrames
                    onProgress()
                }
                if (writeFrames + DECODE_FRAMES > maxFrames) {
                    truncated = true
                    break
                }
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        frames = writeFrames
        if (!cancelled()) {
            analyzer.analyze()?.let { if (!gridLocked) grid = it }
            complete = true
        }
        onProgress()
    }

    /** One-pole band split for the waveform's colours: lows under ~200 Hz, highs over ~2.5 kHz. */
    private class WaveSplit(sampleRate: Int) {
        private val aLow = 1f - exp(-2f * Math.PI.toFloat() * 200f / sampleRate)
        private val aHigh = 1f - exp(-2f * Math.PI.toFloat() * 2_500f / sampleRate)
        private var low = 0f
        private var belowHigh = 0f

        fun process(x: Float, peaks: FloatArray) {
            low += aLow * (x - low)
            belowHigh += aHigh * (x - belowHigh)
            val high = x - belowHigh
            val mid = belowHigh - low
            peaks[0] = maxOf(peaks[0], abs(low))
            peaks[1] = maxOf(peaks[1], abs(mid))
            peaks[2] = maxOf(peaks[2], abs(high))
        }
    }

    companion object {
        const val CHUNK_SHIFT = 16
        const val CHUNK_FRAMES = 1 shl CHUNK_SHIFT
        private const val CHUNK_MASK = (CHUNK_FRAMES - 1).toLong()
        const val WAVE_BINS_PER_SECOND = 100
        private const val DECODE_FRAMES = 4096
        private const val CENTRE_GAIN = 0.70710678f
        private const val FOLD_NORM = 1f / (1f + CENTRE_GAIN)
        const val PROVISIONAL_SECONDS = 30.0
        private const val PROGRESS_SECONDS = 2

        private fun chunksFor(frames: Long): Int = ((frames + CHUNK_FRAMES - 1) / CHUNK_FRAMES).toInt().coerceAtLeast(1)

        /** A fifth of the heap per track, at 4 bytes a frame: about 35 minutes at 48 kHz with the 2 GB heap. */
        fun defaultMaxFrames(): Long = Runtime.getRuntime().maxMemory() / 5 / 4

        /** A track from samples already in memory (tests, and the click a deck plays to check its output). */
        fun fromSamples(left: FloatArray, right: FloatArray, sampleRate: Int, title: String = ""): DeckTrack {
            val t = DeckTrack(null, title, "", sampleRate, left.size.toLong())
            for (i in left.indices) t.append(left[i], right[i])
            t.frames = t.writeFrames
            t.complete = true
            return t
        }
    }
}
