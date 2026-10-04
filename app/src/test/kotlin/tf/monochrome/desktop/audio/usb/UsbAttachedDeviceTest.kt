package tf.monochrome.desktop.audio.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The flat int[] from `nativeListDevices` is rebuilt in one pass over groups of five. */
class UsbAttachedDeviceTest {

    @Test
    fun `decodes the native flattening and ignores a trailing partial record`() {
        val flat = intArrayOf(
            0x1d6b, 0x0100, 1, 4, 1,
            0x046d, 0xc52b, 1, 7, 0,
            0x0bda, 0x4014, 2, // cut off
        )
        val devices = UsbAttachedDevice.decodeAll(flat)
        assertEquals(2, devices.size)
        assertEquals(UsbAttachedDevice(0x1d6b, 0x0100, 1, 4, true), devices[0])
        assertEquals(UsbAttachedDevice(0x046d, 0xc52b, 1, 7, false), devices[1])
        assertEquals("1d6b:0100", devices[0].idHex)
        assertEquals((1 shl 8) or 4, devices[0].deviceId)
        assertTrue(UsbAttachedDevice.decodeAll(null).isEmpty())
        assertTrue(UsbAttachedDevice.decodeAll(IntArray(0)).isEmpty())
    }

    @Test
    fun `identity falls back to VID PID until the descriptor strings are read`() {
        val info = DacInfo.fromAttached(UsbAttachedDevice(0x1d6b, 0x0100, 1, 4, true))
        assertEquals("USB DAC 1d6b:0100", info.displayName)
        assertEquals("1d6b:0100", info.descriptorLine)
        assertEquals(DacInfo.UNKNOWN, info.deviceClass)
    }
}
