package tf.monochrome.desktop.audio.resample

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import tf.monochrome.desktop.audio.stretch.StretchAudioProcessor
import tf.monochrome.desktop.audio.usb.AudioProcessorChain
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The engine's processor chain: the app's own processors in their existing
 * order, then the three transport stages, and the routing that sends the two
 * halves of a speed change to whichever stage can actually do them.
 *
 * On Android this implemented Media3's `AudioProcessorChain` for
 * `DefaultAudioSink`, which built the chain, drove it and asked it about
 * playback parameters. The desktop engine owns its sink, so this is a plain
 * class that owns the ordered [processors] and drives them through the
 * hand-driven [AudioProcessorChain] (the same driver the Android USB bypass
 * used), with the stage order of playback-architecture §2.4:
 *
 * ```
 * appProcessors…, VariRate, Stretch, FloatSonic
 * ```
 *
 * The transport stages stay last so the rest of the app keeps the ordering it
 * is written against: AutoEQ runs upstream of the pitch change, so its
 * pre-warp is still both necessary and correct.
 *
 * Routing. One Sonic instance splits a speed change as `s = speed / pitch` and
 * `r = rate * pitch`:
 *
 *  - **Preserve-pitch on** (`pitch == 1`): `s == speed`, so Sonic time-stretches
 *    with its WSOLA path and `r == 1`, no resampling. That goes to
 *    [FloatSonicAudioProcessor] -- the app's own Sonic, which also takes float,
 *    so hi-res streams change speed at their own resolution, and applies the
 *    change live rather than on the next flush.
 *  - **Pitch riding the tempo** (`pitch == speed`): `s` is exactly 1.0, inside
 *    Sonic's `0.99999..1.00001` dead zone, so *no* stretch runs and the entire
 *    effect would be `adjustRate` -- two-point linear interpolation. That case
 *    goes to [VariRateAudioProcessor]'s windowed-sinc kernel instead.
 *
 * Only one of the two is ever active: the other is left at unity, which makes
 * `isActive()` false for the resampler and drops it from the chain; FloatSonic,
 * once engaged, stays as an exact pass-through (see its KDoc for why).
 *
 * Membership. The driver fixes which stages are in the chain at [configure],
 * so a stage whose activity tracks a live control has to be pulled in when
 * that control moves. [applyPlaybackParameters] marks the membership dirty and
 * the next [process] on the render thread re-evaluates it, flushing only the
 * stage that joined -- the same sequencing the Android bypass sink used, and
 * the thing that made speed changes audible on a track configured at 1.00x.
 *
 * Desktop: Media3's `SilenceSkippingAudioProcessor` is not carried over. The
 * app never enabled it (`applySkipSilenceEnabled` was reachable only through
 * `Player.setSkipSilenceEnabled`, which nothing called), so it only ever
 * passed audio through; `getSkippedOutputFrameCount` went with it, having
 * been 0 forever.
 */
@OptIn(UnstableApi::class)
class TryptifyAudioProcessorChain(
    appProcessors: List<AudioProcessor>,
    private val resampler: VariRateAudioProcessor,
    private val stretch: StretchAudioProcessor,
    /**
     * Time-stretching with pitch preserved. One instance may be shared with a
     * crossfade tail's chain, as Android shared it between its two paths; only
     * one chain is configured and draining it at a time.
     */
    private val timeStretch: FloatSonicAudioProcessor = FloatSonicAudioProcessor(),
    /** Where membership changes are logged; a no-op in JVM tests. */
    log: (String) -> Unit = { Log.i(TAG, it) },
) {

    /** The Android call sites built the app processors as an array; both shapes are taken. */
    constructor(
        appProcessors: Array<AudioProcessor>,
        resampler: VariRateAudioProcessor,
        stretch: StretchAudioProcessor,
        timeStretch: FloatSonicAudioProcessor = FloatSonicAudioProcessor(),
    ) : this(appProcessors.toList(), resampler, stretch, timeStretch)

    /** Every stage in chain order: the app's processors, then resampler, transposer, time-stretch. */
    val processors: List<AudioProcessor> = appProcessors + listOf<AudioProcessor>(resampler, stretch, timeStretch)

    /** Media3-shaped accessor, for code ported from the Android call sites. */
    fun getAudioProcessors(): Array<AudioProcessor> = processors.toTypedArray()

    private val pipeline = AudioProcessorChain(processors, log)

    @Volatile private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT

    /**
     * Set after the stage controls move, cleared by the render thread just
     * before it re-evaluates membership. Written in that order on both sides,
     * so a change that lands between the clear and the re-evaluation is seen
     * by the re-evaluation or triggers the next one; it is never lost.
     */
    @Volatile private var membershipDirty = false

    // ── transport ────────────────────────────────────────────────────────

    /**
     * Routes [parameters] to the stage that can do them and returns the
     * parameters actually applied (all of them: nothing is clamped here).
     * Safe to call from a control thread while the render thread is in
     * [process]: the resampler designs its kernel on this thread and swaps it
     * in atomically, FloatSonic's setters are plain field writes it reads per
     * block, and the chain's membership is re-evaluated on the render thread.
     */
    fun applyPlaybackParameters(parameters: PlaybackParameters): PlaybackParameters {
        val speed = parameters.speed
        val pitch = parameters.pitch
        val ridesTempo = abs(pitch - speed) < TOLERANCE && abs(speed - 1f) >= TOLERANCE
        if (ridesTempo) {
            resampler.setRatio(speed)
            timeStretch.setSpeed(1f)
            timeStretch.setPitch(1f)
        } else {
            resampler.setRatio(1f)
            timeStretch.setSpeed(speed)
            timeStretch.setPitch(pitch)
        }
        playbackParameters = parameters
        membershipDirty = true
        return parameters
    }

    fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    /**
     * Media time consumed by [playoutDurationUs] of output.
     *
     * Resampling by `ratio` consumes exactly `ratio` input frames per output
     * frame, so the mapping is the plain product. When FloatSonic is the active
     * stage the nominal speed stands in for Sonic's measured in/out ratio
     * (FloatSonic keeps no running count); the two agree to within a pitch
     * period at steady state, and the nominal speed is what Sonic itself
     * answers until it has seen enough output to measure. A pitch change with
     * the tempo preserved moves no frames, and the transposer is one-in-one-out,
     * so neither enters here.
     */
    fun getMediaDuration(playoutDurationUs: Long): Long {
        val ratio = resampler.getRatio()
        if (abs(ratio - 1f) >= TOLERANCE) return (playoutDurationUs * ratio.toDouble()).roundToLong()
        val speed = playbackParameters.speed
        return if (abs(speed - 1f) >= TOLERANCE) (playoutDurationUs * speed.toDouble()).roundToLong() else playoutDurationUs
    }

    // ── the driver, for the engine's render thread ───────────────────────

    /** Configures every stage for [input] and returns the chain's output format. */
    fun configure(input: AudioFormat): AudioFormat {
        membershipDirty = false
        return pipeline.configure(input)
    }

    /**
     * Walks [input] through every active stage and returns the final buffer,
     * owned by the last stage and valid until the next call; see
     * [AudioProcessorChain.process]. Pulls in any stage whose control moved
     * since the last call first.
     */
    fun process(input: ByteBuffer): ByteBuffer {
        if (membershipDirty) {
            membershipDirty = false
            pipeline.refreshActive()
        }
        return pipeline.process(input)
    }

    /** The input has ended: call [process] with an empty buffer until [isEnded]. */
    fun queueEndOfStream() = pipeline.queueEndOfStream()

    fun isEnded(): Boolean = pipeline.isEnded()

    /** Re-evaluates membership now; for callers already on the render thread. */
    fun refreshActive() {
        membershipDirty = false
        pipeline.refreshActive()
    }

    fun flush() = pipeline.flush()

    fun reset() = pipeline.reset()

    fun outputFormat(): AudioFormat = pipeline.outputFormat()

    fun anyActive(): Boolean = pipeline.anyActive()

    private companion object {
        const val TAG = "TryptifyAudioProcessorChain"

        /** Sonic's own threshold for treating a factor as unity. */
        const val TOLERANCE = 1e-4f
    }
}
