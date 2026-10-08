package tf.monochrome.desktop.dj.controller

import tf.monochrome.desktop.dj.controller.TraktorS2Mk1.Bit
import tf.monochrome.desktop.dj.controller.TraktorS2Mk1.Nibble
import tf.monochrome.desktop.dj.controller.TraktorS2Mk1.u16le
import tf.monochrome.desktop.dj.controller.TraktorS2Mk1.u8
import kotlin.math.abs
import kotlin.math.log10

/**
 * What the S2 MK1's controls do on the console, and what its LEDs show.
 *
 * Each deck, as printed on the controller:
 *
 *  - PLAY plays and pauses; SHIFT+PLAY is keylock.
 *  - CUE is the usual cue (set while paused, back to it while playing, held
 *    to preview); SHIFT+CUE goes back to the start.
 *  - SYNC matches tempo and phase once; held, it locks sync on; pressed
 *    while locked, it unlocks. SHIFT+SYNC toggles the lock.
 *  - The four pads are hot cues 1-4, or 5-8 after SAMPLES (its LED lit);
 *    SHIFT+pad clears one.
 *  - IN and OUT set a loop's ends.
 *  - The left encoder jumps by the jump size; turned while pressed, it
 *    sets that size; with SHIFT, it jumps one beat.
 *  - The right encoder sets the loop size; pressed, a loop of that size;
 *    SHIFT+press is reloop; SHIFT+turn moves the loop by its length.
 *  - The GAIN encoder is the filter (pressed: centre); with SHIFT, the trim
 *    (SHIFT+press: 0 dB).
 *  - LOAD loads the browser's selection; SHIFT+LOAD ejects.
 *  - SHIFT+PFL toggles quantize.
 *  - The platter scratches while touched and bends while not; SHIFT makes
 *    it ten times faster.
 *
 * And the mixer: volume, EQ, crossfader; per FX unit, its three buttons and
 * knobs and its dry/wet. The browse encoder scrolls the browser (SHIFT: ten
 * at a time).
 *
 * Every knob and fader takes over softly ([SoftTakeover]): plugged in with a
 * fader down, the deck stays as loud as it was until that fader comes up to
 * it.
 *
 * One thread only: [onReport], [tick] and [leds] are all the controller's
 * reader thread's.
 */
class S2Mk1Mapping(private val dj: DjSurface) {

    /** Read from the controller's feature reports when it connects. */
    @Volatile var calibration: TraktorS2Mk1.Calibration = TraktorS2Mk1.Calibration.DEFAULT

    /**
     * The tempo faders' direction. Off, a fader pulled towards you is faster
     * (Technics, as Traktor and Mixxx have it out of the box); on, the other
     * way.
     */
    @Volatile var invertTempo: Boolean = false

    private class DeckState {
        var shift = false
        /** 0: pads are hot cues 1-4; 1: 5-8. */
        var padPage = 0
        var leftHeld = false
        var cueHeld = false
        /** The platter's top touched, as last read. */
        var jogHeld = false
        /** When SYNC went down, while it is held and may yet lock; -1 otherwise. */
        var syncDownAt = -1L
        var jog = -1
        var leftEncoder = -1
        var rightEncoder = -1
        var gainEncoder = -1
        val tempo = SoftTakeover(WIDE)
        /** The direction [tempo] last read the fader in: flipped, the fader is a stranger again. */
        var inverted = false
        val volume = SoftTakeover()
        val eq = Array(3) { SoftTakeover() }
    }

    private val decks = Array(2) { DeckState() }
    private val crossfader = SoftTakeover(WIDE)
    private val fxMix = Array(2) { SoftTakeover() }
    private val fxKnobs = Array(2) { Array(FX_SLOTS) { SoftTakeover() } }
    private var browseEncoder = -1
    private var buttons: ByteArray? = null
    private var analog: ByteArray? = null

    /** Forget the controller's state, for a fresh connection. */
    fun reset() {
        for (i in decks.indices) decks[i] = DeckState()
        crossfader.reset()
        fxMix.forEach { it.reset() }
        fxKnobs.forEach { u -> u.forEach { it.reset() } }
        browseEncoder = -1
        buttons = null
        analog = null
    }

    /**
     * Lets go of what the controller is holding: a CUE previewing, a platter
     * touched. For one unplugged mid-gesture, which never sends the release:
     * without this the deck would preview, or scratch, for ever.
     */
    fun release() {
        for (i in decks.indices) {
            val s = decks[i]
            if (s.cueHeld) {
                dj.decks[i].cueUp()
                s.cueHeld = false
            }
            if (s.jogHeld) {
                dj.decks[i].jogTouched = false
                s.jogHeld = false
            }
            s.syncDownAt = -1
        }
    }

    /** An input report as read, ID first. [now] is a monotonic clock in ms. */
    fun onReport(report: ByteArray, length: Int, now: Long) {
        if (length < 1) return
        when (u8(report, 0)) {
            TraktorS2Mk1.REPORT_BUTTONS -> if (length >= TraktorS2Mk1.BUTTONS_BYTES) onButtons(report.copyOf(length), now)
            TraktorS2Mk1.REPORT_ANALOG -> if (length >= TraktorS2Mk1.ANALOG_BYTES) onAnalog(report.copyOf(length))
        }
    }

    /** Time passing without a report: SYNC held long enough locks. */
    fun tick(now: Long) {
        for (i in decks.indices) {
            val s = decks[i]
            if (s.syncDownAt >= 0 && now - s.syncDownAt >= SYNC_HOLD_MS) {
                s.syncDownAt = -1
                if (!dj.decks[i].syncLock) dj.setSyncLock(i, true)
            }
        }
    }

    // ── Report 0x01: buttons and jogs ──────────────────────────────────

    private fun onButtons(r: ByteArray, now: Long) {
        val prev = buttons
        buttons = r
        fun on(b: Bit) = (u8(r, b.offset) and b.mask) != 0
        fun was(b: Bit) = prev != null && (u8(prev, b.offset) and b.mask) != 0
        // The first report only says what is already held: nothing was pressed.
        fun down(b: Bit) = prev != null && on(b) && !was(b)
        fun up(b: Bit) = prev != null && !on(b) && was(b)

        for (i in decks.indices) {
            val l = TraktorS2Mk1.DECKS[i]
            val s = decks[i]
            val d = dj.decks[i]
            s.shift = on(l.shift)
            s.leftHeld = on(l.leftPress)

            if (down(l.play)) if (s.shift) d.keylock = !d.keylock else d.togglePlay()

            if (down(l.cue)) {
                if (s.shift) d.rewind() else {
                    d.cueDown()
                    s.cueHeld = true
                }
            }
            if (up(l.cue) && s.cueHeld) {
                d.cueUp()
                s.cueHeld = false
            }

            if (down(l.sync)) when {
                s.shift -> dj.setSyncLock(i, !d.syncLock)
                d.syncLock -> dj.setSyncLock(i, false)
                else -> {
                    d.sync()
                    s.syncDownAt = now
                }
            }
            if (!on(l.sync)) s.syncDownAt = -1

            l.pads.forEachIndexed { p, bit ->
                if (down(bit)) {
                    val slot = s.padPage * l.pads.size + p
                    if (s.shift) d.clearHotCue(slot) else d.hotCue(slot)
                }
            }
            if (down(l.samples)) s.padPage = 1 - s.padPage
            // Desktop: RESET is free; the S2's own use for it (Traktor's sample decks) has no counterpart here.

            if (down(l.loopIn)) d.loopInHere()
            if (down(l.loopOut)) d.loopOutHere()
            if (down(l.rightPress)) if (s.shift) d.reloop() else d.toggleAutoLoop()
            if (down(l.gainPress)) if (s.shift) d.trim = 0.5f else d.filter = 0f

            if (down(l.load)) if (s.shift) d.eject() else dj.loadSelected(i)
            // Desktop: PFL alone is free until the console has a headphone cue bus.
            if (down(l.pfl) && s.shift) d.quantize = !d.quantize

            // The platter: the counter's low byte, 1024 to a turn.
            val tick = u8(r, l.jog)
            if (s.jog >= 0) {
                val ticks = TraktorS2Mk1.jogTicks(s.jog, tick)
                if (ticks != 0) d.jog(ticks.toDouble() / TraktorS2Mk1.JOG_TICKS_PER_REV * (if (s.shift) SHIFT_JOG else 1.0))
            }
            s.jog = tick
        }

        for (u in TraktorS2Mk1.FX.indices) {
            val l = TraktorS2Mk1.FX[u]
            l.buttons.forEachIndexed { slot, bit -> if (down(bit)) dj.toggleFx(u, slot) }
            // Each deck has its own FX bus, so a unit's assign button for its own deck takes it in and out.
            // Desktop: the other deck's assign button is free: sending it there would re-route the console's buses.
            if (down(l.assign[u])) dj.toggleFxAssign(u)
            // Desktop: FOCUS is free: the units run in group mode only (three effects, one knob each).
        }
        // Desktop: the browse encoder's press is free: the browser is one flat list.
    }

    // ── Report 0x02: knobs, faders, encoders, platter touch ────────────

    private fun onAnalog(r: ByteArray) {
        val prev = analog
        analog = r
        fun changed(offset: Int) = prev == null || u16le(prev, offset) != u16le(r, offset)
        fun raw(offset: Int) = u16le(r, offset)
        fun nibble(n: Nibble) = u8(r, n.offset).let { if (n.high) it ushr 4 else it and 0x0F }
        val cal = calibration
        val anyShift = decks.any { it.shift }

        for (i in decks.indices) {
            val l = TraktorS2Mk1.DECKS[i]
            val s = decks[i]
            val d = dj.decks[i]

            if (changed(l.rate)) {
                val v = TraktorS2Mk1.Calibration.FULL.map(raw(l.rate))
                val invert = invertTempo
                if (invert != s.inverted) {
                    s.inverted = invert
                    s.tempo.reset()
                }
                val position = if (invert) 2f * v - 1f else 1f - 2f * v
                if (s.tempo.accept(position, d.tempoFader)) d.setTempoFader(position)
            }
            if (changed(l.volume)) {
                val v = cal.volume[i].map(raw(l.volume))
                if (s.volume.accept(v, d.volume)) d.volume = v
            }
            if (changed(l.eqHigh)) {
                val v = cal.eq[i][0].map(raw(l.eqHigh))
                if (s.eq[0].accept(v, d.eqHigh)) d.eqHigh = v
            }
            if (changed(l.eqMid)) {
                val v = cal.eq[i][1].map(raw(l.eqMid))
                if (s.eq[1].accept(v, d.eqMid)) d.eqMid = v
            }
            if (changed(l.eqLow)) {
                val v = cal.eq[i][2].map(raw(l.eqLow))
                if (s.eq[2].accept(v, d.eqLow)) d.eqLow = v
            }
            if (changed(l.jogTouch)) {
                s.jogHeld = raw(l.jogTouch) > cal.jogTouched[i]
                d.jogTouched = s.jogHeld
            }

            val left = nibble(l.leftEncoder)
            val leftStep = TraktorS2Mk1.encoderStep(s.leftEncoder, left)
            s.leftEncoder = left
            if (leftStep != 0) when {
                s.shift -> d.beatJump(leftStep.toDouble())
                s.leftHeld -> d.resizeJump(leftStep)
                else -> d.beatJump(leftStep * d.jumpBeats)
            }

            val right = nibble(l.rightEncoder)
            val rightStep = TraktorS2Mk1.encoderStep(s.rightEncoder, right)
            s.rightEncoder = right
            if (rightStep != 0) if (s.shift) d.beatJump(rightStep * d.loopBeats) else d.resizeLoop(rightStep)

            val gain = nibble(l.gainEncoder)
            val gainStep = TraktorS2Mk1.encoderStep(s.gainEncoder, gain)
            s.gainEncoder = gain
            if (gainStep != 0) {
                if (s.shift) d.trim = (d.trim + gainStep * TRIM_STEP).coerceIn(0f, 1f)
                else d.filter = (d.filter + gainStep * FILTER_STEP).coerceIn(-1f, 1f)
            }
        }

        if (changed(TraktorS2Mk1.CROSSFADER)) {
            val v = 2f * cal.crossfader.map(raw(TraktorS2Mk1.CROSSFADER)) - 1f
            if (crossfader.accept(v, dj.crossfader)) dj.crossfader = v
        }

        for (u in TraktorS2Mk1.FX.indices) {
            val l = TraktorS2Mk1.FX[u]
            if (changed(l.mix)) {
                val v = cal.fxMix[u].map(raw(l.mix))
                if (fxMix[u].accept(v, dj.fxMix(u))) dj.setFxMix(u, v)
            }
            for (slot in 0 until FX_SLOTS) {
                val offset = l.knobs[slot]
                if (!changed(offset)) continue
                val v = cal.fxKnobs[u][slot].map(raw(offset))
                if (fxKnobs[u][slot].accept(v, dj.fxAmount(u, slot))) dj.setFxAmount(u, slot, v)
            }
        }

        val browse = nibble(TraktorS2Mk1.BROWSE_ENCODER)
        val browseStep = TraktorS2Mk1.encoderStep(browseEncoder, browse)
        browseEncoder = browse
        if (browseStep != 0) dj.browse(browseStep * if (anyShift) SHIFT_BROWSE else 1)
        // Desktop: the head mix and sampler gain knobs are free until the console has a cue bus and sample decks.
    }

    // ── Report 0x80: the LEDs ──────────────────────────────────────────

    private val meter = IntArray(4)

    /** The LED report's payload (no ID byte), [TraktorS2Mk1.LED_PAYLOAD] bytes, for the console as it is now. */
    fun leds(out: ByteArray = ByteArray(TraktorS2Mk1.LED_PAYLOAD)): ByteArray {
        out.fill(0)
        fun set(offset: Int, value: Int) {
            out[offset - 1] = value.coerceIn(0, TraktorS2Mk1.ON).toByte()
        }
        fun lit(offset: Int, on: Boolean) = set(offset, if (on) TraktorS2Mk1.ON else TraktorS2Mk1.OFF)

        for (i in decks.indices) {
            val l = TraktorS2Mk1.DECKS[i]
            val s = decks[i]
            val d = dj.decks[i]
            val loaded = d.track != null
            lit(l.ledLoaded, loaded)
            TraktorS2Mk1.meterSegments(meterLevel(d.peak), meter)
            for (k in meter.indices) set(l.ledMeter[k], meter[k])
            lit(l.ledPeak, d.peak >= 1f)
            lit(l.ledPlay, d.playing)
            lit(l.ledCue, loaded && !d.playing && abs(d.playPosition - d.cuePoint) < 1.0)
            lit(l.ledSync, d.syncLock)
            lit(l.ledShift, s.shift)
            lit(l.ledLoopIn, d.loopActive)
            lit(l.ledLoopOut, d.loopActive)
            lit(l.ledSamples, s.padPage == 1)
            // With SHIFT held, PFL shows what SHIFT+PFL toggles.
            lit(l.ledPfl, s.shift && d.quantize)
            val cues = d.hotCues
            for (p in l.ledPadBlue.indices) {
                val cued = !cues[s.padPage * l.ledPadBlue.size + p].isNaN()
                // Blue for the first page, green for the second, as SAMPLES says which.
                lit(if (s.padPage == 0) l.ledPadBlue[p] else l.ledPadGreen[p], cued)
            }
        }
        for (u in TraktorS2Mk1.FX.indices) {
            val l = TraktorS2Mk1.FX[u]
            for (slot in 0 until FX_SLOTS) lit(l.ledButtons[slot], dj.fxOn(u, slot))
            lit(l.ledAssign[u], dj.fxAssigned(u))
        }
        return out
    }

    companion object {
        /** Threshold for the -1..1 controls: the same share of their travel as 0.03 of a 0..1 knob. */
        private const val WIDE = 0.06f
        /** Each FX unit's buttons and knobs. */
        private const val FX_SLOTS = 3
        const val SYNC_HOLD_MS = 300L
        private const val SHIFT_JOG = 10.0
        private const val SHIFT_BROWSE = 10
        private const val FILTER_STEP = 0.05f
        /** A trim step: 36 dB of travel in 30 steps, about 1.2 dB. */
        private const val TRIM_STEP = 1f / 30f
        /** The meter's floor: -36 dB lights nothing, 0 dB all four. */
        private const val METER_FLOOR_DB = -36f

        /** A peak (linear) as the meter's 0..1, on a dB scale. */
        fun meterLevel(peak: Float): Float {
            if (peak <= 0f) return 0f
            val db = 20f * log10(peak)
            return ((db - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0f, 1f)
        }
    }
}
