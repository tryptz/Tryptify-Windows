package tf.monochrome.desktop.audio.sink

/**
 * How the audio leaves the app: the desktop's three routes to a device, and
 * the fallback. What the pipeline panel and the Settings card name.
 */
enum class OutputMode(val label: String) {
    /** Through the Windows mixer, which converts to the endpoint's mix format. */
    WASAPI_SHARED("WASAPI shared"),

    /** The endpoint opened at the stream's own rate and depth, past the mixer. */
    WASAPI_EXCLUSIVE("WASAPI exclusive"),

    /** libusb drives the DAC directly; no Windows audio driver involved. */
    USB_EXCLUSIVE("USB exclusive (libusb)"),

    /** The fallback when WASAPI is missing or refused, and the Linux path. */
    JAVA_SOUND("Java Sound"),
}

/** The route this sink is, following a [FallbackAudioSink] to whichever candidate carries the audio. */
fun AudioSink.outputMode(): OutputMode? = when (this) {
    is FallbackAudioSink -> active?.outputMode()
    is LibusbUacSink -> OutputMode.USB_EXCLUSIVE
    is WasapiSink -> if (isExclusive) OutputMode.WASAPI_EXCLUSIVE else OutputMode.WASAPI_SHARED
    is JavaSoundSink -> OutputMode.JAVA_SOUND
    else -> null
}
