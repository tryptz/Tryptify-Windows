package tf.monochrome.desktop.audio.pipeline

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.desktop.audio.sink.OutputMode

/**
 * What the panel may say about a Windows output from its endpoint name, and
 * the depth a sink's format stands for. Everything without a marker is
 * "other": an A2DP headset's endpoint looks exactly like a speaker's.
 */
class OutputDeviceProbeTest {

    @Test
    fun `the libusb path is USB whatever the name`() {
        assertEquals(OutputKind.USB, OutputDeviceProbe.outputKindFor(OutputMode.USB_EXCLUSIVE, null, null))
        assertEquals(OutputKind.USB, OutputDeviceProbe.outputKindFor(OutputMode.USB_EXCLUSIVE, "Speakers", null))
    }

    @Test
    fun `a WASAPI endpoint named after the attached DAC is USB`() {
        assertEquals(OutputKind.USB, OutputDeviceProbe.outputKindFor(OutputMode.WASAPI_EXCLUSIVE, "Speakers (FiiO K7)", "FiiO K7"))
        assertEquals(OutputKind.OTHER, OutputDeviceProbe.outputKindFor(OutputMode.WASAPI_SHARED, "Speakers (Realtek(R) Audio)", "FiiO K7"))
        assertEquals(OutputKind.OTHER, OutputDeviceProbe.outputKindFor(OutputMode.WASAPI_SHARED, "Speakers (Realtek(R) Audio)", " "))
    }

    @Test
    fun `hands-free and display audio are recognised, the rest is not guessed`() {
        assertEquals(OutputKind.BLUETOOTH_SCO, OutputDeviceProbe.outputKindFor(OutputMode.WASAPI_SHARED, "Headset (WH-1000XM4 Hands-Free AG Audio)", null))
        assertEquals(OutputKind.HDMI, OutputDeviceProbe.outputKindFor(OutputMode.WASAPI_SHARED, "LG TV (NVIDIA High Definition Audio)", null))
        assertEquals(OutputKind.HDMI, OutputDeviceProbe.outputKindFor(OutputMode.WASAPI_EXCLUSIVE, "DELL U2720Q (Intel(R) Display Audio)", null))
        assertEquals(OutputKind.OTHER, OutputDeviceProbe.outputKindFor(OutputMode.WASAPI_SHARED, "Headphones (WH-1000XM4)", null))
        assertEquals(OutputKind.OTHER, OutputDeviceProbe.outputKindFor(OutputMode.JAVA_SOUND, null, null))
    }

    @Test
    fun `a sink format reads as valid bits`() {
        assertEquals(16 to false, OutputDeviceProbe.pcmShape(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)))
        assertEquals(24 to false, OutputDeviceProbe.pcmShape(AudioFormat(96_000, 2, C.ENCODING_PCM_24BIT)))
        // WASAPI's 24-in-32 container.
        assertEquals(24 to false, OutputDeviceProbe.pcmShape(AudioFormat(96_000, 2, C.ENCODING_PCM_32BIT)))
        assertEquals(32 to true, OutputDeviceProbe.pcmShape(AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)))
        assertNull(OutputDeviceProbe.pcmShape(AudioFormat(48_000, 2, C.ENCODING_INVALID)))
    }
}
