package tf.monochrome.desktop.dj.controller

import kotlin.math.floor

/**
 * The Native Instruments Traktor Kontrol S2 (MK1) on USB HID: what its
 * reports mean, and the LED report it takes.
 *
 * The S2 MK1 is a HID device, not a MIDI one: Windows' own HID driver
 * reads it, no NI driver needed (the NI driver is only for its sound card).
 * It sends two input reports whenever something moves:
 *
 *  - 0x01, the buttons and the two jog wheels' tick counters;
 *  - 0x02, every knob and fader as a 12-bit value (in a 16-bit
 *    little-endian field), the four-bit encoders, and the platters' touch
 *    sensors.
 *
 * and takes one output report, 0x80, a brightness (0..0x1F) per LED.
 *
 * Knobs and faders don't use their full range, and each unit's range is
 * different: the controller stores its own calibration in feature reports
 * 0xD0..0xD4 (fader ends; knob left, centre and right; the platters'
 * touched threshold), which [Calibration] reads.
 *
 * The layout is Mixxx's (res/controllers/Traktor-Kontrol-S2-MK1-hid-scripts.js),
 * which was read off the hardware. Every offset here counts the report ID
 * as byte 0, as that script does and as Windows' ReadFile hands a report
 * over; the LED offsets too, so the 61-byte payload's first byte is
 * offset 1.
 */
object TraktorS2Mk1 {
    const val VENDOR_ID = 0x17CC
    const val PRODUCT_ID = 0x1101
    const val NAME = "Traktor Kontrol S2 MK1"

    const val REPORT_BUTTONS = 0x01
    const val REPORT_ANALOG = 0x02
    const val REPORT_LEDS = 0x80
    /** The LED report without its ID byte. */
    const val LED_PAYLOAD = 61
    /** Feature reports, each 32 bytes after the ID: faders, three of knobs, the platters. */
    val CALIBRATION_REPORTS = intArrayOf(0xD0, 0xD1, 0xD2, 0xD3, 0xD4)
    const val CALIBRATION_BYTES = 32

    /** The jog wheel's counter: its low byte counts 1024 to a turn and wraps every 256. */
    const val JOG_TICKS_PER_REV = 1024

    /** LED brightness: off and full (the hardware has 32 steps). */
    const val OFF = 0x00
    const val ON = 0x1F

    /** A button: the bit [mask] of byte [offset] of report 0x01. */
    class Bit(val offset: Int, val mask: Int)

    /** The controls of one deck, and its LEDs. */
    class DeckLayout(
        // Report 0x01
        val shift: Bit, val sync: Bit, val cue: Bit, val play: Bit,
        val pads: List<Bit>,
        val loopIn: Bit, val loopOut: Bit, val samples: Bit, val reset: Bit,
        val leftPress: Bit, val rightPress: Bit, val gainPress: Bit,
        val load: Bit, val pfl: Bit,
        val jog: Int,
        // Report 0x02: 16-bit values, and encoders as (offset, high nibble?)
        val rate: Int, val volume: Int, val jogTouch: Int,
        val eqHigh: Int, val eqMid: Int, val eqLow: Int,
        val leftEncoder: Nibble, val rightEncoder: Nibble, val gainEncoder: Nibble,
        // Report 0x80
        val ledLoaded: Int, val ledMeter: IntArray, val ledPeak: Int,
        val ledReset: Int, val ledLoopIn: Int, val ledLoopOut: Int, val ledPfl: Int, val ledSamples: Int,
        val ledShift: Int, val ledSync: Int, val ledCue: Int, val ledPlay: Int,
        /** Each pad's green and blue LED. */
        val ledPadGreen: IntArray, val ledPadBlue: IntArray,
    )

    /** A four-bit encoder in byte [offset] of report 0x02: the high nibble or the low one. */
    class Nibble(val offset: Int, val high: Boolean)

    class FxLayout(
        val focus: Bit, val buttons: List<Bit>, val assign: List<Bit>,
        val mix: Int, val knobs: IntArray,
        val ledFocus: Int, val ledButtons: IntArray, val ledAssign: IntArray,
    )

    val DECKS = listOf(
        DeckLayout(
            shift = Bit(0x0D, 0x80), sync = Bit(0x0D, 0x40), cue = Bit(0x0D, 0x20), play = Bit(0x0D, 0x10),
            pads = listOf(Bit(0x0D, 0x08), Bit(0x0D, 0x04), Bit(0x0D, 0x02), Bit(0x0D, 0x01)),
            loopIn = Bit(0x09, 0x40), loopOut = Bit(0x09, 0x20), samples = Bit(0x0B, 0x02), reset = Bit(0x09, 0x10),
            leftPress = Bit(0x0E, 0x02), rightPress = Bit(0x0E, 0x04), gainPress = Bit(0x0E, 0x01),
            load = Bit(0x0B, 0x08), pfl = Bit(0x09, 0x80),
            jog = 0x01,
            rate = 0x0F, volume = 0x2B, jogTouch = 0x0D,
            eqHigh = 0x11, eqMid = 0x25, eqLow = 0x27,
            leftEncoder = Nibble(0x01, high = true), rightEncoder = Nibble(0x02, high = false), gainEncoder = Nibble(0x01, high = false),
            ledLoaded = 0x1F, ledMeter = intArrayOf(0x15, 0x16, 0x17, 0x18), ledPeak = 0x01,
            ledReset = 0x06, ledLoopIn = 0x02, ledLoopOut = 0x05, ledPfl = 0x20, ledSamples = 0x35,
            ledShift = 0x08, ledSync = 0x04, ledCue = 0x07, ledPlay = 0x03,
            ledPadGreen = intArrayOf(0x0C, 0x0B, 0x0A, 0x09), ledPadBlue = intArrayOf(0x10, 0x0F, 0x0E, 0x0D),
        ),
        DeckLayout(
            shift = Bit(0x0C, 0x80), sync = Bit(0x0C, 0x40), cue = Bit(0x0C, 0x20), play = Bit(0x0C, 0x10),
            pads = listOf(Bit(0x0C, 0x08), Bit(0x0C, 0x04), Bit(0x0C, 0x02), Bit(0x0C, 0x01)),
            loopIn = Bit(0x0B, 0x40), loopOut = Bit(0x0B, 0x20), samples = Bit(0x0B, 0x01), reset = Bit(0x0B, 0x10),
            leftPress = Bit(0x0E, 0x20), rightPress = Bit(0x0E, 0x40), gainPress = Bit(0x0E, 0x10),
            load = Bit(0x0B, 0x04), pfl = Bit(0x0B, 0x80),
            jog = 0x05,
            rate = 0x1F, volume = 0x2D, jogTouch = 0x1D,
            eqHigh = 0x21, eqMid = 0x23, eqLow = 0x29,
            leftEncoder = Nibble(0x03, high = true), rightEncoder = Nibble(0x04, high = false), gainEncoder = Nibble(0x03, high = false),
            ledLoaded = 0x1E, ledMeter = intArrayOf(0x11, 0x12, 0x13, 0x14), ledPeak = 0x25,
            ledReset = 0x26, ledLoopIn = 0x22, ledLoopOut = 0x21, ledPfl = 0x1D, ledSamples = 0x34,
            ledShift = 0x28, ledSync = 0x24, ledCue = 0x27, ledPlay = 0x23,
            ledPadGreen = intArrayOf(0x2C, 0x2B, 0x2A, 0x29), ledPadBlue = intArrayOf(0x30, 0x2F, 0x2E, 0x2D),
        ),
    )

    val FX = listOf(
        FxLayout(
            focus = Bit(0x09, 0x08), buttons = listOf(Bit(0x09, 0x04), Bit(0x09, 0x02), Bit(0x09, 0x01)),
            assign = listOf(Bit(0x0A, 0x02), Bit(0x0A, 0x08)),
            mix = 0x0B, knobs = intArrayOf(0x09, 0x07, 0x05),
            ledFocus = 0x1C, ledButtons = intArrayOf(0x1B, 0x1A, 0x19), ledAssign = intArrayOf(0x3D, 0x3B),
        ),
        FxLayout(
            focus = Bit(0x0A, 0x80), buttons = listOf(Bit(0x0A, 0x40), Bit(0x0A, 0x20), Bit(0x0A, 0x10)),
            assign = listOf(Bit(0x0A, 0x01), Bit(0x0A, 0x04)),
            mix = 0x1B, knobs = intArrayOf(0x19, 0x17, 0x15),
            ledFocus = 0x39, ledButtons = intArrayOf(0x38, 0x37, 0x36), ledAssign = intArrayOf(0x3C, 0x3A),
        ),
    )

    val BROWSE_PRESS = Bit(0x0E, 0x08)
    val BROWSE_ENCODER = Nibble(0x02, high = true)
    const val CROSSFADER = 0x2F
    const val HEAD_MIX = 0x31
    const val SAMPLER_GAIN = 0x13
    const val LED_WARNING = 0x33

    /** The smallest reports that hold every field above, ID included. */
    const val BUTTONS_BYTES = 0x0F
    const val ANALOG_BYTES = 0x33

    // ── Calibration ────────────────────────────────────────────────────

    /** A fader's raw values at its two ends. */
    data class Fader(val min: Int, val max: Int) {
        /** [raw] as 0..1 along the fader. */
        fun map(raw: Int): Float = if (max == min) 0f else ((raw - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    }

    /**
     * A knob's raw values fully left, at its centre detent and fully right.
     * Each half maps on its own, so the detent reads exactly 0.5 (flat EQ)
     * even when the centre isn't halfway between the ends.
     */
    data class Knob(val min: Int, val centre: Int, val max: Int) {
        fun map(raw: Int): Float = when {
            raw <= centre -> if (centre == min) 0.5f else 0.5f * ((raw - min).toFloat() / (centre - min)).coerceIn(0f, 1f)
            else -> if (max == centre) 0.5f else 0.5f + 0.5f * ((raw - centre).toFloat() / (max - centre)).coerceIn(0f, 1f)
        }
    }

    class Calibration(
        val volume: List<Fader>,
        val crossfader: Fader,
        /** Per deck: high, mid, low. */
        val eq: List<List<Knob>>,
        val fxMix: List<Knob>,
        /** Per FX unit: its three knobs. */
        val fxKnobs: List<List<Knob>>,
        val sampler: Knob,
        /** Per deck: the platter's touch sensor reads above this when a hand is on it. */
        val jogTouched: IntArray,
    ) {
        companion object {
            /** The ends Mixxx scales the uncalibrated controls (rate, head mix) to. */
            val FULL = Fader(16, 4080)
            private val KNOB = Knob(16, 2048, 4080)

            /** For a controller whose feature reports can't be read. 0x0CE6 is a factory touch threshold. */
            val DEFAULT = Calibration(
                volume = List(2) { FULL }, crossfader = FULL,
                eq = List(2) { List(3) { KNOB } }, fxMix = List(2) { KNOB }, fxKnobs = List(2) { List(3) { KNOB } },
                sampler = KNOB, jogTouched = intArrayOf(0x0CE6, 0x0CE6),
            )

            /**
             * The calibration in feature reports 0xD0..0xD4, each passed
             * without its ID byte (32 bytes). The knob reports run on from
             * each other: one knob's three values can straddle two reports.
             */
            fun parse(faders: ByteArray, knobs1: ByteArray, knobs2: ByteArray, knobs3: ByteArray, jogs: ByteArray): Calibration {
                val knobs = ByteArray(CALIBRATION_BYTES * 3)
                knobs1.copyInto(knobs, 0, 0, minOf(knobs1.size, CALIBRATION_BYTES))
                knobs2.copyInto(knobs, CALIBRATION_BYTES, 0, minOf(knobs2.size, CALIBRATION_BYTES))
                knobs3.copyInto(knobs, CALIBRATION_BYTES * 2, 0, minOf(knobs3.size, CALIBRATION_BYTES))
                fun fader(i: Int) = Fader(u16le(faders, i), u16le(faders, i + 2))
                fun knob(i: Int) = Knob(u16le(knobs, i), u16le(knobs, i + 2), u16le(knobs, i + 4))
                return Calibration(
                    volume = listOf(fader(0x0C), fader(0x10)),
                    crossfader = fader(0x14),
                    eq = listOf(
                        listOf(knob(0x18), knob(0x1E), knob(0x24)),
                        listOf(knob(0x2A), knob(0x30), knob(0x36)),
                    ),
                    fxMix = listOf(knob(0x00), knob(0x42)),
                    fxKnobs = listOf(
                        listOf(knob(0x06), knob(0x0C), knob(0x12)),
                        listOf(knob(0x48), knob(0x4E), knob(0x54)),
                    ),
                    sampler = knob(0x3C),
                    // Big-endian, unlike the rest: unpressed then pressed, per platter.
                    jogTouched = intArrayOf(u16be(jogs, 0x02), u16be(jogs, 0x06)),
                )
            }
        }
    }

    // ── Reading ────────────────────────────────────────────────────────

    internal fun u8(b: ByteArray, i: Int): Int = if (i in b.indices) b[i].toInt() and 0xFF else 0
    internal fun u16le(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
    internal fun u16be(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)

    /**
     * One step of an encoder from [previous] to [value] (both 0..15):
     * +1 clockwise, -1 counter-clockwise, 0 for the first reading or a
     * jump of more than a step (a report was missed; guessing the
     * direction would jump the wrong way half the time).
     */
    fun encoderStep(previous: Int, value: Int): Int = when {
        previous < 0 -> 0
        (previous + 1) % 16 == value -> 1
        (value + 1) % 16 == previous -> -1
        else -> 0
    }

    /**
     * Ticks the jog counter moved from [previous] to [value] (its low
     * byte, which wraps at 256). Taken as the short way round: between two
     * reports a platter turns far less than a quarter turn (256 ticks).
     */
    fun jogTicks(previous: Int, value: Int): Int = ((value - previous + 128) and 0xFF) - 128

    /**
     * The VU meter's four segments for a level of [meter] (0..1): the ones
     * below lit fully, the one it falls in lit in proportion, the rest dark.
     */
    fun meterSegments(meter: Float, out: IntArray) {
        val scaled = meter.coerceIn(0f, 1f) * out.size
        val full = floor(scaled).toInt()
        for (i in out.indices) {
            out[i] = when {
                i < full -> ON
                i == full -> ((scaled - full) * ON).toInt()
                else -> OFF
            }
        }
    }
}
