// Desktop stand-in for Media3's BaseAudioProcessor; see AudioProcessor.kt.
package androidx.media3.common.audio

import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A processor skeleton: holds the pending and configured formats, owns one
 * growable output buffer, and tracks end of stream. Subclasses implement
 * onConfigure, queueInput and the on* hooks.
 */
abstract class BaseAudioProcessor : AudioProcessor {
    @JvmField protected var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    @JvmField protected var outputAudioFormat: AudioFormat = AudioFormat.NOT_SET

    private var pendingInputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var pendingOutputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        pendingInputAudioFormat = inputAudioFormat
        pendingOutputAudioFormat = onConfigure(inputAudioFormat)
        return if (isActive()) pendingOutputAudioFormat else AudioFormat.NOT_SET
    }

    override fun isActive(): Boolean = pendingOutputAudioFormat !== AudioFormat.NOT_SET

    override fun queueEndOfStream() {
        inputEnded = true
        onQueueEndOfStream()
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        inputAudioFormat = pendingInputAudioFormat
        outputAudioFormat = pendingOutputAudioFormat
        onFlush()
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        pendingInputAudioFormat = AudioFormat.NOT_SET
        pendingOutputAudioFormat = AudioFormat.NOT_SET
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
        onReset()
    }

    /** Returns a cleared output buffer of at least [size] bytes, reusing the last one when it fits. */
    protected fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        outputBuffer = buffer
        return buffer
    }

    /** Whether there is pending output. */
    protected fun hasPendingOutput(): Boolean = outputBuffer.hasRemaining()

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    protected open fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat = AudioFormat.NOT_SET

    protected open fun onQueueEndOfStream() {}
    protected open fun onFlush() {}
    protected open fun onReset() {}
}
