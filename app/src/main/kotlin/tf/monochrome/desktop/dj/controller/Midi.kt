package tf.monochrome.desktop.dj.controller

import java.io.Closeable
import java.io.IOException
import kotlinx.serialization.Serializable

/**
 * USB MIDI, reduced to what a DJ controller needs: its input ports by name,
 * the three-byte messages they send, and the same messages sent back to light
 * its LEDs.
 *
 * Unlike HID, MIDI needs no driver per controller and no layout read off the
 * hardware: every controller speaks the same messages, and what each one
 * means is a [MidiProfile], built in, learned, or imported from Mixxx.
 *
 * The implementation is platform/JavaMidiBus (javax.sound.midi, which on
 * Windows is WinMM); tests drive [MidiControllers] with a fake bus.
 */
interface MidiBus {
    /** The MIDI input ports present now, by name, each name unique. */
    fun inputs(): List<String>

    /**
     * Opens input [name], and the output of the same name if there is one,
     * for the LEDs. [receive] gets each channel message (status 0x80..0xEF)
     * on the transport's own thread.
     */
    @Throws(MidiOpenException::class)
    fun open(name: String, receive: (status: Int, data1: Int, data2: Int) -> Unit): MidiConnection
}

interface MidiConnection : Closeable {
    /** Whether there is an output to [send] to. */
    val hasOutput: Boolean

    /** Sends one channel message; false when there is no output or it would not take it. */
    fun send(status: Int, data1: Int, data2: Int): Boolean
}

/** The port is there but would not open; [busy] when another program holds it. */
class MidiOpenException(val busy: Boolean, message: String) : IOException(message)

/** Where the platform has no MIDI: no ports, so nothing connects. */
object NoMidiBus : MidiBus {
    override fun inputs(): List<String> = emptyList()
    override fun open(name: String, receive: (Int, Int, Int) -> Unit): MidiConnection =
        throw MidiOpenException(busy = false, "no MIDI")
}

// ── What a profile is made of ─────────────────────────────────────────

@Serializable
enum class MidiType { NOTE, CC, PITCH_BEND }

/**
 * One control's messages: a note (on and off alike), a control change, or a
 * channel's pitch bend. [channel] is 0..15, as on the wire; people count
 * them from 1, which [toString] does.
 */
@Serializable
data class MidiKey(val type: MidiType, val channel: Int, val number: Int = 0) {
    /** The status byte a message to this key is sent with: a note on, for a note. */
    val status: Int
        get() = channel or when (type) {
            MidiType.NOTE -> 0x90
            MidiType.CC -> 0xB0
            MidiType.PITCH_BEND -> 0xE0
        }

    override fun toString() = when (type) {
        MidiType.NOTE -> "CH${channel + 1} NOTE %02X".format(number)
        MidiType.CC -> "CH${channel + 1} CC %02X".format(number)
        MidiType.PITCH_BEND -> "CH${channel + 1} PITCH"
    }

    companion object {
        /** The key a message is for; null for one that is not a note, CC or pitch bend. */
        fun of(status: Int, data1: Int): MidiKey? {
            val channel = status and 0x0F
            return when (status and 0xF0) {
                0x80, 0x90 -> MidiKey(MidiType.NOTE, channel, data1)
                0xB0 -> MidiKey(MidiType.CC, channel, data1)
                0xE0 -> MidiKey(MidiType.PITCH_BEND, channel)
                else -> null
            }
        }
    }
}

/** What a light shows. Each is on or off but [METER], which is a level. */
@Serializable
enum class MidiLight {
    PLAYING, CUE, SYNC_LOCK, KEYLOCK, QUANTIZE, HOT_CUE, LOOP, LOADED, FX_ON, FX_ASSIGNED, METER,
}

/**
 * What a control does. Its [kind] says how its messages read: a button
 * (pressed, released), an absolute knob or fader (a position), or a relative
 * encoder or jog wheel (steps). Its [scope] says what `unit` names: a deck
 * (0, 1), an FX unit (0, 1), or nothing. [light] is the LED a button
 * naturally shows, which learning it lights too.
 */
@Serializable
enum class MidiAction(val kind: Kind, val scope: Scope, val light: MidiLight? = null) {
    // A deck's buttons.
    PLAY(Kind.BUTTON, Scope.DECK, MidiLight.PLAYING),
    /** Set while paused, back to it while playing, held to preview. */
    CUE(Kind.BUTTON, Scope.DECK, MidiLight.CUE),
    /** Back to the start. */
    START(Kind.BUTTON, Scope.DECK),
    /** Matches tempo and phase once; held, locks; pressed while locked, unlocks. */
    SYNC(Kind.BUTTON, Scope.DECK, MidiLight.SYNC_LOCK),
    SYNC_LOCK(Kind.BUTTON, Scope.DECK, MidiLight.SYNC_LOCK),
    KEYLOCK(Kind.BUTTON, Scope.DECK, MidiLight.KEYLOCK),
    QUANTIZE(Kind.BUTTON, Scope.DECK, MidiLight.QUANTIZE),
    LOAD(Kind.BUTTON, Scope.DECK, MidiLight.LOADED),
    EJECT(Kind.BUTTON, Scope.DECK),
    /** Hot cue `slot` (0..7): set, or jumped to. */
    HOT_CUE(Kind.BUTTON, Scope.DECK, MidiLight.HOT_CUE),
    CLEAR_HOT_CUE(Kind.BUTTON, Scope.DECK),
    LOOP_IN(Kind.BUTTON, Scope.DECK, MidiLight.LOOP),
    LOOP_OUT(Kind.BUTTON, Scope.DECK, MidiLight.LOOP),
    /** A loop of the loop size, or out of the loop. */
    LOOP(Kind.BUTTON, Scope.DECK, MidiLight.LOOP),
    RELOOP(Kind.BUTTON, Scope.DECK, MidiLight.LOOP),
    LOOP_HALVE(Kind.BUTTON, Scope.DECK),
    LOOP_DOUBLE(Kind.BUTTON, Scope.DECK),
    JUMP_BACK(Kind.BUTTON, Scope.DECK),
    JUMP_FORWARD(Kind.BUTTON, Scope.DECK),
    /** The jog wheel's top touched: it scratches while held, bends while not. */
    JOG_TOUCH(Kind.BUTTON, Scope.DECK),

    // A deck's knobs and faders.
    VOLUME(Kind.ABSOLUTE, Scope.DECK),
    /** Higher is faster, unless inverted. */
    TEMPO(Kind.ABSOLUTE, Scope.DECK),
    TRIM(Kind.ABSOLUTE, Scope.DECK),
    EQ_HIGH(Kind.ABSOLUTE, Scope.DECK),
    EQ_MID(Kind.ABSOLUTE, Scope.DECK),
    EQ_LOW(Kind.ABSOLUTE, Scope.DECK),
    FILTER(Kind.ABSOLUTE, Scope.DECK),

    JOG(Kind.RELATIVE, Scope.DECK),

    // The mixer and the browser.
    CROSSFADER(Kind.ABSOLUTE, Scope.GLOBAL),
    BROWSE(Kind.RELATIVE, Scope.GLOBAL),

    // An FX unit: `unit` is the unit, `slot` (0..2) the effect.
    FX_TOGGLE(Kind.BUTTON, Scope.FX, MidiLight.FX_ON),
    FX_ASSIGN(Kind.BUTTON, Scope.FX, MidiLight.FX_ASSIGNED),
    FX_MIX(Kind.ABSOLUTE, Scope.FX),
    FX_AMOUNT(Kind.ABSOLUTE, Scope.FX),
    ;

    enum class Kind { BUTTON, ABSOLUTE, RELATIVE }
    enum class Scope { DECK, FX, GLOBAL }

    /** Centred at rest: the middle of its travel is exactly the middle of its range. */
    val bipolar: Boolean get() = this in BIPOLAR

    /** Whether [slot] means something to it. */
    val slotted: Boolean get() = this == HOT_CUE || this == CLEAR_HOT_CUE || this == FX_TOGGLE || this == FX_AMOUNT

    private companion object {
        val BIPOLAR = setOf(TEMPO, TRIM, EQ_HIGH, EQ_MID, EQ_LOW, FILTER, CROSSFADER)
    }
}

/** How a relative control says how far it turned. */
@Serializable
enum class Relative {
    /** 1..63 forwards, 127..65 back (127 is -1). Most encoders. */
    TWOS_COMPLEMENT,
    /** 64 is still: 65 is +1, 63 is -1. Most jog wheels. */
    OFFSET_64,
    /** Bit 6 is the sign: 0x01 is +1, 0x41 is -1. */
    SIGN_BIT,
    ;

    fun steps(value: Int): Int = when (this) {
        TWOS_COMPLEMENT -> if (value < 64) value else value - 128
        OFFSET_64 -> value - 64
        SIGN_BIT -> if (value and 0x40 != 0) -(value and 0x3F) else value and 0x3F
    }
}

/** A control and what it does: [action] on deck or FX unit [unit], [slot] where it takes one. */
data class MidiTarget(val action: MidiAction, val unit: Int = 0, val slot: Int = 0)

@Serializable
data class MidiBinding(
    val key: MidiKey,
    val action: MidiAction,
    val unit: Int = 0,
    val slot: Int = 0,
    /** Turned around: a knob or fader read from the other end, an encoder the other way. */
    val invert: Boolean = false,
    val encoding: Relative = Relative.TWOS_COMPLEMENT,
    /**
     * A 14-bit control: the CC (on [key]'s channel) carrying its low 7 bits,
     * [key] carrying the high. By convention the high's number plus 32.
     */
    val lsb: Int? = null,
    /** A jog wheel's steps in one turn of it. */
    val stepsPerTurn: Int = DEFAULT_STEPS_PER_TURN,
) {
    val target: MidiTarget get() = MidiTarget(action, unit, slot)

    /** The low half's key, for a 14-bit control. */
    val lsbKey: MidiKey? get() = lsb?.let { MidiKey(MidiType.CC, key.channel, it) }

    companion object {
        /** The DDJ-400's; where a controller's is unknown, a middling guess (they run 128..2048). */
        const val DEFAULT_STEPS_PER_TURN = 720
    }
}

@Serializable
data class MidiLed(
    val key: MidiKey,
    val light: MidiLight,
    val unit: Int = 0,
    val slot: Int = 0,
    val on: Int = 0x7F,
    val off: Int = 0x00,
)

/**
 * What a MIDI controller's messages mean on the console, and what its LEDs
 * show. [devices] are the port names it is for: a port matches when its name
 * contains one, ignoring case (Windows calls a second DDJ-400 "2- DDJ-400").
 */
@Serializable
data class MidiProfile(
    val name: String,
    val devices: List<String> = emptyList(),
    val bindings: List<MidiBinding> = emptyList(),
    val leds: List<MidiLed> = emptyList(),
    /**
     * False for a built-in profile written from the maker's documentation and
     * not yet checked on the hardware: the screen says so.
     */
    val verified: Boolean = true,
) {
    fun matches(port: String): Boolean = devices.any { port.contains(it, ignoreCase = true) }

    /**
     * With [binding] learned: it replaces whatever was on the same messages,
     * and whatever did the same thing (so learning PLAY on another button
     * moves it), and lights the LED a button naturally has.
     */
    fun learn(binding: MidiBinding): MidiProfile {
        val keys = setOfNotNull(binding.key, binding.lsbKey)
        val (gone, kept) = bindings.partition { b ->
            b.key in keys || b.lsbKey in keys || b.target == binding.target
        }
        val goneKeys = gone.map { it.key }.toSet()
        val light = binding.action.light
        val leds = this.leds.filterNot { it.key in goneKeys || it.key == binding.key } +
            listOfNotNull(light?.let { MidiLed(binding.key, it, binding.unit, binding.slot) })
        return copy(bindings = kept + binding, leds = leds)
    }

    /** Without [binding], and the LED on its key. */
    fun forget(binding: MidiBinding): MidiProfile =
        copy(bindings = bindings - binding, leds = leds.filterNot { it.key == binding.key })
}
