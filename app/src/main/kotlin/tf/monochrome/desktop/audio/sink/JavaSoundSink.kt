package tf.monochrome.desktop.audio.sink

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.AudioFormat as JAudioFormat

/**
 * The fallback output through Java Sound: 16-bit PCM on the default mixer.
 * No exclusive mode, no rate switching below the mixer; it exists so the app
 * plays on a machine where WASAPI could not be opened, and on Linux, where the
 * headless build's smoke test runs. A float or 24-bit chain output is packed
 * to 16 bits by the engine before it gets here.
 */
class JavaSoundSink : AudioSink {
    private var line: SourceDataLine? = null
    private var format: AudioFormat? = null
    private var bytesPerFrame = 0
    private var writtenFrames = 0L
    private var framesAtFlush = 0L
    private var scratch = ByteArray(0)

    override val isOpen: Boolean get() = line != null
    override val isExclusive: Boolean get() = false
    override val latencyFrames: Int get() = line?.let { it.bufferSize / bytesPerFrame.coerceAtLeast(1) } ?: 0
    override val outputFormat: AudioFormat? get() = format
    override val deviceName: String? get() = "Java Sound"

    override fun configure(format: AudioFormat): AudioFormat {
        release()
        val out = AudioFormat(format.sampleRate, format.channelCount, C.ENCODING_PCM_16BIT)
        val jf = JAudioFormat(format.sampleRate.toFloat(), 16, format.channelCount, true, ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN)
        val info = DataLine.Info(SourceDataLine::class.java, jf)
        val opened = try {
            (AudioSystem.getLine(info) as SourceDataLine).also {
                it.open(jf, (format.sampleRate / 5) * out.bytesPerFrame)   // 200 ms
            }
        } catch (e: Exception) {
            throw SinkException("Java Sound line unavailable for ${format.sampleRate} Hz x${format.channelCount}", e)
        }
        line = opened
        bytesPerFrame = out.bytesPerFrame
        this.format = out
        writtenFrames = 0
        framesAtFlush = 0
        Log.i(TAG, "opened ${format.sampleRate} Hz x${format.channelCount} 16-bit")
        return out
    }

    override fun write(buffer: ByteBuffer, frames: Int): Int {
        val l = line ?: return 0
        if (frames <= 0) return 0
        val available = l.available() / bytesPerFrame
        val n = minOf(frames, available)
        if (n <= 0) return 0
        val bytes = n * bytesPerFrame
        if (scratch.size < bytes) scratch = ByteArray(bytes)
        buffer.get(scratch, 0, bytes)
        val wrote = l.write(scratch, 0, bytes) / bytesPerFrame
        writtenFrames += wrote
        if (wrote < n) buffer.position(buffer.position() - (n - wrote) * bytesPerFrame)
        return wrote
    }

    override fun playedFrames(): Long = line?.let { (it.longFramePosition - framesAtFlush).coerceAtLeast(0) } ?: 0
    override fun pendingFrames(): Long = (writtenFrames - playedFrames()).coerceAtLeast(0)
    override fun drain() { line?.drain() }
    override fun play() { line?.start() }
    override fun pause() { line?.stop() }
    override fun flush() {
        line?.let { it.stop(); it.flush(); framesAtFlush = it.longFramePosition; writtenFrames = 0 }
    }
    override fun stop() { line?.stop() }
    override fun release() {
        line?.let { it.stop(); it.flush(); it.close() }
        line = null
        format = null
    }

    companion object {
        private const val TAG = "JavaSoundSink"
    }
}
