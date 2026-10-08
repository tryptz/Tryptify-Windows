package tf.monochrome.desktop.dj.controller

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import tf.monochrome.desktop.dj.BeatGrid
import tf.monochrome.desktop.dj.Deck
import tf.monochrome.desktop.dj.DeckTrack
import tf.monochrome.desktop.dj.controller.TraktorS2Mk1.Bit
import tf.monochrome.desktop.dj.controller.TraktorS2Mk1.Nibble

/**
 * The Traktor S2 MK1 driver without the hardware: reports built byte by
 * byte from the layout Mixxx read off the controller, played into the
 * mapping, and the console's state read back.
 */
class S2Mk1Test {

    private val sr = 48_000

    private fun track(): DeckTrack {
        val n = 60 * sr
        val ramp = FloatArray(n) { 0.1f + 0.8f * it / n }
        return DeckTrack.fromSamples(ramp, ramp.copyOf(), sr).also { it.grid = BeatGrid(120.0, 0.0, 1f) }
    }

    private fun surface() = FakeDjSurface().apply {
        for (d in decks) {
            d.quantize = false
            d.load(track())
        }
    }

    /** Report 0x01 with [pressed] held and the platters' counters at [jog1] and [jog2]. */
    private fun buttons(vararg pressed: Bit, jog1: Int = 0, jog2: Int = 0): ByteArray {
        val r = ByteArray(TraktorS2Mk1.BUTTONS_BYTES + 1)
        r[0] = TraktorS2Mk1.REPORT_BUTTONS.toByte()
        r[0x01] = jog1.toByte()
        r[0x05] = jog2.toByte()
        for (b in pressed) r[b.offset] = (r[b.offset].toInt() or b.mask).toByte()
        return r
    }

    /** Report 0x02, built up a field at a time. */
    private class Analog {
        val r = ByteArray(TraktorS2Mk1.ANALOG_BYTES + 1).also { it[0] = TraktorS2Mk1.REPORT_ANALOG.toByte() }
        fun value(offset: Int, v: Int) = apply {
            r[offset] = v.toByte()
            r[offset + 1] = (v shr 8).toByte()
        }
        fun nibble(n: Nibble, v: Int) = apply {
            val b = r[n.offset].toInt() and 0xFF
            r[n.offset] = (if (n.high) (b and 0x0F) or (v shl 4) else (b and 0xF0) or v).toByte()
        }
    }

    private val deck1 = TraktorS2Mk1.DECKS[0]
    private val deck2 = TraktorS2Mk1.DECKS[1]

    private fun S2Mk1Mapping.send(r: ByteArray, now: Long = 0L) = onReport(r, r.size, now)

    /** Press [b] and let go, from a report with nothing held. */
    private fun S2Mk1Mapping.tap(vararg b: Bit) {
        send(buttons(*b))
        send(buttons())
    }

    // ── Protocol ───────────────────────────────────────────────────────

    @Test
    fun calibrationComesFromTheFeatureReports() {
        val faders = ByteArray(32)
        val knobs = ByteArray(96)
        val jogs = ByteArray(32)
        fun le(b: ByteArray, i: Int, v: Int) { b[i] = v.toByte(); b[i + 1] = (v shr 8).toByte() }
        fun be(b: ByteArray, i: Int, v: Int) { b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte() }
        le(faders, 0x0C, 30); le(faders, 0x0E, 4000)          // deck 1 volume
        le(faders, 0x14, 40); le(faders, 0x16, 4050)          // crossfader
        // Deck 1's mid knob straddles the first two knob reports: left and centre in 0xD1, right in 0xD2.
        le(knobs, 0x1E, 50); le(knobs, 0x20, 2100); le(knobs, 0x22, 4060)
        le(knobs, 0x54, 60); le(knobs, 0x56, 1990); le(knobs, 0x58, 4070)    // FX 2, third knob
        be(jogs, 0x00, 0x0C1B); be(jogs, 0x02, 0x0CE6)
        be(jogs, 0x04, 0x0C9A); be(jogs, 0x06, 0x0D58)

        val c = TraktorS2Mk1.Calibration.parse(
            faders, knobs.copyOfRange(0, 32), knobs.copyOfRange(32, 64), knobs.copyOfRange(64, 96), jogs,
        )
        assertEquals(TraktorS2Mk1.Fader(30, 4000), c.volume[0])
        assertEquals(TraktorS2Mk1.Fader(40, 4050), c.crossfader)
        assertEquals(TraktorS2Mk1.Knob(50, 2100, 4060), c.eq[0][1])
        assertEquals(TraktorS2Mk1.Knob(60, 1990, 4070), c.fxKnobs[1][2])
        // The touched threshold, big-endian, after each platter's untouched reading.
        assertArrayEquals(intArrayOf(0x0CE6, 0x0D58), c.jogTouched)
    }

    @Test
    fun knobsMapEachHalfToTheDetent() {
        val k = TraktorS2Mk1.Knob(100, 2500, 4000)
        assertEquals(0f, k.map(100), 0f)
        assertEquals(0.25f, k.map(1300), 1e-6f)
        assertEquals("the detent is flat, off the middle of the travel", 0.5f, k.map(2500), 0f)
        assertEquals(0.75f, k.map(3250), 1e-6f)
        assertEquals(1f, k.map(4095), 0f)
        assertEquals(0f, TraktorS2Mk1.Fader(30, 4000).map(10), 0f)
        assertEquals(0.5f, TraktorS2Mk1.Fader(0, 4000).map(2000), 0f)
    }

    @Test
    fun encodersAndPlattersCountTheShortWayRound() {
        assertEquals("the first reading", 0, TraktorS2Mk1.encoderStep(-1, 7))
        assertEquals(1, TraktorS2Mk1.encoderStep(15, 0))
        assertEquals(-1, TraktorS2Mk1.encoderStep(0, 15))
        assertEquals("a missed report", 0, TraktorS2Mk1.encoderStep(3, 5))
        assertEquals(10, TraktorS2Mk1.jogTicks(250, 4))
        assertEquals(-10, TraktorS2Mk1.jogTicks(4, 250))
        assertEquals(0, TraktorS2Mk1.jogTicks(9, 9))
    }

    @Test
    fun meterLightsWholeSegmentsThenAPart() {
        val seg = IntArray(4)
        TraktorS2Mk1.meterSegments(0.6f, seg)
        assertArrayEquals(intArrayOf(0x1F, 0x1F, (0.4f * 0x1F).toInt(), 0), seg)
        TraktorS2Mk1.meterSegments(1f, seg)
        assertArrayEquals(IntArray(4) { 0x1F }, seg)
        assertEquals(1f, S2Mk1Mapping.meterLevel(1f), 0f)
        assertEquals(0.5f, S2Mk1Mapping.meterLevel(10f.pow(-18f / 20f)), 1e-4f)
        assertEquals(0f, S2Mk1Mapping.meterLevel(0f), 0f)
    }

    // ── Buttons ────────────────────────────────────────────────────────

    @Test
    fun heldAtConnectIsNotAPress() {
        val s = surface()
        val m = S2Mk1Mapping(s)
        m.send(buttons(deck1.play))
        assertFalse(s.decks[0].playing)
        m.send(buttons())
        m.send(buttons(deck1.play))
        assertTrue(s.decks[0].playing)
    }

    @Test
    fun transportButtons() {
        val s = surface()
        val m = S2Mk1Mapping(s)
        val d = s.decks[0]
        m.send(buttons())
        m.tap(deck1.play)
        assertTrue(d.playing)
        m.tap(deck1.play)
        assertFalse(d.playing)
        // SHIFT+PLAY is keylock, and plays nothing.
        m.send(buttons(deck1.shift))
        m.send(buttons(deck1.shift, deck1.play))
        m.send(buttons(deck1.shift))
        m.send(buttons())
        assertTrue(d.keylock)
        assertFalse(d.playing)
        // Deck 2's button is deck 2's.
        m.tap(deck2.play)
        assertTrue(s.decks[1].playing)
        assertFalse(d.playing)
    }

    @Test
    fun padsAreHotCuesOnTwoPages() {
        val s = surface()
        val m = S2Mk1Mapping(s)
        val d = s.decks[0]
        m.send(buttons())
        d.beatJump(2.0)
        m.tap(deck1.pads[1])
        assertEquals(48_000.0, d.hotCues[1], 1.0)
        // SAMPLES turns the page: the same pad is hot cue 6.
        m.tap(deck1.samples)
        d.beatJump(2.0)
        m.tap(deck1.pads[1])
        assertEquals(96_000.0, d.hotCues[5], 1.0)
        // SHIFT+pad clears it.
        m.send(buttons(deck1.shift))
        m.send(buttons(deck1.shift, deck1.pads[1]))
        m.send(buttons())
        assertTrue(d.hotCues[5].isNaN())
        assertFalse(d.hotCues[1].isNaN())
    }

    @Test
    fun syncTappedMatchesHeldLocks() {
        val s = surface()
        val m = S2Mk1Mapping(s)
        m.send(buttons(), 0)
        m.send(buttons(deck2.sync), 1_000)
        m.tick(1_000 + S2Mk1Mapping.SYNC_HOLD_MS - 50)
        assertFalse("a tap only syncs", s.decks[1].syncLock)
        m.tick(1_000 + S2Mk1Mapping.SYNC_HOLD_MS)
        assertTrue("held, it locks", s.decks[1].syncLock)
        m.send(buttons(), 1_400)
        // Pressed while locked: unlocks, and holding it doesn't lock again.
        m.send(buttons(deck2.sync), 2_000)
        assertFalse(s.decks[1].syncLock)
        m.tick(3_000)
        assertFalse(s.decks[1].syncLock)
        assertEquals(listOf("lock 1 true", "lock 1 false"), s.calls)
    }

    @Test
    fun loadAndFxButtons() {
        val s = surface()
        val m = S2Mk1Mapping(s)
        m.send(buttons())
        m.tap(deck2.load)
        assertEquals(listOf("load 1"), s.calls)
        val fx2 = TraktorS2Mk1.FX[1]
        m.tap(fx2.buttons[2])
        assertTrue(s.fxOn(1, 2))
        // Unit 2's assign for deck 2 takes it out of the deck's path; for deck 1 does nothing.
        m.tap(fx2.assign[1])
        assertFalse(s.fxAssigned(1))
        m.tap(fx2.assign[0])
        assertFalse(s.fxAssigned(1))
        assertTrue(s.fxAssigned(0))
    }

    // ── Encoders, platters, knobs ──────────────────────────────────────

    @Test
    fun encodersJumpAndResize() {
        val s = surface()
        val m = S2Mk1Mapping(s)
        val d = s.decks[0]
        m.send(buttons())
        val a = Analog().nibble(deck1.leftEncoder, 4).nibble(deck1.rightEncoder, 4).nibble(deck1.gainEncoder, 4)
        m.send(a.r)
        // Left: one step clockwise is a jump of the jump size (4 beats at 120 BPM).
        m.send(a.nibble(deck1.leftEncoder, 5).r)
        assertEquals(4 * 24_000.0, d.playPosition, 1.0)
        // Turned while pressed, it sets that size.
        m.send(buttons(deck1.leftPress))
        m.send(a.nibble(deck1.leftEncoder, 4).r)
        m.send(buttons())
        assertEquals(2.0, d.jumpBeats, 0.0)
        assertEquals(4 * 24_000.0, d.playPosition, 1.0)
        // Right: the loop size, down a step.
        m.send(a.nibble(deck1.rightEncoder, 3).r)
        assertEquals(2.0, d.loopBeats, 0.0)
        // Gain: the filter, a twentieth a step; pressed, back to the middle.
        m.send(a.nibble(deck1.gainEncoder, 5).r)
        m.send(a.nibble(deck1.gainEncoder, 6).r)
        assertEquals(0.1f, d.filter, 1e-6f)
        m.tap(deck1.gainPress)
        assertEquals(0f, d.filter, 0f)
        // Browse: SHIFT on either deck scrolls ten at a time.
        m.send(a.nibble(TraktorS2Mk1.BROWSE_ENCODER, 1).r)
        m.send(a.nibble(TraktorS2Mk1.BROWSE_ENCODER, 2).r)
        m.send(buttons(deck2.shift))
        m.send(a.nibble(TraktorS2Mk1.BROWSE_ENCODER, 1).r)
        assertEquals(listOf("browse 1", "browse 1", "browse -10"), s.calls)
    }

    @Test
    fun platterSeeksWhilePausedAndScratchesWhenTouched() {
        val s = surface()
        val m = S2Mk1Mapping(s)
        val d = s.decks[0]
        val l = FloatArray(480)
        val r = FloatArray(480)
        d.attach()
        d.render(l, r, 480, sr, 1f)
        m.send(buttons(jog1 = 200))
        // 256 ticks, a quarter turn, over three reports and through the counter's wrap.
        m.send(buttons(jog1 = 30))
        m.send(buttons(jog1 = 150))
        m.send(buttons(jog1 = 200))
        d.render(l, r, 480, sr, 1f)
        assertEquals(0.25 * Deck.SCRATCH_SECONDS_PER_REV * sr, d.playPosition, 2.0)

        val touch = Analog().value(deck1.jogTouch, 0x0D00)
        m.send(touch.r)
        assertTrue(d.jogTouched)
        m.send(touch.value(deck1.jogTouch, 0x0C20).r)
        assertFalse(d.jogTouched)
    }

    @Test
    fun releaseLetsGoOfWhatAnUnpluggedS2Held() {
        val s = surface()
        val m = S2Mk1Mapping(s)
        m.send(buttons())
        // CUE held at the cue point previews; a platter touched scratches.
        m.send(buttons(deck1.cue))
        assertTrue(s.decks[0].playing)
        m.send(Analog().value(deck2.jogTouch, 0x0D00).r)
        assertTrue(s.decks[1].jogTouched)
        // Pulled out: neither release ever comes.
        m.release()
        assertFalse(s.decks[0].playing)
        assertFalse(s.decks[1].jogTouched)
        // Let go once: a second time finds nothing held.
        m.release()
        assertFalse(s.decks[0].playing)
    }

    @Test
    fun knobsTakeOverSoftly() {
        val s = surface()
        val m = S2Mk1Mapping(s)
        val d = s.decks[0]
        d.volume = 1f
        // The fader is down when the controller connects: the deck stays loud.
        val a = Analog().value(deck1.volume, 16)
        m.send(a.r)
        assertEquals(1f, d.volume, 0f)
        m.send(a.value(deck1.volume, 2048).r)
        assertEquals(1f, d.volume, 0f)
        // Pushed past the deck's level, it has the fader, and keeps it on the way back down.
        m.send(a.value(deck1.volume, 4080).r)
        assertEquals(1f, d.volume, 0f)
        m.send(a.value(deck1.volume, 2048).r)
        assertEquals(0.5f, d.volume, 0.01f)
        // The screen moves it: the hardware lets go until it meets the value again.
        d.volume = 0.2f
        m.send(a.value(deck1.volume, 2200).r)
        assertEquals(0.2f, d.volume, 0f)
        m.send(a.value(deck1.volume, 800).r)
        assertEquals(0.19f, d.volume, 0.01f)

        // The EQ knob's detent is flat whatever the knob's own centre reads.
        m.send(a.value(deck1.eqLow, 2048).r)
        assertEquals(0.5f, d.eqLow, 0.001f)
        // The crossfader, -1..1; already at the hardware's position.
        m.send(a.value(TraktorS2Mk1.CROSSFADER, 2048).r)
        m.send(a.value(TraktorS2Mk1.CROSSFADER, 4080).r)
        assertEquals(1f, s.crossfader, 0f)
    }

    @Test
    fun tempoFaderDirection() {
        val s = surface()
        val m = S2Mk1Mapping(s)
        val d = s.decks[0]
        val a = Analog().value(deck1.rate, 2048)
        m.send(a.r)
        // Technics: the far end from you (the raw maximum here) is slower.
        m.send(a.value(deck1.rate, 4080).r)
        assertEquals(-1f, d.tempoFader, 1e-4f)
        m.invertTempo = true
        m.send(a.value(deck1.rate, 4000).r)
        m.send(a.value(deck1.rate, 3500).r)
        assertTrue("inverted, the hardware must come back to the deck's tempo first", d.tempoFader < -0.99f)
        m.send(a.value(deck1.rate, 16).r)
        m.send(a.value(deck1.rate, 2048).r)
        assertEquals(0f, d.tempoFader, 1e-3f)
    }

    // ── LEDs ───────────────────────────────────────────────────────────

    @Test
    fun ledsShowTheDecks() {
        val s = surface()
        val m = S2Mk1Mapping(s)
        val d = s.decks[1]
        m.send(buttons())
        m.tap(deck2.play)
        m.tap(deck2.pads[0])
        s.decks[1].syncLock = true
        s.toggleFx(0, 1)
        val out = m.leds()
        assertEquals(TraktorS2Mk1.LED_PAYLOAD, out.size)
        fun at(offset: Int) = out[offset - 1].toInt() and 0xFF
        assertEquals(0x1F, at(deck2.ledPlay))
        assertEquals(0x1F, at(deck2.ledSync))
        assertEquals(0x1F, at(deck2.ledLoaded))
        assertEquals("first page, blue", 0x1F, at(deck2.ledPadBlue[0]))
        assertEquals(0, at(deck2.ledPadGreen[0]))
        assertEquals(0, at(deck2.ledPadBlue[1]))
        assertEquals(0, at(deck1.ledPlay))
        assertEquals("deck 1 paused at its cue", 0x1F, at(deck1.ledCue))
        assertEquals(0x1F, at(TraktorS2Mk1.FX[0].ledButtons[1]))
        assertEquals(0x1F, at(TraktorS2Mk1.FX[0].ledAssign[0]))
        assertEquals(0, at(TraktorS2Mk1.LED_WARNING))
        assertFalse(d.loopActive)
    }
}
