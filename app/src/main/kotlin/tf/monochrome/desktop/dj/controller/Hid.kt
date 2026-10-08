package tf.monochrome.desktop.dj.controller

import java.io.Closeable
import java.io.IOException

/**
 * USB HID, reduced to what a DJ controller needs: find the device, read its
 * input reports, write its LED report, and ask it for a feature or input
 * report on demand.
 *
 * Every report crosses this interface with its ID in byte 0, as on the wire
 * and as hid.dll (and hidapi, and Mixxx's scripts) hand them over. The
 * Windows implementation is platform/windows/WindowsHid; tests drive
 * [ControllerManager] with a fake bus.
 */
data class HidInfo(
    /** What opens the device: on Windows the interface path, `\\?\hid#vid_17cc&pid_1101#...`. */
    val path: String,
    val vendorId: Int,
    val productId: Int,
)

interface HidConnection : Closeable {
    /**
     * Waits up to [timeoutMs] for the next input report and copies it into
     * [buffer], ID first. Returns its length, 0 when none came in time.
     * Throws [IOException] once the device is gone.
     */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    /** Sends an output report, ID first. False when the device did not take it. */
    fun write(report: ByteArray): Boolean

    /** Feature report [id], ID first; null when the device does not answer. */
    fun feature(id: Int): ByteArray?

    /** The current state of input report [id], ID first, without waiting for a change. */
    fun inputReport(id: Int): ByteArray?
}

interface HidBus {
    /** The HID devices present now. Empty where the platform has no HID access. */
    fun devices(): List<HidInfo>

    /** Throws [HidOpenException] when the device is listed but will not open. */
    @Throws(HidOpenException::class)
    fun open(info: HidInfo): HidConnection
}

/** The device is there but would not open; [busy] when another program holds it. */
class HidOpenException(val busy: Boolean, message: String) : IOException(message)

/** Where the platform has no HID access: no devices, so nothing connects. */
object NoHidBus : HidBus {
    override fun devices(): List<HidInfo> = emptyList()
    override fun open(info: HidInfo): HidConnection = throw HidOpenException(busy = false, "no HID")
}
