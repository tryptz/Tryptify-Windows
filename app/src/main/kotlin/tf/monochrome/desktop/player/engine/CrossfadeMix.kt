package tf.monochrome.desktop.player.engine

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import tf.monochrome.desktop.player.CrossfadeRamp

/**
 * The arithmetic of a crossfade inside the engine: when a blend starts, how
 * long it runs, and the sum of the two tracks.
 *
 * On Android the outgoing track's tail played on a second ExoPlayer with its
 * own seeded DSP chain, the system mixer summed the two players, and both
 * volumes were stepped along [CrossfadeRamp]'s curves every 40 ms. The desktop
 * engine has one processor chain and one output, so the blend happens before
 * the chain instead: both tracks' PCM is summed frame by frame with the same
 * equal-power curves, and the chain processes the sum. One DSP state carries
 * across the join, nothing is processed twice, and the ramp is
 * sample-accurate rather than stepped.
 *
 * Timing follows [CrossfadeRamp]: the blend length is heard time, so at 1.5x a
 * six-second blend covers nine seconds of each track's media.
 *
 * Pure functions; the engine's render thread owns the blend's state.
 */
internal object CrossfadeMix {

    /** What an armed blend should do, given where the outgoing track is. */
    sealed interface Plan {
        /** The blend point is [frames] of the outgoing track away: read no further than that. */
        data class Wait(val frames: Long) : Plan

        /** Start now, running over [frames] frames of both tracks. */
        data class Start(val frames: Long) : Plan

        /** No blend: too little of the outgoing track is left, or it has no known end. */
        data object Skip : Plan
    }

    /**
     * Decides an armed blend at the outgoing track's [readPositionUs] (media
     * time of the next frame the engine would read).
     *
     * The blend begins one crossfade of heard time before the end, as on
     * Android. If the engine gets there late (the incoming track was still
     * opening, or a seek landed inside the last seconds) it begins as soon as
     * it can and runs over what is left, which keeps the blend landing on the
     * outgoing track's last sample; less than [CrossfadeRamp.MIN_BLEND_MS] of
     * it is a glitch rather than a fade, so that is skipped. A track that is
     * all blend at its speed is never blended (Android's rule), a stream with
     * no duration (a live station) has no end to blend at, and the blend never
     * outlasts an [incomingDurationUs] that is known.
     */
    fun plan(
        readPositionUs: Long,
        durationUs: Long,
        crossfadeMs: Long,
        speed: Float,
        sampleRate: Int,
        incomingDurationUs: Long,
    ): Plan {
        if (crossfadeMs <= 0L || durationUs <= 0L || sampleRate <= 0) return Plan.Skip
        if (CrossfadeRamp.heardMs(durationUs / 1000, speed) <= crossfadeMs) return Plan.Skip
        val blendUs = CrossfadeRamp.mediaMs(crossfadeMs, speed) * 1000
        val startUs = durationUs - blendUs
        if (readPositionUs < startUs) return Plan.Wait(framesCeil(startUs - readPositionUs, sampleRate))
        var lengthUs = minOf(durationUs - readPositionUs, blendUs)
        if (incomingDurationUs > 0L) lengthUs = minOf(lengthUs, incomingDurationUs)
        if (CrossfadeRamp.heardMs(lengthUs / 1000, speed) < CrossfadeRamp.MIN_BLEND_MS) return Plan.Skip
        return Plan.Start(lengthUs * sampleRate / C.MICROS_PER_SECOND)
    }

    /** Whole frames covering [us] at [sampleRate], rounded up so reading them reaches the instant. */
    fun framesCeil(us: Long, sampleRate: Int): Long =
        (us * sampleRate + C.MICROS_PER_SECOND - 1) / C.MICROS_PER_SECOND

    /**
     * Blends [frames] interleaved frames in place: `incoming × fadeIn +
     * outgoing × fadeOut`, written over [incoming].
     *
     * Frame `f` of the call is frame `startFrame + f` of a blend [totalFrames]
     * long, so the curves continue across calls; the first frame of a blend is
     * the outgoing track untouched and the incoming track is at full gain once
     * the blend is done. The outgoing track supplies only its first
     * [outgoingFrames] frames (it can end inside the blend when its stated
     * duration was generous); past those it is silence.
     */
    fun mix(
        incoming: FloatArray,
        outgoing: FloatArray,
        frames: Int,
        outgoingFrames: Int,
        channels: Int,
        startFrame: Long,
        totalFrames: Long,
    ) {
        for (f in 0 until frames) {
            val progress = CrossfadeRamp.progress(startFrame + f, totalFrames)
            val gainIn = CrossfadeRamp.fadeIn(progress)
            val base = f * channels
            if (f < outgoingFrames) {
                val gainOut = CrossfadeRamp.fadeOut(progress)
                for (c in base until base + channels) incoming[c] = incoming[c] * gainIn + outgoing[c] * gainOut
            } else {
                for (c in base until base + channels) incoming[c] = incoming[c] * gainIn
            }
        }
    }

    /**
     * Reads [samples] samples of 16-bit or float PCM (native order) from
     * [src]'s position, advancing it, as floats. 16-bit is scaled by 2^15, so
     * [fromFloat] gives every value back exactly.
     */
    fun toFloat(src: ByteBuffer, dst: FloatArray, samples: Int, encoding: Int) {
        val b = src.order(ByteOrder.nativeOrder())
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> for (i in 0 until samples) dst[i] = b.getFloat()
            C.ENCODING_PCM_16BIT -> for (i in 0 until samples) dst[i] = b.getShort() / 32768f
            else -> throw IllegalArgumentException("a blend mixes 16-bit or float PCM, not encoding $encoding")
        }
    }

    /**
     * Writes [samples] floats into [dst] at its position as [encoding], the
     * inverse of [toFloat]. 16-bit is rounded and clamped: a blend of two
     * full-scale tracks can sum past 1 for a moment.
     */
    fun fromFloat(src: FloatArray, samples: Int, dst: ByteBuffer, encoding: Int) {
        val b = dst.order(ByteOrder.nativeOrder())
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> for (i in 0 until samples) b.putFloat(src[i])
            C.ENCODING_PCM_16BIT -> for (i in 0 until samples) b.putShort(to16(src[i]))
            else -> throw IllegalArgumentException("a blend mixes 16-bit or float PCM, not encoding $encoding")
        }
    }

    /** One float sample as 16-bit: scaled by 2^15, rounded, clamped; NaN becomes 0. */
    fun to16(x: Float): Short = Math.round(x * 32768f).coerceIn(-32768, 32767).toShort()
}
