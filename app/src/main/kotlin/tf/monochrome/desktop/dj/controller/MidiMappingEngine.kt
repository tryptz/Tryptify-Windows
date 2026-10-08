package tf.monochrome.desktop.dj.controller

import kotlin.math.abs
import kotlin.math.roundToInt
import tf.monochrome.desktop.dj.Deck
import tf.monochrome.desktop.dj.controller.MidiAction.Kind

/**
 * A [MidiProfile] played on the console: each message to the bindings on its
 * key, and the profile's LEDs to the controller.
 *
 * It reads messages as the S2's mapping reads its reports:
 *
 *  - A note on with velocity above 0, or a CC above 0, is a press; a note
 *    off, a note on at velocity 0, or a CC at 0, is a release. CUE, SYNC and
 *    JOG_TOUCH act on both; everything else on the press.
 *  - A knob or fader is 7 bits, or 14 when its binding names an LSB: the
 *    high half arrives first and is applied as it stands (the MIDI spec
 *    resets the low half to 0 then), and the low half refines it. Pitch bend
 *    is 14 bits in one message.
 *  - The centred controls (EQ, trim, filter, tempo, crossfader) map each
 *    half of their travel separately, so the detent (64, or 8192) is exactly
 *    flat: 0..127 has no middle.
 *  - Every knob and fader takes over softly ([SoftTakeover]).
 *
 * One thread only: [onMessage], [tick], [leds] and setting [profile] are all
 * the MIDI thread's.
 */
class MidiMappingEngine(
    private val dj: DjSurface,
    profile: MidiProfile,
    /** See [S2Mk1Mapping.invertTempo]; on top of each binding's own invert. */
    private val invertTempo: () -> Boolean = { false },
) {
    private class Control(val binding: MidiBinding) {
        val takeover = SoftTakeover(if (binding.action == MidiAction.TEMPO || binding.action == MidiAction.CROSSFADER) WIDE else NARROW)
        /** A 14-bit control's high half, as last received. */
        var msb = 0
        /** The tempo's direction as last read: flipped, the fader is a stranger again. */
        var inverted = false
    }

    private var byKey: Map<MidiKey, List<Control>> = emptyMap()
    private var byLsb: Map<MidiKey, List<Control>> = emptyMap()
    private val cueHeld = BooleanArray(DECKS)
    private val jogHeld = BooleanArray(DECKS)
    /** When SYNC went down, while it is held and may yet lock; -1 otherwise. */
    private val syncDownAt = LongArray(DECKS) { -1L }
    /** The value each LED was last sent, -1 for none yet. */
    private var sent = IntArray(0)

    /** Swapping it forgets every control's state, and sends every LED again. */
    var profile: MidiProfile = profile
        set(value) {
            field = value
            index()
        }

    init {
        index()
    }

    private fun index() {
        val controls = profile.bindings.filter(::valid).map(::Control)
        byKey = controls.groupBy { it.binding.key }
        byLsb = controls.filter { it.binding.lsb != null }.groupBy { it.binding.lsbKey!! }
        reset()
    }

    /** Forget the controller's state, for a fresh connection. */
    fun reset() {
        for (c in byKey.values.flatten()) {
            c.takeover.reset()
            c.msb = 0
        }
        cueHeld.fill(false)
        jogHeld.fill(false)
        syncDownAt.fill(-1L)
        sent = IntArray(profile.leds.size) { -1 }
    }

    /**
     * Lets go of what the controller is holding: a CUE previewing, a platter
     * touched. For a controller unplugged mid-gesture, which never sends the
     * release: without this the deck would preview, or scratch, for ever.
     */
    fun release() {
        for (i in cueHeld.indices) {
            if (cueHeld[i]) {
                dj.decks[i].cueUp()
                cueHeld[i] = false
            }
            if (jogHeld[i]) {
                dj.decks[i].jogTouched = false
                jogHeld[i] = false
            }
        }
        syncDownAt.fill(-1L)
    }

    /** One channel message. [now] is a monotonic clock in ms. */
    fun onMessage(status: Int, data1: Int, data2: Int, now: Long) {
        val key = MidiKey.of(status, data1) ?: return
        byKey[key]?.forEach { handle(it, status, data1, data2, now) }
        if (key.type == MidiType.CC) {
            byLsb[key]?.forEach { absolute(it, (it.msb shl 7) or data2, 14) }
        }
    }

    /** Time passing without a message: SYNC held long enough locks. */
    fun tick(now: Long) {
        for (i in syncDownAt.indices) {
            if (syncDownAt[i] >= 0 && now - syncDownAt[i] >= S2Mk1Mapping.SYNC_HOLD_MS) {
                syncDownAt[i] = -1
                if (!dj.decks[i].syncLock) dj.setSyncLock(i, true)
            }
        }
    }

    private fun handle(c: Control, status: Int, data1: Int, data2: Int, now: Long) {
        val b = c.binding
        when (b.action.kind) {
            Kind.BUTTON -> button(b, down = (status and 0xF0) != 0x80 && data2 > 0, now)
            Kind.ABSOLUTE -> when {
                b.key.type == MidiType.PITCH_BEND -> absolute(c, (data2 shl 7) or data1, 14)
                b.lsb != null -> {
                    c.msb = data2
                    absolute(c, data2 shl 7, 14)
                }
                else -> absolute(c, data2, 7)
            }
            Kind.RELATIVE -> relative(b, b.encoding.steps(data2).let { if (b.invert) -it else it })
        }
    }

    // ── Buttons ───────────────────────────────────────────────────────

    private fun button(b: MidiBinding, down: Boolean, now: Long) {
        val i = b.unit
        when (b.action) {
            MidiAction.CUE -> {
                val d = dj.decks[i]
                if (down) {
                    d.cueDown()
                    cueHeld[i] = true
                } else if (cueHeld[i]) {
                    d.cueUp()
                    cueHeld[i] = false
                }
                return
            }
            MidiAction.SYNC -> {
                if (!down) {
                    syncDownAt[i] = -1
                } else if (dj.decks[i].syncLock) {
                    dj.setSyncLock(i, false)
                } else {
                    dj.decks[i].sync()
                    syncDownAt[i] = now
                }
                return
            }
            MidiAction.JOG_TOUCH -> {
                dj.decks[i].jogTouched = down
                jogHeld[i] = down
                return
            }
            else -> if (!down) return
        }
        when (b.action) {
            MidiAction.PLAY -> deck(b).togglePlay()
            MidiAction.START -> deck(b).rewind()
            MidiAction.SYNC_LOCK -> dj.setSyncLock(i, !deck(b).syncLock)
            MidiAction.KEYLOCK -> deck(b).let { it.keylock = !it.keylock }
            MidiAction.QUANTIZE -> deck(b).let { it.quantize = !it.quantize }
            MidiAction.LOAD -> dj.loadSelected(i)
            MidiAction.EJECT -> deck(b).eject()
            MidiAction.HOT_CUE -> deck(b).hotCue(b.slot)
            MidiAction.CLEAR_HOT_CUE -> deck(b).clearHotCue(b.slot)
            MidiAction.LOOP_IN -> deck(b).loopInHere()
            MidiAction.LOOP_OUT -> deck(b).loopOutHere()
            MidiAction.LOOP -> deck(b).toggleAutoLoop()
            MidiAction.RELOOP -> deck(b).reloop()
            MidiAction.LOOP_HALVE -> deck(b).resizeLoop(-1)
            MidiAction.LOOP_DOUBLE -> deck(b).resizeLoop(1)
            MidiAction.JUMP_BACK -> deck(b).let { it.beatJump(-it.jumpBeats) }
            MidiAction.JUMP_FORWARD -> deck(b).let { it.beatJump(it.jumpBeats) }
            MidiAction.FX_TOGGLE -> dj.toggleFx(b.unit, b.slot)
            MidiAction.FX_ASSIGN -> dj.toggleFxAssign(b.unit)
            else -> Unit
        }
    }

    // ── Knobs and faders ──────────────────────────────────────────────

    private fun absolute(c: Control, raw: Int, bits: Int) {
        val b = c.binding
        var v = if (b.action.bipolar) centred(raw, bits) else raw.toFloat() / ((1 shl bits) - 1)
        if (b.invert) v = 1f - v
        val t = c.takeover
        when (b.action) {
            MidiAction.VOLUME -> deck(b).let { if (t.accept(v, it.volume)) it.volume = v }
            MidiAction.TRIM -> deck(b).let { if (t.accept(v, it.trim)) it.trim = v }
            MidiAction.EQ_HIGH -> deck(b).let { if (t.accept(v, it.eqHigh)) it.eqHigh = v }
            MidiAction.EQ_MID -> deck(b).let { if (t.accept(v, it.eqMid)) it.eqMid = v }
            MidiAction.EQ_LOW -> deck(b).let { if (t.accept(v, it.eqLow)) it.eqLow = v }
            MidiAction.FILTER -> deck(b).let { d ->
                val f = 2f * v - 1f
                if (t.accept(f, d.filter)) d.filter = f
            }
            MidiAction.TEMPO -> deck(b).let { d ->
                val invert = invertTempo()
                if (invert != c.inverted) {
                    c.inverted = invert
                    t.reset()
                }
                val position = if (invert) 1f - 2f * v else 2f * v - 1f
                if (t.accept(position, d.tempoFader)) d.setTempoFader(position)
            }
            MidiAction.CROSSFADER -> {
                val x = 2f * v - 1f
                if (t.accept(x, dj.crossfader)) dj.crossfader = x
            }
            MidiAction.FX_MIX -> if (t.accept(v, dj.fxMix(b.unit))) dj.setFxMix(b.unit, v)
            MidiAction.FX_AMOUNT -> if (t.accept(v, dj.fxAmount(b.unit, b.slot))) dj.setFxAmount(b.unit, b.slot, v)
            else -> Unit
        }
    }

    // ── Encoders and jog wheels ───────────────────────────────────────

    private fun relative(b: MidiBinding, steps: Int) {
        if (steps == 0) return
        when (b.action) {
            MidiAction.JOG -> deck(b).jog(steps.toDouble() / b.stepsPerTurn.coerceAtLeast(1))
            MidiAction.BROWSE -> dj.browse(steps)
            else -> Unit
        }
    }

    // ── The LEDs ──────────────────────────────────────────────────────

    /** Sends each LED whose value changed since it was last sent: an idle console sends nothing. */
    fun leds(send: (status: Int, data1: Int, data2: Int) -> Unit) {
        profile.leds.forEachIndexed { k, led ->
            if (led.key.type == MidiType.PITCH_BEND || !valid(led)) return@forEachIndexed
            val v = value(led)
            if (v != sent[k]) {
                send(led.key.status, led.key.number, v)
                sent[k] = v
            }
        }
    }

    /** Every LED off, for a controller nothing drives any more. */
    fun dark(send: (status: Int, data1: Int, data2: Int) -> Unit) {
        for (led in profile.leds) if (led.key.type != MidiType.PITCH_BEND) send(led.key.status, led.key.number, led.off.coerceIn(0, 127))
        sent.fill(-1)
    }

    private fun value(led: MidiLed): Int {
        fun lit(on: Boolean) = if (on) led.on else led.off
        val v = when (led.light) {
            MidiLight.METER -> {
                val d = dj.decks[led.unit]
                led.off + ((led.on - led.off) * S2Mk1Mapping.meterLevel(d.peak)).roundToInt()
            }
            MidiLight.FX_ON -> lit(dj.fxOn(led.unit, led.slot))
            MidiLight.FX_ASSIGNED -> lit(dj.fxAssigned(led.unit))
            else -> {
                val d = dj.decks[led.unit]
                val loaded = d.track != null
                lit(
                    when (led.light) {
                        MidiLight.PLAYING -> d.playing
                        MidiLight.CUE -> loaded && !d.playing && abs(d.playPosition - d.cuePoint) < 1.0
                        MidiLight.SYNC_LOCK -> d.syncLock
                        MidiLight.KEYLOCK -> d.keylock
                        MidiLight.QUANTIZE -> d.quantize
                        MidiLight.HOT_CUE -> !d.hotCues[led.slot].isNaN()
                        MidiLight.LOOP -> d.loopActive
                        MidiLight.LOADED -> loaded
                        else -> false
                    },
                )
            }
        }
        return v.coerceIn(0, 127)
    }

    // ── Checks ────────────────────────────────────────────────────────

    private fun deck(b: MidiBinding): Deck = dj.decks[b.unit]

    /** A binding from a file can name anything: those that point nowhere are left out. */
    private fun valid(b: MidiBinding): Boolean {
        val a = b.action
        val units = when (a.scope) {
            MidiAction.Scope.DECK -> minOf(DECKS, dj.decks.size)
            MidiAction.Scope.FX -> FX_UNITS
            MidiAction.Scope.GLOBAL -> 1
        }
        val slots = when (a) {
            MidiAction.HOT_CUE, MidiAction.CLEAR_HOT_CUE -> Deck.HOT_CUES
            MidiAction.FX_TOGGLE, MidiAction.FX_AMOUNT -> FX_SLOTS
            else -> 1
        }
        return b.unit in 0 until units && b.slot in 0 until slots && b.key.channel in 0..15 && b.key.number in 0..127 &&
            (b.lsb == null || b.lsb in 0..127)
    }

    private fun valid(led: MidiLed): Boolean {
        val units = when (led.light) {
            MidiLight.FX_ON, MidiLight.FX_ASSIGNED -> FX_UNITS
            else -> minOf(DECKS, dj.decks.size)
        }
        val slots = when (led.light) {
            MidiLight.HOT_CUE -> Deck.HOT_CUES
            MidiLight.FX_ON -> FX_SLOTS
            else -> 1
        }
        return led.unit in 0 until units && led.slot in 0 until slots && led.key.channel in 0..15 && led.key.number in 0..127
    }

    companion object {
        private const val DECKS = 2
        private const val FX_UNITS = 2
        private const val FX_SLOTS = 3
        /** As on the S2: the -1..1 controls get twice the 0..1 ones' threshold. */
        private const val WIDE = 0.06f
        private const val NARROW = 0.03f

        /**
         * A centred control's raw value as 0..1, each half on its own: 0 is 0,
         * the detent (half the range) is exactly 0.5, the top is 1.
         */
        fun centred(raw: Int, bits: Int): Float {
            val max = (1 shl bits) - 1
            val half = 1 shl (bits - 1)
            val r = raw.coerceIn(0, max)
            return if (r <= half) r.toFloat() / (2 * half) else 0.5f + (r - half).toFloat() / (2 * (max - half))
        }
    }
}
