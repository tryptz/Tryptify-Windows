package tf.monochrome.desktop.audio.sink

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import tf.monochrome.desktop.audio.wasapi.WasapiNative

/**
 * WASAPI output. Shared mode takes the chain's format as-is and lets Windows
 * convert; exclusive mode opens the endpoint at the stream's rate with the
 * same `[24, 16]` depth ladder the Android USB bypass used for float sources,
 * and a 16-bit source stays 16-bit, so a 16-bit file with the DSP off is
 * bit-perfect to the DAC.
 */
class WasapiSink(
    private val deviceId: String? = null,
    override val isExclusive: Boolean = false,
    private val bufferMillis: Int = if (isExclusive) 40 else 80,
) : AudioSink {
    private var handle = 0L
    private var bytesPerFrame = 0
    private var format: AudioFormat? = null
    private var nativeFormat = WasapiNative.FORMAT_FLOAT32
    private var playing = false
    private var staging: ByteBuffer = ByteBuffer.allocateDirect(0)

    override val isOpen: Boolean get() = handle != 0L
    override val outputFormat: AudioFormat? get() = format
    override val latencyFrames: Int get() = if (handle == 0L) 0 else WasapiNative.nativeLatencyFrames(handle)
    override val deviceName: String?
        get() = WasapiNative.listDevices().firstOrNull { if (deviceId == null) it.isDefault else it.id == deviceId }?.name

    override fun configure(format: AudioFormat): AudioFormat {
        if (!WasapiNative.isAvailable) throw SinkException("WASAPI is not available on this platform")
        release()
        val candidates: List<Pair<Int, Int>> = when (format.encoding) {
            C.ENCODING_PCM_FLOAT -> if (isExclusive) {
                listOf(WasapiNative.FORMAT_PCM24_IN_32 to C.ENCODING_PCM_32BIT, WasapiNative.FORMAT_PCM24 to C.ENCODING_PCM_24BIT,
                    WasapiNative.FORMAT_PCM16 to C.ENCODING_PCM_16BIT, WasapiNative.FORMAT_FLOAT32 to C.ENCODING_PCM_FLOAT)
            } else listOf(WasapiNative.FORMAT_FLOAT32 to C.ENCODING_PCM_FLOAT)
            C.ENCODING_PCM_16BIT -> listOf(WasapiNative.FORMAT_PCM16 to C.ENCODING_PCM_16BIT)
            C.ENCODING_PCM_24BIT -> listOf(WasapiNative.FORMAT_PCM24 to C.ENCODING_PCM_24BIT)
            C.ENCODING_PCM_32BIT -> listOf(WasapiNative.FORMAT_PCM32 to C.ENCODING_PCM_32BIT)
            else -> throw SinkException("unsupported encoding ${format.encoding}")
        }
        var lastError: String? = null
        for ((native, encoding) in candidates) {
            val h = WasapiNative.nativeOpen(deviceId, format.sampleRate, format.channelCount, native, isExclusive, bufferMillis)
            if (h != 0L) {
                handle = h
                nativeFormat = native
                val desc = WasapiNative.nativeDescribe(h)
                bytesPerFrame = desc[6]
                this.format = AudioFormat(format.sampleRate, format.channelCount, encoding)
                Log.i(TAG, "opened ${if (isExclusive) "exclusive" else "shared"} ${format.sampleRate} Hz x${format.channelCount} fmt=$native buffer=${desc[3]} period=${desc[4]}")
                return this.format!!
            }
            lastError = WasapiNative.nativeLastError()
        }
        throw SinkException("WASAPI open failed: $lastError")
    }

    override fun write(buffer: ByteBuffer, frames: Int): Int {
        val h = handle
        if (h == 0L || frames <= 0) return 0
        val bytes = frames * bytesPerFrame
        // The native side needs a direct buffer; copy a heap buffer through staging.
        val src: ByteBuffer
        val offset: Int
        if (buffer.isDirect) {
            src = buffer
            offset = buffer.position()
        } else {
            if (staging.capacity() < bytes) staging = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            staging.clear()
            val save = buffer.limit()
            buffer.limit(buffer.position() + bytes)
            staging.put(buffer)
            buffer.limit(save)
            buffer.position(buffer.position() - bytes)
            src = staging
            offset = 0
        }
        val written = WasapiNative.nativeWrite(h, src, offset, frames)
        if (written < 0) throw SinkException("WASAPI write failed: ${WasapiNative.nativeLastError()}")
        buffer.position(buffer.position() + written * bytesPerFrame)
        return written
    }

    override fun playedFrames(): Long = if (handle == 0L) 0 else WasapiNative.nativePlayedFrames(handle).coerceAtLeast(0)
    override fun pendingFrames(): Long =
        if (handle == 0L) 0 else (WasapiNative.nativeWrittenFrames(handle) - playedFrames()).coerceAtLeast(0)

    override fun drain() { if (handle != 0L) WasapiNative.nativeFlush(handle) }

    override fun play() {
        if (handle != 0L && !playing) { playing = WasapiNative.nativeStart(handle) }
    }

    override fun pause() {
        if (handle != 0L && playing) { WasapiNative.nativeStop(handle); playing = false }
    }

    override fun flush() {
        if (handle != 0L) { WasapiNative.nativeReset(handle); playing = false }
    }

    override fun stop() = pause()

    override fun release() {
        if (handle != 0L) { WasapiNative.nativeClose(handle); handle = 0L; playing = false; format = null }
    }

    companion object {
        private const val TAG = "WasapiSink"
    }
}
