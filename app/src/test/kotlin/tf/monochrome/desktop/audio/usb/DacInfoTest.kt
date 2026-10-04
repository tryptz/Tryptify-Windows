package tf.monochrome.desktop.audio.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `nativeDeviceInfo`'s seven strings laid over the enumeration's identity,
 * and the Windows wording of the failures the Settings card shows beside it.
 */
class DacInfoTest {

    private val bathys = UsbAttachedDevice(0x262a, 0x9302, 1, 7, hasAudioStreaming = true)

    @Test
    fun `a full descriptor read names the DAC`() {
        val info = DacInfo.fromDescriptor(bathys, arrayOf("Focal", "Bathys", "SN123", "2.00", "239", "2", "1"))
        assertEquals("Focal Bathys", info.displayName)
        assertEquals("SN123", info.serialNumber)
        assertEquals("262a:9302 · USB 2.00 · class 239/2/1", info.descriptorLine)
        assertEquals(bathys.deviceId, info.deviceId)
    }

    @Test
    fun `unreadable strings fall back to VID PID and keep the descriptor numbers`() {
        // What a DAC still on the Windows audio driver gives: no strings.
        val info = DacInfo.fromDescriptor(bathys, arrayOf("", " ", "", "1.10", "0", "0", "0"))
        assertNull(info.manufacturer)
        assertNull(info.product)
        assertNull(info.serialNumber)
        assertEquals("USB DAC 262a:9302", info.displayName)
        assertEquals("262a:9302 · USB 1.10 · class 0/0/0", info.descriptorLine)
    }

    @Test
    fun `no descriptor at all leaves the enumeration's identity alone`() {
        for (fields in listOf<Array<String>?>(null, arrayOf("Focal", "Bathys"))) {
            val info = DacInfo.fromDescriptor(bathys, fields)
            assertNull(info.product)
            assertNull(info.usbVersion)
            assertEquals(DacInfo.UNKNOWN, info.deviceClass)
            assertEquals("262a:9302", info.descriptorLine)
        }
        val garbled = DacInfo.fromDescriptor(bathys, arrayOf("a", "b", "c", "2.00", "x", "", "-"))
        assertEquals(DacInfo.UNKNOWN, garbled.deviceClass)
        assertEquals(DacInfo.UNKNOWN, garbled.deviceSubClass)
    }

    @Test
    fun `every failure has advice, and none of it is Android's`() {
        for (code in StartError.entries) {
            val message = StartFailure(code, "detail").actionableMessage()
            if (code == StartError.Ok) {
                assertEquals("", message)
                continue
            }
            assertTrue("$code has no advice", message.isNotBlank())
            for (androidOnly in listOf("Android", "Developer Options", "HAL", "framework")) {
                assertFalse("$code still says \"$androidOnly\": $message", message.contains(androidOnly, ignoreCase = true))
            }
        }
        val claim = StartFailure(StartError.ClaimInterfaceFailed, "").actionableMessage()
        assertTrue(claim.contains("WinUSB"))
        assertTrue(claim.contains("Zadig"))
    }
}
