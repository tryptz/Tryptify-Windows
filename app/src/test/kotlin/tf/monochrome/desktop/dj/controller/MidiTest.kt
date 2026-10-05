package tf.monochrome.desktop.dj.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.desktop.dj.BeatGrid
import tf.monochrome.desktop.dj.DeckTrack

/**
 * USB MIDI without a controller: messages as a controller sends them,
 * played into a mapping, and the console read back; and the mappings
 * themselves, learned, built in and imported.
 */
class MidiTest {

    private val sr = 48_000

    private fun surface() = FakeDjSurface().apply {
        for (d in decks) {
            val n = 60 * sr
            val ramp = FloatArray(n) { 0.1f + 0.8f * it / n }
            d.quantize = false
            d.load(DeckTrack.fromSamples(ramp, ramp.copyOf(), sr).also { it.grid = BeatGrid(120.0, 0.0, 1f) })
        }
    }

    private fun note(ch: Int, n: Int) = MidiKey(MidiType.NOTE, ch, n)
    private fun cc(ch: Int, n: Int) = MidiKey(MidiType.CC, ch, n)

    private fun engine(s: DjSurface, vararg bindings: MidiBinding, leds: List<MidiLed> = emptyList()) =
        MidiMappingEngine(s, MidiProfile("test", bindings = bindings.toList(), leds = leds))

    private fun MidiMappingEngine.send(status: Int, d1: Int, d2: Int, now: Long = 0) = onMessage(status, d1, d2, now)

    // ── Buttons ────────────────────────────────────────────────────────

    @Test
    fun aNoteOnPressesAndEitherReleaseReleases() {
        val s = surface()
        val e = engine(s, MidiBinding(note(0, 0x0B), MidiAction.PLAY), MidiBinding(note(1, 0x0C), MidiAction.CUE, unit = 1))
        val d = s.decks[0]
        e.send(0x90, 0x0B, 0x7F)
        assertTrue(d.playing)
        // A release, as a note on at velocity 0: nothing.
        e.send(0x90, 0x0B, 0x00)
        assertTrue(d.playing)
        e.send(0x90, 0x0B, 0x7F)
        e.send(0x80, 0x0B, 0x40)
        assertFalse(d.playing)
        // Another channel is another deck.
        e.send(0x90, 0x0B + 1, 0x7F)
        e.send(0x91, 0x0B, 0x7F)
        assertFalse(s.decks[1].playing)

        // CUE held previews; a note off, with its own velocity, lets go.
        val d2 = s.decks[1]
        e.send(0x91, 0x0C, 0x7F)
        assertTrue(d2.playing)
        e.send(0x81, 0x0C, 0x40)
        assertFalse(d2.playing)
    }

    @Test
    fun syncTapsOnceAndLocksWhenHeld() {
        val s = surface()
        val e = engine(s, MidiBinding(note(0, 0x58), MidiAction.SYNC))
        e.send(0x90, 0x58, 0x7F, now = 0)
        e.tick(S2Mk1Mapping.SYNC_HOLD_MS - 1)
        e.send(0x90, 0x58, 0x00, now = S2Mk1Mapping.SYNC_HOLD_MS - 1)
        e.tick(1000)
        assertFalse(s.decks[0].syncLock)

        e.send(0x90, 0x58, 0x7F, now = 2000)
        e.tick(2000 + S2Mk1Mapping.SYNC_HOLD_MS)
        assertTrue(s.decks[0].syncLock)
        e.send(0x90, 0x58, 0x00, now = 2400)
        // Pressed while locked: unlocked.
        e.send(0x90, 0x58, 0x7F, now = 3000)
        assertFalse(s.decks[0].syncLock)
        assertEquals(listOf("lock 0 true", "lock 0 false"), s.calls.filter { it.startsWith("lock") })
    }

    @Test
    fun releaseLetsGoOfWhatAnUnpluggedControllerHeld() {
        val s = surface()
        val e = engine(s, MidiBinding(note(0, 0x0C), MidiAction.CUE), MidiBinding(note(0, 0x36), MidiAction.JOG_TOUCH))
        e.send(0x90, 0x0C, 0x7F)
        e.send(0x90, 0x36, 0x7F)
        assertTrue(s.decks[0].playing)
        assertTrue(s.decks[0].jogTouched)
        e.release()
        assertFalse(s.decks[0].playing)
        assertFalse(s.decks[0].jogTouched)
    }

    // ── Knobs and faders ──────────────────────────────────────────────

    @Test
    fun aCentredKnobIsFlatAtItsDetent() {
        assertEquals(0.5f, MidiMappingEngine.centred(64, 7), 0f)
        assertEquals(0f, MidiMappingEngine.centred(0, 7), 0f)
        assertEquals(1f, MidiMappingEngine.centred(127, 7), 0f)
        assertEquals(0.5f, MidiMappingEngine.centred(8192, 14), 0f)
        assertEquals(1f, MidiMappingEngine.centred(16383, 14), 0f)

        val s = surface()
        val e = engine(s, MidiBinding(cc(0, 0x07), MidiAction.EQ_HIGH))
        e.send(0xB0, 0x07, 64)
        assertEquals(0.5f, s.decks[0].eqHigh, 0f)
        e.send(0xB0, 0x07, 60)
        e.send(0xB0, 0x07, 0)
        assertEquals(0f, s.decks[0].eqHigh, 0f)
    }

    @Test
    fun fourteenBitFadersTakeBothHalves() {
        val s = surface()
        val e = engine(s, MidiBinding(cc(0, 0x13), MidiAction.VOLUME, lsb = 0x33))
        val d = s.decks[0]
        // From the top, where the deck is, down in 14-bit steps.
        e.send(0xB0, 0x13, 0x7F)
        e.send(0xB0, 0x33, 0x7F)
        assertEquals(1f, d.volume, 0f)
        e.send(0xB0, 0x13, 0x7E)
        assertEquals((0x7E shl 7) / 16383f, d.volume, 1e-6f)
        e.send(0xB0, 0x33, 0x40)
        assertEquals(((0x7E shl 7) or 0x40) / 16383f, d.volume, 1e-6f)
        // The low half alone, on another channel: not this fader.
        e.send(0xB1, 0x33, 0x00)
        assertEquals(((0x7E shl 7) or 0x40) / 16383f, d.volume, 1e-6f)
    }

    @Test
    fun aFaderFarFromTheConsoleTakesOverOnlyWhenItGetsThere() {
        val s = surface()
        val e = engine(s, MidiBinding(cc(0, 0x13), MidiAction.VOLUME))
        val d = s.decks[0]
        d.volume = 1f
        // The controller's fader is down: the deck stays up until the fader comes to it.
        e.send(0xB0, 0x13, 0)
        assertEquals(1f, d.volume, 0f)
        e.send(0xB0, 0x13, 126)
        assertEquals(126 / 127f, d.volume, 1e-6f)
        e.send(0xB0, 0x13, 0)
        assertEquals(0f, d.volume, 0f)
    }

    @Test
    fun pitchBendIsFourteenBitsAndTempoFollowsTheInvert() {
        val s = surface()
        var invert = false
        val e = MidiMappingEngine(s, MidiProfile("t", bindings = listOf(MidiBinding(MidiKey(MidiType.PITCH_BEND, 0), MidiAction.TEMPO))), { invert })
        val d = s.decks[0]
        e.send(0xE0, 0x00, 0x40)
        assertEquals(0f, d.tempoFader, 1e-4f)
        // Up a little: faster.
        e.send(0xE0, 0x00, 0x48)
        assertTrue(d.tempoFader > 0f)
        val faster = d.tempoFader
        // Inverted, the same position is slower: the tempo holds until the fader comes back to it.
        invert = true
        e.send(0xE0, 0x00, 0x48)
        assertEquals(faster, d.tempoFader, 1e-4f)
        // Down past it, which is now faster: caught, and followed.
        e.send(0xE0, 0x00, 0x30)
        assertTrue(d.tempoFader > faster)
        e.send(0xE0, 0x00, 0x50)
        assertTrue(d.tempoFader < 0f)
    }

    @Test
    fun crossfaderAndFilterAreBipolar() {
        val s = surface()
        val e = engine(s, MidiBinding(cc(6, 0x1F), MidiAction.CROSSFADER), MidiBinding(cc(6, 0x17), MidiAction.FILTER, invert = true))
        e.send(0xB6, 0x1F, 64)
        assertEquals(0f, s.crossfader, 0f)
        e.send(0xB6, 0x1F, 90)
        e.send(0xB6, 0x1F, 127)
        assertEquals(1f, s.crossfader, 0f)
        e.send(0xB6, 0x17, 64)
        e.send(0xB6, 0x17, 30)
        // Inverted: the knob left of centre is the filter right of it.
        assertTrue(s.decks[0].filter > 0f)
    }

    // ── Encoders ──────────────────────────────────────────────────────

    @Test
    fun relativeEncodings() {
        assertEquals(1, Relative.TWOS_COMPLEMENT.steps(1))
        assertEquals(-1, Relative.TWOS_COMPLEMENT.steps(127))
        assertEquals(1, Relative.OFFSET_64.steps(65))
        assertEquals(-1, Relative.OFFSET_64.steps(63))
        assertEquals(1, Relative.SIGN_BIT.steps(0x01))
        assertEquals(-1, Relative.SIGN_BIT.steps(0x41))

        val s = surface()
        val e = engine(s, MidiBinding(cc(6, 0x40), MidiAction.BROWSE), MidiBinding(cc(7, 0x40), MidiAction.BROWSE, invert = true))
        e.send(0xB6, 0x40, 127)
        e.send(0xB6, 0x40, 2)
        e.send(0xB7, 0x40, 1)
        assertEquals(listOf("browse -1", "browse 2", "browse -1"), s.calls.filter { it.startsWith("browse") })
    }

    // ── LEDs ──────────────────────────────────────────────────────────

    @Test
    fun ledsGoOutOnlyWhenTheyChange() {
        val s = surface()
        val e = engine(
            s,
            MidiBinding(note(0, 0x0B), MidiAction.PLAY),
            leds = listOf(MidiLed(note(0, 0x0B), MidiLight.PLAYING), MidiLed(note(7, 0x00), MidiLight.HOT_CUE, slot = 0, on = 0x2A, off = 0x01)),
        )
        val sent = mutableListOf<Triple<Int, Int, Int>>()
        e.leds { a, b, c -> sent += Triple(a, b, c) }
        assertEquals(listOf(Triple(0x90, 0x0B, 0x00), Triple(0x97, 0x00, 0x01)), sent)
        sent.clear()
        e.leds { a, b, c -> sent += Triple(a, b, c) }
        assertEquals(emptyList<Triple<Int, Int, Int>>(), sent)

        e.send(0x90, 0x0B, 0x7F)
        s.decks[0].hotCue(0)
        e.leds { a, b, c -> sent += Triple(a, b, c) }
        assertEquals(listOf(Triple(0x90, 0x0B, 0x7F), Triple(0x97, 0x00, 0x2A)), sent)

        sent.clear()
        e.dark { a, b, c -> sent += Triple(a, b, c) }
        assertEquals(listOf(Triple(0x90, 0x0B, 0x00), Triple(0x97, 0x00, 0x01)), sent)
    }

    @Test
    fun bindingsThatPointNowhereAreLeftOut() {
        val s = surface()
        // Deck 3, hot cue 9, channel 17: from a hand-edited file. None may throw.
        val e = engine(
            s,
            MidiBinding(note(0, 1), MidiAction.PLAY, unit = 2),
            MidiBinding(note(0, 2), MidiAction.HOT_CUE, slot = 8),
            MidiBinding(note(16, 3), MidiAction.PLAY),
            leds = listOf(MidiLed(note(0, 4), MidiLight.HOT_CUE, slot = 9)),
        )
        e.send(0x90, 1, 0x7F)
        e.send(0x90, 2, 0x7F)
        e.leds { _, _, _ -> error("no LED is valid") }
    }

    // ── Profiles ──────────────────────────────────────────────────────

    @Test
    fun learningReplacesWhatWasOnTheKeyAndWhatDidTheSameThing() {
        val p = MidiProfile(
            "p",
            bindings = listOf(
                MidiBinding(note(0, 1), MidiAction.PLAY),
                MidiBinding(note(0, 2), MidiAction.CUE),
                MidiBinding(cc(0, 0x13), MidiAction.VOLUME, lsb = 0x33),
            ),
            leds = listOf(MidiLed(note(0, 1), MidiLight.PLAYING), MidiLed(note(0, 2), MidiLight.CUE)),
        )
        // PLAY moved to CUE's button: both old bindings go, and their LEDs.
        val moved = p.learn(MidiBinding(note(0, 2), MidiAction.PLAY))
        assertEquals(listOf(MidiBinding(cc(0, 0x13), MidiAction.VOLUME, lsb = 0x33), MidiBinding(note(0, 2), MidiAction.PLAY)), moved.bindings)
        assertEquals(listOf(MidiLed(note(0, 2), MidiLight.PLAYING)), moved.leds)
        // A knob learned on the fader's low half takes the fader's place.
        val knob = moved.learn(MidiBinding(cc(0, 0x33), MidiAction.TRIM))
        assertEquals(listOf(MidiAction.PLAY, MidiAction.TRIM), knob.bindings.map { it.action })
        // Deck 2's PLAY is another target.
        assertEquals(3, knob.learn(MidiBinding(note(1, 1), MidiAction.PLAY, unit = 1)).bindings.size)
    }

    @Test
    fun aLearnedControlIsReadFromWhatItSent() {
        val t = MidiTarget(MidiAction.VOLUME)
        // A 14-bit fader: CC 0x13 and 0x33, interleaved.
        val fader = MidiControllers.binding(t, listOf(Triple(0xB0, 0x13, 5), Triple(0xB0, 0x33, 9), Triple(0xB0, 0x13, 6)))
        assertEquals(MidiBinding(cc(0, 0x13), MidiAction.VOLUME, lsb = 0x33), fader)
        // Even when the low half came first.
        assertEquals(fader, MidiControllers.binding(t, listOf(Triple(0xB0, 0x33, 9), Triple(0xB0, 0x13, 6))))
        // A 7-bit knob.
        assertEquals(MidiBinding(cc(2, 0x30), MidiAction.VOLUME), MidiControllers.binding(t, listOf(Triple(0xB2, 0x30, 1))))
        // A jog wheel and an encoder.
        val jog = MidiControllers.binding(MidiTarget(MidiAction.JOG, 1), listOf(Triple(0xB1, 0x22, 65), Triple(0xB1, 0x22, 66)))
        assertEquals(MidiBinding(cc(1, 0x22), MidiAction.JOG, unit = 1, encoding = Relative.OFFSET_64), jog)
        val knob = MidiControllers.binding(MidiTarget(MidiAction.BROWSE), listOf(Triple(0xB6, 0x40, 127), Triple(0xB6, 0x40, 1)))
        assertEquals(Relative.TWOS_COMPLEMENT, knob?.encoding)
        // A note is not a knob.
        assertNull(MidiControllers.binding(t, listOf(Triple(0x90, 0x10, 0x7F))))
    }

    @Test
    fun aPortIsTheControllerWhicheverUnitItIs() {
        assertEquals("DDJ-400", MidiControllers.device("DDJ-400"))
        assertEquals("DDJ-400", MidiControllers.device("2- DDJ-400"))
        assertEquals("DDJ-400", MidiControllers.device("DDJ-400 (2)"))
        assertEquals("DDJ-400.json", MidiControllers.fileName("DDJ-400"))
        assertEquals("Numark_Party_Mix_MIDI_1.json", MidiControllers.fileName("Numark Party Mix: MIDI 1"))
        assertEquals("controller.json", MidiControllers.fileName("../.."))
        assertTrue(BuiltInMidiProfiles.DDJ_400.matches("2- DDJ-400"))
        assertTrue(BuiltInMidiProfiles.DDJ_400.matches("DDJ-FLX4"))
        assertNull(BuiltInMidiProfiles.forPort("Launchpad Mini"))
    }

    @Test
    fun theBuiltInMappingsAreWholeAndUnclashed() {
        val s = surface()
        for (p in BuiltInMidiProfiles.ALL) {
            val keys = p.bindings.flatMap { listOfNotNull(it.key, it.lsbKey) }
            assertEquals("${p.name}: a key bound twice", keys.size, keys.toSet().size)
            for (b in p.bindings) {
                assertTrue("${p.name}: $b", b.unit in 0..1 && b.key.channel in 0..15 && b.key.number in 0..127)
                if (!b.action.slotted) assertEquals("${p.name}: $b", 0, b.slot)
            }
            // Every control played once: none throws.
            val e = MidiMappingEngine(s, p)
            for (b in p.bindings) e.onMessage(b.key.status, b.key.number, 0x40, 0)
            for (action in listOf(MidiAction.PLAY, MidiAction.CUE, MidiAction.SYNC, MidiAction.VOLUME, MidiAction.TEMPO, MidiAction.JOG)) {
                for (d in 0..1) assertTrue("${p.name}: $action on deck $d", p.bindings.any { it.action == action && it.unit == d })
            }
            assertTrue(p.bindings.any { it.action == MidiAction.CROSSFADER })
        }
    }

    // ── Mixxx ─────────────────────────────────────────────────────────

    @Test
    fun aMixxxMappingIsImported() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <MixxxControllerPreset mixxxVersion="2.3" schemaVersion="1">
              <info><name>Acme DJ-2</name></info>
              <controller id="Acme">
                <scriptfiles><file functionprefix="Acme" filename="Acme.js"/></scriptfiles>
                <controls>
                  <control><group>[Channel1]</group><key>play</key><status>0x90</status><midino>0x0B</midino><options><normal/></options></control>
                  <control><group>[Channel1]</group><key>play</key><status>0x80</status><midino>0x0B</midino><options><normal/></options></control>
                  <control><group>[Channel2]</group><key>cue_default</key><status>145</status><midino>12</midino></control>
                  <control><group>[Channel1]</group><key>volume</key><status>0xB0</status><midino>0x13</midino><options><fourteen-bit-msb/></options></control>
                  <control><group>[Channel1]</group><key>volume</key><status>0xB0</status><midino>0x33</midino><options><fourteen-bit-lsb/></options></control>
                  <control><group>[Channel1]</group><key>rate</key><status>0xB0</status><midino>0x00</midino><options><Invert/></options></control>
                  <control><group>[EqualizerRack1_[Channel2]_Effect1]</group><key>parameter3</key><status>0xB1</status><midino>0x07</midino></control>
                  <control><group>[QuickEffectRack1_[Channel1]]</group><key>super1</key><status>0xB6</status><midino>0x17</midino></control>
                  <control><group>[Channel2]</group><key>jog</key><status>0xB1</status><midino>0x21</midino></control>
                  <control><group>[Channel1]</group><key>hotcue_3_activate</key><status>0x97</status><midino>0x02</midino></control>
                  <control><group>[EffectRack1_EffectUnit2_Effect3]</group><key>enabled</key><status>0x95</status><midino>0x47</midino></control>
                  <control><group>[EffectRack1_EffectUnit1]</group><key>group_[Channel1]_enable</key><status>0x94</status><midino>0x4C</midino></control>
                  <control><group>[EffectRack1_EffectUnit1]</group><key>group_[Channel2]_enable</key><status>0x94</status><midino>0x4D</midino></control>
                  <control><group>[Master]</group><key>crossfader</key><status>0xB6</status><midino>0x1F</midino></control>
                  <control><group>[Library]</group><key>MoveVertical</key><status>0xB6</status><midino>0x40</midino><options><selectknob/></options></control>
                  <control><group>[Channel1]</group><key>Acme.jogTouch</key><status>0x90</status><midino>0x36</midino><options><script-binding/></options></control>
                  <control><group>[Sampler1]</group><key>cue_gotoandplay</key><status>0x98</status><midino>0x30</midino></control>
                </controls>
                <outputs>
                  <output><group>[Channel1]</group><key>play_indicator</key><status>0x90</status><midino>0x0B</midino><on>0x7F</on><off>0x0</off><minimum>0.5</minimum></output>
                  <output><group>[Channel1]</group><key>hotcue_3_enabled</key><status>0x97</status><midino>0x02</midino><on>0x2A</on></output>
                  <output><group>[Channel1]</group><key>VuMeter</key><status>0xB0</status><midino>0x02</midino></output>
                  <output><group>[Sampler1]</group><key>play</key><status>0x98</status><midino>0x30</midino></output>
                </outputs>
              </controller>
            </MixxxControllerPreset>
        """.trimIndent()
        val r = MixxxImport.read(xml.byteInputStream(), "ACME-2")
        val p = r.profile
        assertEquals("Acme DJ-2", p.name)
        assertEquals(listOf("ACME-2"), p.devices)
        assertEquals(1, r.scripted)
        // The sampler's control and light, and unit 1's assign to deck 2.
        assertEquals(3, r.unsupported)
        val expected = listOf(
            MidiBinding(note(0, 0x0B), MidiAction.PLAY),
            MidiBinding(note(1, 12), MidiAction.CUE, unit = 1),
            MidiBinding(cc(0, 0x13), MidiAction.VOLUME, lsb = 0x33),
            MidiBinding(cc(0, 0x00), MidiAction.TEMPO, invert = true),
            MidiBinding(cc(1, 0x07), MidiAction.EQ_HIGH, unit = 1),
            MidiBinding(cc(6, 0x17), MidiAction.FILTER),
            MidiBinding(cc(1, 0x21), MidiAction.JOG, unit = 1, encoding = Relative.OFFSET_64),
            MidiBinding(note(7, 0x02), MidiAction.HOT_CUE, slot = 2),
            MidiBinding(note(5, 0x47), MidiAction.FX_TOGGLE, unit = 1, slot = 2),
            MidiBinding(note(4, 0x4C), MidiAction.FX_ASSIGN),
            MidiBinding(cc(6, 0x1F), MidiAction.CROSSFADER),
            MidiBinding(cc(6, 0x40), MidiAction.BROWSE),
        )
        assertEquals(expected, p.bindings)
        assertEquals(
            listOf(
                MidiLed(note(0, 0x0B), MidiLight.PLAYING),
                MidiLed(note(7, 0x02), MidiLight.HOT_CUE, slot = 2, on = 0x2A),
                MidiLed(cc(0, 0x02), MidiLight.METER),
            ),
            p.leds,
        )
    }

    @Test(expected = MixxxImport.FormatException::class)
    fun aFileThatIsNotAMappingSaysSo() {
        MixxxImport.read("<html><body>no</body></html>".byteInputStream(), "x")
    }

    @Test(expected = MixxxImport.FormatException::class)
    fun aMappingCannotReachOutsideItself() {
        // An external entity would read a file off the disk: a DOCTYPE is refused outright.
        val xml = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>""" +
            """<MixxxControllerPreset><info><name>&e;</name></info><controller/></MixxxControllerPreset>"""
        MixxxImport.read(xml.byteInputStream(), "x")
    }
}
