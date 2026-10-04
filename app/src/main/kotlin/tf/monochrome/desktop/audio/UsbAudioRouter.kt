package tf.monochrome.desktop.audio

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import tf.monochrome.desktop.audio.usb.DacInfo
import tf.monochrome.desktop.audio.usb.LibusbUacDriver
import tf.monochrome.desktop.audio.usb.UsbAttachedDevice
import tf.monochrome.desktop.audio.usb.UsbExclusiveController
import tf.monochrome.desktop.platform.AppScope

/**
 * Tracks the currently-attached USB Audio Class DAC (if any), for the
 * Settings and onboarding lines that name it and for the pipeline panel.
 *
 * Desktop: Android used this to pin ExoPlayer's AudioTrack to the DAC with
 * `setPreferredAudioDevice`, routing the stream there inside the framework.
 * Windows has no per-stream device preference that keeps its mixer out of the
 * way; the routes to a USB DAC are WASAPI exclusive on its endpoint (picked
 * under Audio output, `AudioOutputController`) and the libusb path
 * ([UsbExclusiveController]). So the router steers nothing. It reports the DAC
 * the libusb path holds -- named from its USB descriptors -- or, when that path
 * is off, the first DAC on the bus, read through libusb every
 * [POLL_MS] while something is watching (libusb has no hotplug on Windows,
 * and Windows' own audio-endpoint list cannot say which endpoint is USB).
 *
 * The device type changed with the platform: [DacInfo] instead of Android's
 * `AudioDeviceInfo`, which has no desktop counterpart. Callers use it only
 * for null checks and [describe].
 *
 * A DAC still on the Windows audio driver cannot be opened by libusb, so its
 * string descriptors are unreadable and it is named by VID:PID until it is
 * bound to WinUSB (see [DacInfo]).
 */
@Singleton
class UsbAudioRouter @Inject constructor(
    private val driver: LibusbUacDriver,
    exclusive: UsbExclusiveController,
    @AppScope scope: CoroutineScope,
) {
    /** The first DAC on the bus, re-read every [POLL_MS] while collected. */
    private val attachedDac: Flow<DacInfo?> = flow {
        var last: UsbAttachedDevice? = null
        var info: DacInfo? = null
        while (true) {
            val dac = driver.audioDevices().firstOrNull()
            // Descriptors are read once per plug-in, not every poll: reading
            // strings opens the device.
            info = when {
                dac == null -> null
                last?.sameDevice(dac) == true && info != null -> info
                else -> DacInfo.fromAttached(dac)
            }
            last = dac
            emit(info)
            delay(POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    val usbOutputDevice: StateFlow<DacInfo?> =
        combine(exclusive.dacInfo, attachedDac) { owned, attached -> owned ?: attached }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Human-readable label for the Settings screen. */
    fun describe(device: DacInfo): String = device.displayName

    private companion object {
        const val POLL_MS = 2_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
