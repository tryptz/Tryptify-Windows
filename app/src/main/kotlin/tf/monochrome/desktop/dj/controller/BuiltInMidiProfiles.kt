package tf.monochrome.desktop.dj.controller

import tf.monochrome.desktop.dj.controller.MidiAction.CLEAR_HOT_CUE
import tf.monochrome.desktop.dj.controller.MidiAction.CROSSFADER
import tf.monochrome.desktop.dj.controller.MidiAction.CUE
import tf.monochrome.desktop.dj.controller.MidiAction.EQ_HIGH
import tf.monochrome.desktop.dj.controller.MidiAction.EQ_LOW
import tf.monochrome.desktop.dj.controller.MidiAction.EQ_MID
import tf.monochrome.desktop.dj.controller.MidiAction.FILTER
import tf.monochrome.desktop.dj.controller.MidiAction.HOT_CUE
import tf.monochrome.desktop.dj.controller.MidiAction.JOG
import tf.monochrome.desktop.dj.controller.MidiAction.JOG_TOUCH
import tf.monochrome.desktop.dj.controller.MidiAction.KEYLOCK
import tf.monochrome.desktop.dj.controller.MidiAction.LOAD
import tf.monochrome.desktop.dj.controller.MidiAction.LOOP_IN
import tf.monochrome.desktop.dj.controller.MidiAction.LOOP_OUT
import tf.monochrome.desktop.dj.controller.MidiAction.PLAY
import tf.monochrome.desktop.dj.controller.MidiAction.RELOOP
import tf.monochrome.desktop.dj.controller.MidiAction.START
import tf.monochrome.desktop.dj.controller.MidiAction.SYNC
import tf.monochrome.desktop.dj.controller.MidiAction.TEMPO
import tf.monochrome.desktop.dj.controller.MidiAction.TRIM
import tf.monochrome.desktop.dj.controller.MidiAction.VOLUME

/**
 * The MIDI controllers that work out of the box. A port matches the first
 * profile whose [MidiProfile.devices] its name contains; one the user learned
 * or imported for it comes before any of these.
 */
object BuiltInMidiProfiles {

    /**
     * Pioneer DJ's DDJ-400 and the DDJ-FLX4 after it, which kept its MIDI.
     * From Pioneer's MIDI message list, as Mixxx's mapping reads it; not yet
     * checked on the hardware, so unverified.
     *
     * Deck 1 is MIDI channel 1, deck 2 channel 2; the mixer's shared controls
     * are on channel 7, and the pads (in HOT CUE mode, as the controller
     * starts) on 8 and 10. Every fader and knob is 14-bit, its LSB on the CC
     * 32 above. With SHIFT held the controller sends other notes, so SHIFT
     * layers are bindings of their own.
     */
    val DDJ_400: MidiProfile = run {
        val bindings = mutableListOf<MidiBinding>()
        val leds = mutableListOf<MidiLed>()
        fun note(channel: Int, number: Int) = MidiKey(MidiType.NOTE, channel, number)
        fun cc(channel: Int, number: Int) = MidiKey(MidiType.CC, channel, number)
        fun bind(key: MidiKey, action: MidiAction, unit: Int, slot: Int = 0, lit: Boolean = true) {
            bindings += MidiBinding(key, action, unit, slot)
            val light = action.light
            if (lit && light != null) leds += MidiLed(key, light, unit, slot)
        }
        fun fader(channel: Int, msb: Int, action: MidiAction, unit: Int) {
            bindings += MidiBinding(cc(channel, msb), action, unit, lsb = msb + 0x20)
        }

        for (d in 0..1) {
            val ch = d
            bind(note(ch, 0x0B), PLAY, d)
            bind(note(ch, 0x0C), CUE, d)
            bind(note(ch, 0x58), SYNC, d)
            bind(note(ch, 0x47), KEYLOCK, d, lit = false) // SHIFT+PLAY
            bind(note(ch, 0x48), START, d) // SHIFT+CUE
            bind(note(ch, 0x10), LOOP_IN, d)
            bind(note(ch, 0x11), LOOP_OUT, d)
            bind(note(ch, 0x4D), RELOOP, d)

            fader(ch, 0x00, TEMPO, d)
            fader(ch, 0x13, VOLUME, d)
            fader(ch, 0x04, TRIM, d)
            fader(ch, 0x07, EQ_HIGH, d)
            fader(ch, 0x0B, EQ_MID, d)
            fader(ch, 0x0F, EQ_LOW, d)
            fader(6, 0x17 + d, FILTER, d)

            // The platter: touched on top it scratches, turned by its edge it bends; both offset from 64.
            bind(note(ch, 0x36), JOG_TOUCH, d)
            for (number in intArrayOf(0x22, 0x21)) {
                bindings += MidiBinding(cc(ch, number), JOG, d, encoding = Relative.OFFSET_64, stepsPerTurn = DDJ_STEPS_PER_TURN)
            }

            bind(note(6, 0x46 + d), LOAD, d, lit = false)

            val pads = if (d == 0) 7 else 9
            for (p in 0 until 8) {
                bind(note(pads, p), HOT_CUE, d, p)
                bind(note(pads, 0x08 + p), CLEAR_HOT_CUE, d, p) // SHIFT+pad
            }
            leds += MidiLed(cc(ch, 0x02), MidiLight.METER, d)
        }
        fader(6, 0x1F, CROSSFADER, 0)
        bindings += MidiBinding(cc(6, 0x40), MidiAction.BROWSE, encoding = Relative.TWOS_COMPLEMENT)

        MidiProfile(
            name = "Pioneer DJ DDJ-400 / DDJ-FLX4",
            devices = listOf("DDJ-400", "DDJ-FLX4"),
            bindings = bindings,
            leds = leds,
            verified = false,
        )
    }

    /** Mixxx's scratch setting for the DDJ-400's platters. */
    private const val DDJ_STEPS_PER_TURN = 720

    val ALL: List<MidiProfile> = listOf(DDJ_400)

    fun forPort(port: String): MidiProfile? = ALL.firstOrNull { it.matches(port) }
}
