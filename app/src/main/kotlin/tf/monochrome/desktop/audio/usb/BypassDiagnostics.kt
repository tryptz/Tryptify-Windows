package tf.monochrome.desktop.audio.usb

/**
 * Snapshot of what the libusb iso pump is actually doing. Surfaced to
 * the Settings UI so the user can verify their hi-res file isn't being
 * silently downsampled — "192 kHz · 24-bit · async feedback ✓" beats a
 * binary "Streaming" badge.
 *
 * Mirrors the native [LibusbUacDriver::nativeActiveStream] long[]
 * layout. The native side packs in a fixed order; [fromLongArray]
 * decodes by position. Adding fields means appending to both ends in
 * lockstep — no tagged-field tolerance, so don't reorder.
 */
data class BypassDiagnostics(
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val channels: Int,
    val interfaceNumber: Int,
    val altSetting: Int,
    val endpointAddress: Int,
    val maxPacketSize: Int,
    /** Iso transfer interval. HS: 2^(bInterval-1) microframes; FS: ms. */
    val bInterval: Int,
    /** 0x0100 = UAC1, 0x0200 = UAC2. */
    val uacVersion: Int,
    /** UAC2 clock-source entity ID that accepted SET_CUR. 0 = UAC1 / unset. */
    val clockSourceId: Int,
    /** Async feedback IN endpoint address; 0 means the device is sync/adaptive. */
    val feedbackEndpointAddress: Int,
    val isHighSpeed: Boolean,
    val bytesPerSample: Int,
) {
    val hasFeedbackEndpoint: Boolean get() = feedbackEndpointAddress != 0
    val isUac2: Boolean get() = uacVersion >= 0x0200

    /** "192 kHz" / "44.1 kHz" / "1.5 MHz". */
    fun rateLabel(): String {
        val hz = sampleRateHz
        return when {
            hz <= 0 -> "—"
            hz % 1000 == 0 -> "${hz / 1000} kHz"
            else -> "%.1f kHz".format(hz / 1000.0)
        }
    }

    /** "USB 2.0 High-Speed" / "USB 1.1 Full-Speed". */
    fun speedLabel(): String =
        if (isHighSpeed) "USB 2.0 HS" else "USB 1.1 FS"

    /** "UAC2" / "UAC1" — what spec version the device negotiated under. */
    fun uacLabel(): String = if (isUac2) "UAC2" else "UAC1"

    companion object {
        /** Field count in the long[] returned by nativeActiveStream. */
        private const val FIELD_COUNT = 13

        fun fromLongArray(packed: LongArray?): BypassDiagnostics? {
            if (packed == null || packed.size < FIELD_COUNT) return null
            return BypassDiagnostics(
                sampleRateHz = packed[0].toInt(),
                bitsPerSample = packed[1].toInt(),
                channels = packed[2].toInt(),
                interfaceNumber = packed[3].toInt(),
                altSetting = packed[4].toInt(),
                endpointAddress = packed[5].toInt(),
                maxPacketSize = packed[6].toInt(),
                bInterval = packed[7].toInt(),
                uacVersion = packed[8].toInt(),
                clockSourceId = packed[9].toInt(),
                feedbackEndpointAddress = packed[10].toInt(),
                isHighSpeed = packed[11] != 0L,
                bytesPerSample = packed[12].toInt(),
            )
        }
    }
}

/**
 * One subrange entry from the device's GET_RANGE table on a clock
 * entity. UAC2 §5.2.1: most DACs report each supported discrete rate
 * with min == max; some interfaces with a continuous PLL report a
 * proper [min, max] window with a non-zero resolution step.
 *
 * For UAC1 devices (no clock entities), the native side synthesises
 * entries with [clockId] = 0 from the AS_FORMAT_TYPE descriptor's
 * sample-frequency table.
 */
data class ClockRateRange(
    val clockId: Int,
    val minHz: Int,
    val maxHz: Int,
    val resHz: Int,
) {
    val isDiscrete: Boolean get() = minHz == maxHz

    /** "44.1 kHz" for discrete, "44.1–768 kHz" for continuous. */
    fun label(): String {
        fun fmt(hz: Int) = when {
            hz <= 0 -> "—"
            hz % 1000 == 0 -> "${hz / 1000} kHz"
            else -> "%.1f kHz".format(hz / 1000.0)
        }
        return if (isDiscrete) fmt(minHz)
        else "${fmt(minHz)}–${fmt(maxHz)}"
    }

    companion object {
        /** Decodes the flat int[] (clockId, minHz, maxHz, resHz)+ packing. */
        fun decodeAll(packed: IntArray?): List<ClockRateRange> {
            if (packed == null || packed.size < 4) return emptyList()
            val out = ArrayList<ClockRateRange>(packed.size / 4)
            var i = 0
            while (i + 4 <= packed.size) {
                out.add(
                    ClockRateRange(
                        clockId = packed[i],
                        minHz = packed[i + 1],
                        maxHz = packed[i + 2],
                        resHz = packed[i + 3],
                    )
                )
                i += 4
            }
            return out
        }
    }
}

/**
 * Categorised reason the most recent [LibusbUacDriver.start] failed.
 * Order MUST match the native [StartError] enum in libusb_uac_driver.h.
 *
 * Each value carries enough context for the UI to write actionable
 * advice without having to parse the detail string. The detail string
 * is a separate field on [LibusbUacDriver.lastStartError] for the
 * cases where the category isn't enough.
 */
enum class StartError(val code: Int) {
    Ok(0),

    /** start() called before open() — the DAC went away, or the driver was
     *  closed under a stream (toggle off, unplug). */
    NoDevice(1),

    /** Descriptor walk found no AS alt with the requested rate/bits/channels. */
    NoMatchingAlt(2),

    /** libusb_claim_interface failed — the operating system's USB audio
     *  driver still owns the streaming interface. On Windows that is
     *  usbaudio2.sys until the interface is bound to WinUSB (Zadig); on
     *  Linux, the /dev/bus/usb permissions. */
    ClaimInterfaceFailed(3),

    /** libusb_set_interface_alt_setting failed — rare, suggests the
     *  device went away mid-negotiation. */
    SetAltFailed(4),

    /** SET_CUR / GET_CUR fell through every clock candidate. The
     *  device runs a fixed-rate clock that doesn't match the source
     *  file, or the clock-entity topology is non-obvious. */
    SetSampleRateFailed(5),

    /** libusb_alloc_transfer returned null — OOM or libusb internal failure. */
    IsoPumpAllocFailed(6),

    /** Initial libusb_submit_transfer failed — the device went away
     *  between claim and submit, the endpoint configuration is broken, or
     *  (on Windows) the WinUSB in use has no isochronous support. */
    IsoPumpSubmitFailed(7);

    companion object {
        fun fromCode(c: Int): StartError = entries.firstOrNull { it.code == c } ?: Ok
    }
}

/**
 * Pair of [StartError] code + native detail message. The Settings UI
 * looks up [actionableMessage] for the headline, then optionally
 * shows [detail] as a "see logs" expandable.
 */
data class StartFailure(
    val code: StartError,
    val detail: String,
) {
    /**
     * User-facing one-liner derived purely from the category.
     *
     * Desktop: reworded for Windows. Android's advice (Developer Options →
     * Disable USB audio routing, the framework-routing toggle) has no
     * counterpart; what stands between libusb and a DAC on Windows is the
     * driver binding, which Zadig changes. A track the DAC refuses is not
     * silenced here: it plays on the output picked under Audio output, so
     * the rate messages say so.
     */
    fun actionableMessage(): String = when (code) {
        StartError.Ok ->
            ""
        StartError.NoDevice ->
            "The DAC isn't open — it was unplugged, or exclusive mode was " +
            "switched off. Plug it in and turn Exclusive USB DAC back on."
        StartError.NoMatchingAlt ->
            "Your DAC doesn't offer this track's sample rate at any bit " +
            "depth it supports, so the track is playing on your selected " +
            "audio output instead. The supported rates are listed below."
        StartError.ClaimInterfaceFailed ->
            "Windows' USB audio driver still owns the DAC. Bind the DAC's " +
            "audio interfaces (AudioControl and AudioStreaming) to WinUSB " +
            "with Zadig, then turn Exclusive USB DAC off and on. The DAC " +
            "then disappears from Windows' sound devices until you put " +
            "its driver back in Device Manager."
        StartError.SetAltFailed ->
            "USB negotiation failed mid-handshake. Unplug and re-plug " +
            "the DAC, then turn Exclusive USB DAC off and on."
        StartError.SetSampleRateFailed ->
            "Your DAC's clock won't accept this rate (it may run at a " +
            "fixed clock), so the track is playing on your selected audio " +
            "output instead. The supported rates are listed below."
        StartError.IsoPumpAllocFailed ->
            "Couldn't allocate USB transfers. Close other audio software " +
            "and try again."
        StartError.IsoPumpSubmitFailed ->
            "The DAC didn't accept the audio stream — it may have been " +
            "unplugged, or the WinUSB driver bound to it can't stream " +
            "audio (it needs Windows 8.1 or later). Re-plug the DAC and " +
            "turn Exclusive USB DAC off and on."
    }
}
