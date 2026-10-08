package tf.monochrome.desktop.audio.sink

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import tf.monochrome.desktop.audio.usb.GainRamp
import tf.monochrome.desktop.audio.usb.LibusbUacDriver
import tf.monochrome.desktop.audio.usb.LibusbUacNative
import tf.monochrome.desktop.audio.usb.StartError
import tf.monochrome.desktop.audio.usb.StartFailure
import tf.monochrome.desktop.audio.usb.UacPcm

/**
 * Exclusive output to a USB Audio Class DAC through libusb: the bypass half of
 * the Android app's `LibusbAudioSink`, as a desktop [AudioSink].
 *
 * The DAC is driven at the track's own rate and depth, past every operating
 * system mixer. [configure] asks the DAC for the chain's rate exactly and walks
 * a bit-depth ladder ([UacPcm.bitDepthLadder]); nothing is resampled and
 * nothing is dithered, and when the DAC has no alternate setting at that rate
 * it throws [SinkException] so the caller ([FallbackAudioSink]) moves the
 * track to the listener's own output instead. The answer it gives is the
 * chain's format unchanged: the engine's packer then only applies the volume
 * (and at unity hands the chain's buffer straight through), and this sink
 * packs into the DAC's subslots itself, where the subslot size is known and
 * the arithmetic can be exact ([UacPcm]).
 *
 * What carried over from Android, and what did not:
 *  - the depth ladder, reuse of a running stream of the same format, and
 *    asking the driver for the subslot it negotiated instead of assuming
 *    bits / 8 (a 24-bit alternate setting may use 4-byte subslots);
 *  - back-pressure: [write] takes only what the driver's ring has room for and
 *    reports exactly that, so the engine keeps the rest;
 *  - the clock: [playedFrames] is what the iso pump has taken from the ring,
 *    the DAC's own pace, because writes run far ahead of real time;
 *  - the "driver still ours" check and the 400 ms wedge watchdog, which now
 *    call [onLost] so the controller can put the output somewhere that works
 *    rather than the sink swapping devices under the engine's clock.
 *
 * Desktop: dropped with Media3. The three delegate modes, the hi-res float
 * path and its `SpeedTimeline` mapping (the engine owns one chain and one
 * clock), the processor chain itself (the engine runs it before any sink),
 * playback-parameter plumbing (speed lives in the chain), lazy engage (the
 * controller re-routes when a DAC opens), the player's half of
 * `BypassVolumeController` (the engine applies the player volume in its
 * packer; applying both would square it, so this applies only the DAC level,
 * [dacGain]), the crossfade tail mix and its idle pump (the engine mixes a crossfade
 * before the chain), and pause-by-flush: [pause] now holds the stream with
 * its queue intact (see [LibusbUacNative]).
 */
class LibusbUacSink(
    private val driver: LibusbUacDriver,
    /**
     * Called once, from the render thread, when the driver stops carrying this
     * sink's audio (DAC unplugged, toggle off, pump wedged). Must not block.
     */
    private val onLost: (reason: String) -> Unit = {},
    /**
     * Called when [configure] turns a format down, with the driver's reason
     * captured before anything clears it: releasing this sink stops the
     * driver, and the driver's stop() resets its own lastStartError, so a
     * collector of that flow could see the failure vanish before it ran.
     */
    private val onRefused: (failure: StartFailure?) -> Unit = {},
    /**
     * The DAC level as linear gain (BypassVolumeController.getDacGain), read
     * every write. The engine's packer has already applied the player volume.
     */
    private val dacGain: () -> Float = { 1f },
) : AudioSink {

    private var format: AudioFormat? = null
    private var sourceBytesPerFrame = 0
    private var channels = 0
    private var validBits = 0
    private var subslotBytes = 0
    private var playing = false
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0)

    // The gain the DAC is getting now, moved a frame at a time toward the
    // level (see GainRamp). Zero whenever the DAC starts from silence, so
    // every fresh stream fades in instead of arriving at level. Render thread.
    private var appliedGain = 0f

    private var lostReported = false
    private var framesWritten = 0L
    private var lastDispatched = 0L
    private var lastAdvanceNs = 0L
    private var firstWriteNs = 0L

    override val isOpen: Boolean get() = format != null && driver.isOpen.value
    override val isExclusive: Boolean get() = true
    override val latencyFrames: Int get() = if (format != null) LibusbUacNative.inFlightFrames() else 0
    override val outputFormat: AudioFormat? get() = format
    override val deviceName: String? get() = driver.dacInfo.value?.displayName

    /** Valid bits per sample the DAC was given; 0 before [configure]. */
    val dacBitsPerSample: Int get() = validBits

    override fun configure(format: AudioFormat): AudioFormat {
        this.format = null
        if (!driver.isAvailable || !LibusbUacNative.isAvailable) {
            throw SinkException("the libusb driver is missing or out of date")
        }
        if (!driver.isOpen.value) throw SinkException("no USB DAC is open")
        val bytes = UacPcm.sourceBytesPerSample(format.encoding)
        if (format.sampleRate <= 0 || format.channelCount <= 0 || bytes == 0) {
            throw SinkException("cannot send $format to a USB DAC")
        }
        val rate = format.sampleRate
        val ch = format.channelCount
        for (bits in UacPcm.bitDepthLadder(format.encoding)) {
            val reused = driver.isStreamingFormat(rate, bits, ch)
            if (!reused && !driver.start(rate, bits, ch)) {
                // Another depth cannot help when the interface is not ours or
                // the clock refused the rate; stop with that reason recorded.
                val code = driver.lastStartError.value?.code
                if (code != null && code in DEPTH_INDEPENDENT) break
                continue
            }
            // Whatever the previous track left queued is not this one's.
            if (reused) driver.flushRing()
            // A stream the DAC starts from silence fades in from silence; one
            // it carries on with (same format) keeps its level.
            if (!reused) appliedGain = 0f
            // The subslot the driver negotiated, from the device's descriptor.
            // The guard keeps a snapshot of an earlier stream out of it.
            val slot = driver.diagnostics.value
                ?.takeIf { it.sampleRateHz == rate && it.bitsPerSample == bits && it.channels == ch }
                ?.bytesPerSample
                ?.takeIf { it > 0 }
                ?: ((bits + 7) / 8)
            if (slot * 8 < bits || slot > 4) {
                Log.w(TAG, "DAC reported $slot-byte subslots for $bits-bit audio; not using this depth")
                continue
            }
            channels = ch
            validBits = bits
            subslotBytes = slot
            sourceBytesPerFrame = bytes * ch
            LibusbUacNative.setPaused(!playing)
            resetClock()
            lostReported = false
            this.format = format
            val note = if (UacPcm.isLossless(format.encoding, bits)) "" else " (narrowed: the DAC has nothing wider at this rate)"
            Log.i(TAG, "configured ${driver.dacInfo.value?.displayName ?: "USB DAC"}: $rate Hz ${ch}ch, " +
                "source ${encodingLabel(format.encoding)} -> $bits-bit in $slot-byte subslots" +
                (if (reused) ", stream reused" else "") + note)
            return format
        }
        val failure = driver.lastStartError.value
        onRefused(failure)
        val why = failure?.let { "${it.code}: ${it.detail}" } ?: "no usable alternate setting"
        throw SinkException("USB DAC refused $rate Hz ${ch}ch ${encodingLabel(format.encoding)} ($why)")
    }

    override fun write(buffer: ByteBuffer, frames: Int): Int {
        if (format == null || frames <= 0) return 0
        if (!driver.isOpen.value || !driver.isStreaming.value) {
            reportLost("the driver no longer streams to the DAC")
            return 0
        }
        val room = LibusbUacNative.writableFrames()
        val n = minOf(frames, room)
        if (n <= 0) {
            checkWedged()
            return 0
        }
        val outBytes = n * channels * subslotBytes
        if (scratch.capacity() < outBytes) {
            // Grows to the largest block the engine writes, then stays.
            scratch = ByteBuffer.allocateDirect(maxOf(outBytes, scratch.capacity() * 2)).order(ByteOrder.nativeOrder())
        }
        scratch.clear()
        // The DAC level, reached a frame at a time (GainRamp): a slider move is
        // a slope, not a step, and a fresh stream fades in.
        val target = dacGain().coerceIn(0f, 1f)
        val start = appliedGain
        val rate = format!!.sampleRate
        val rise = GainRamp.risePerFrame(target, rate)
        val fall = GainRamp.fallPerFrame(rate)
        if (target >= 1f && start >= 1f) {
            // Unity and settled: the samples go out untouched, bit-perfect.
            UacPcm.pack(buffer, format!!.encoding, n * channels, validBits, subslotBytes, scratch)
        } else {
            UacPcm.packWithGain(buffer, format!!.encoding, n, channels, validBits, subslotBytes, scratch, start, target, rise, fall)
        }
        scratch.limit(outBytes)
        val written = driver.write(scratch, n)
        // Where the ramp got to in the frames the DAC took; a partial write
        // resumes from there.
        if (written > 0) appliedGain = GainRamp.after(start, target, written, rise, fall)
        if (written > 0) {
            buffer.position(buffer.position() + written * sourceBytesPerFrame)
            framesWritten += written
            if (firstWriteNs == 0L) {
                firstWriteNs = System.nanoTime()
                lastAdvanceNs = firstWriteNs
            }
        }
        checkWedged()
        return written
    }

    override fun playedFrames(): Long = if (format != null) LibusbUacNative.dispatchedFrames() else 0L

    /**
     * Also runs the stall check: at end of stream the engine stops writing and
     * polls this until the ring is empty, and a pump that died then would
     * otherwise hold the drain open for good. Only the render thread calls it.
     */
    override fun pendingFrames(): Long {
        if (format == null) return 0L
        checkWedged()
        return LibusbUacNative.queuedFrames()
    }

    /** Nothing is staged here; the pump drains the ring on its own. */
    override fun drain() {}

    override fun play() {
        playing = true
        if (format != null) LibusbUacNative.setPaused(false)
        // A pause is not a stall: time the watchdog from now.
        lastAdvanceNs = System.nanoTime()
    }

    override fun pause() {
        playing = false
        if (format != null) LibusbUacNative.setPaused(true)
    }

    override fun flush() {
        if (format != null && driver.isStreaming.value) driver.flushRing()
        resetClock()
    }

    override fun stop() = pause()

    override fun release() {
        format = null
        playing = false
        // Full teardown: alternate setting 0 and the interfaces released. A
        // refused configure can leave the claim of an earlier stream held.
        if (driver.isOpen.value) driver.stop()
    }

    private fun resetClock() {
        framesWritten = 0L
        lastDispatched = 0L
        firstWriteNs = 0L
        lastAdvanceNs = 0L
    }

    /**
     * Android's iso-pump watchdog: frames queued, the stream playing, and the
     * pump has taken none for [STALL_NS] after a warm-up. On Windows an
     * unplugged DAC looks exactly like this -- its transfers complete with
     * "no device", nothing is resubmitted, and the driver still says it is
     * streaming -- so this is also how an unplug is noticed between the
     * controller's polls.
     */
    private fun checkWedged() {
        if (!playing || firstWriteNs == 0L || lostReported) return
        val now = System.nanoTime()
        val dispatched = LibusbUacNative.dispatchedFrames()
        if (dispatched != lastDispatched || LibusbUacNative.queuedFrames() == 0L) {
            lastDispatched = dispatched
            lastAdvanceNs = now
            return
        }
        if (now - firstWriteNs > WARMUP_NS && now - lastAdvanceNs > STALL_NS) {
            reportLost("iso pump stalled: no frames taken for ${(now - lastAdvanceNs) / 1_000_000} ms " +
                "after $framesWritten written")
        }
    }

    private fun reportLost(reason: String) {
        if (lostReported) return
        lostReported = true
        Log.w(TAG, "USB output lost: $reason")
        onLost(reason)
    }

    private fun encodingLabel(encoding: Int): String = when (encoding) {
        C.ENCODING_PCM_FLOAT -> "float"
        else -> "${UacPcm.sourceBytesPerSample(encoding) * 8}-bit"
    }

    private companion object {
        const val TAG = "LibusbUacSink"
        val DEPTH_INDEPENDENT = setOf(StartError.NoDevice, StartError.ClaimInterfaceFailed, StartError.SetSampleRateFailed)
        const val WARMUP_NS = 400_000_000L
        const val STALL_NS = 400_000_000L
    }
}
