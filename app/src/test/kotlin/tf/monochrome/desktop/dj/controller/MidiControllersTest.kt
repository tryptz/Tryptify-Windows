package tf.monochrome.desktop.dj.controller

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tf.monochrome.desktop.dj.BeatGrid
import tf.monochrome.desktop.dj.DeckTrack
import tf.monochrome.desktop.dj.controller.ControllerManager.State
import tf.monochrome.desktop.dj.controller.ControllerManager.Status

/**
 * The MIDI thread against a fake bus: controllers plugged in and pulled out,
 * held by another program, learned, imported, and their LEDs recorded.
 */
class MidiControllersTest {

    @get:Rule val temp = TemporaryFolder()

    /** One controller: its input, and an output that records what is sent to it. */
    private class FakePort(val name: String) : MidiConnection {
        @Volatile var receive: ((Int, Int, Int) -> Unit)? = null
        val sent = CopyOnWriteArrayList<Triple<Int, Int, Int>>()
        @Volatile var closed = false
        override val hasOutput = true
        override fun send(status: Int, data1: Int, data2: Int): Boolean {
            sent += Triple(status, data1, data2)
            return true
        }
        override fun close() {
            closed = true
        }

        /** The controller sends a message. */
        fun play(status: Int, data1: Int, data2: Int) = receive!!(status, data1, data2)
    }

    private class FakeMidi : MidiBus {
        val plugged = CopyOnWriteArrayList<FakePort>()
        @Volatile var busy = false
        val opened = CopyOnWriteArrayList<String>()
        val scans = AtomicInteger()

        override fun inputs(): List<String> {
            scans.incrementAndGet()
            return plugged.map { it.name }
        }

        override fun open(name: String, receive: (status: Int, data1: Int, data2: Int) -> Unit): MidiConnection {
            if (busy) throw MidiOpenException(busy = true, "already allocated")
            val p = plugged.firstOrNull { it.name == name } ?: throw MidiOpenException(busy = false, "gone")
            opened += name
            p.closed = false
            p.receive = receive
            return p
        }
    }

    private val surface = FakeDjSurface().apply {
        val sr = 48_000
        for (d in decks) {
            val ramp = FloatArray(60 * sr) { 0.1f + 0.8f * it / (60f * sr) }
            d.quantize = false
            d.load(DeckTrack.fromSamples(ramp, ramp.copyOf(), sr).also { it.grid = BeatGrid(120.0, 0.0, 1f) })
        }
    }
    private val bus = FakeMidi()
    private val running = mutableListOf<MidiControllers>()

    private fun start(dir: File = temp.root): MidiControllers =
        MidiControllers(surface, bus, dir, scanIntervalMs = 10).also {
            running += it
            it.start()
        }

    @After
    fun tearDown() {
        running.forEach { it.stop() }
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    private fun MidiControllers.state(port: String) = ports.value.firstOrNull { it.name == port }?.state

    private val ddj = BuiltInMidiProfiles.DDJ_400
    private fun ddj(action: MidiAction, unit: Int = 0) = ddj.bindings.first { it.action == action && it.unit == unit }.key

    @Test
    fun aKnownControllerConnectsAndPlays() {
        val port = FakePort("DDJ-400").also { bus.plugged += it }
        val midi = start()
        eventually("connected") {
            midi.ports.value == listOf(Status("DDJ-400", State.CONNECTED, midi = true, mapping = ddj.name, verified = false))
        }
        val play = ddj(MidiAction.PLAY)
        port.play(play.status, play.number, 0x7F)
        eventually("deck 1 playing") { surface.decks[0].playing }
        val led = ddj.leds.first { it.light == MidiLight.PLAYING && it.unit == 0 }
        eventually("its PLAY lit") { Triple(led.key.status, led.key.number, led.on) in port.sent }
    }

    @Test
    fun aControllerWithNoMappingIsLeftForOtherPrograms() {
        bus.plugged += FakePort("Launchpad Mini")
        val midi = start()
        eventually("listed") { midi.ports.value == listOf(Status("Launchpad Mini", State.UNMAPPED, midi = true)) }
        val after = bus.scans.get()
        eventually("a few more looks") { bus.scans.get() >= after + 3 }
        // Never opened: on Windows that would take it from Traktor or Serato.
        assertEquals(emptyList<String>(), bus.opened)
    }

    @Test
    fun aPortHeldElsewhereIsBusyUntilItIsLetGo() {
        bus.plugged += FakePort("DDJ-400")
        bus.busy = true
        val midi = start()
        eventually("busy") { midi.state("DDJ-400") == State.BUSY }
        bus.busy = false
        eventually("connected") { midi.state("DDJ-400") == State.CONNECTED }
    }

    @Test
    fun unpluggedMidGestureTheDeckIsLetGo() {
        val port = FakePort("DDJ-400").also { bus.plugged += it }
        val midi = start()
        eventually("connected") { midi.state("DDJ-400") == State.CONNECTED }
        // CUE held at the cue point previews; the release never comes.
        val cue = ddj(MidiAction.CUE)
        port.play(cue.status, cue.number, 0x7F)
        eventually("previewing") { surface.decks[0].playing }
        bus.plugged.clear()
        eventually("gone") { midi.ports.value.isEmpty() }
        assertTrue(port.closed)
        assertFalse(surface.decks[0].playing)
    }

    @Test
    fun aButtonIsLearnedSavedAndThereAfterARestart() {
        val port = FakePort("Acme DJ-2").also { bus.plugged += it }
        val midi = start()
        eventually("listed") { midi.state("Acme DJ-2") == State.UNMAPPED }

        midi.learn("Acme DJ-2", MidiTarget(MidiAction.PLAY, unit = 1))
        // Opened for learning only.
        eventually("opened to learn") { midi.state("Acme DJ-2") == State.CONNECTED && midi.learning.value != null }
        port.play(0x92, 0x10, 0x7F)
        port.play(0x82, 0x10, 0x00)
        val learned = MidiBinding(MidiKey(MidiType.NOTE, 2, 0x10), MidiAction.PLAY, unit = 1)
        eventually("learned") { midi.learning.value == null && midi.profiles.value["Acme DJ-2"]?.bindings == listOf(learned) }
        assertEquals(listOf(MidiLed(learned.key, MidiLight.PLAYING, unit = 1)), midi.profiles.value["Acme DJ-2"]?.leds)
        assertFalse("learning is not playing", surface.decks[1].playing)

        // And now it plays.
        port.play(0x92, 0x10, 0x7F)
        eventually("deck 2 playing") { surface.decks[1].playing }
        assertTrue(File(temp.root, "Acme_DJ-2.json").isFile)

        midi.stop()
        val again = start()
        eventually("its mapping back") {
            again.ports.value == listOf(Status("Acme DJ-2", State.CONNECTED, midi = true, mapping = "Acme DJ-2"))
        }
        assertEquals(listOf(learned), again.profiles.value["Acme DJ-2"]?.bindings)
    }

    @Test
    fun aFaderIsLearnedWithBothItsHalves() {
        val port = FakePort("Acme DJ-2").also { bus.plugged += it }
        val midi = start()
        eventually("listed") { midi.state("Acme DJ-2") == State.UNMAPPED }
        midi.learn("Acme DJ-2", MidiTarget(MidiAction.VOLUME))
        eventually("opened to learn") { midi.state("Acme DJ-2") == State.CONNECTED }
        // A platter touched on the way is not the fader.
        port.play(0x90, 0x36, 0x7F)
        port.play(0xB0, 0x13, 0x40)
        port.play(0xB0, 0x33, 0x00)
        eventually("learned") { midi.learning.value == null }
        assertEquals(
            listOf(MidiBinding(MidiKey(MidiType.CC, 0, 0x13), MidiAction.VOLUME, lsb = 0x33)),
            midi.profiles.value["Acme DJ-2"]?.bindings,
        )
    }

    @Test
    fun learningGivenUpLetsAnUnmappedPortGo() {
        val port = FakePort("Acme DJ-2").also { bus.plugged += it }
        val midi = start()
        eventually("listed") { midi.state("Acme DJ-2") == State.UNMAPPED }
        midi.learn("Acme DJ-2", MidiTarget(MidiAction.PLAY))
        eventually("opened to learn") { midi.state("Acme DJ-2") == State.CONNECTED }
        midi.cancelLearn()
        eventually("let go") { midi.state("Acme DJ-2") == State.UNMAPPED && port.closed }
    }

    @Test
    fun aBuiltInMappingEditedIsTheUsersUntilReset() {
        val port = FakePort("DDJ-400").also { bus.plugged += it }
        val midi = start()
        eventually("connected") { midi.state("DDJ-400") == State.CONNECTED }
        midi.learn("DDJ-400", MidiTarget(MidiAction.KEYLOCK))
        eventually("learning") { midi.learning.value != null }
        port.play(0x9F, 0x7E, 0x7F)
        eventually("learned") { midi.learning.value == null }
        val mine = midi.profiles.value.getValue("DDJ-400")
        // The whole built-in mapping, and the new button, for this controller only.
        assertEquals(listOf("DDJ-400"), mine.devices)
        assertEquals(ddj.bindings.filterNot { it.target == MidiTarget(MidiAction.KEYLOCK) }.size + 1, mine.bindings.size)
        val file = File(temp.root, "DDJ-400.json")
        assertTrue(file.isFile)

        midi.resetMapping("DDJ-400")
        eventually("built in again") { midi.profiles.value["DDJ-400"] == ddj }
        assertFalse(file.exists())
    }

    @Test
    fun aMixxxMappingIsImportedForAPort() {
        bus.plugged += FakePort("ACME-2 MIDI 1")
        val midi = start()
        eventually("listed") { midi.state("ACME-2 MIDI 1") == State.UNMAPPED }
        val xml = """
            <MixxxControllerPreset>
              <info><name>Acme DJ-2</name></info>
              <controller>
                <controls>
                  <control><group>[Channel1]</group><key>play</key><status>0x90</status><midino>0x0B</midino></control>
                  <control><group>[Channel1]</group><key>Acme.wheel</key><status>0xB0</status><midino>0x21</midino><options><script-binding/></options></control>
                </controls>
              </controller>
            </MixxxControllerPreset>
        """.trimIndent()
        val result = midi.importMixxx("ACME-2 MIDI 1", xml.byteInputStream())
        assertEquals(1, result.scripted)
        eventually("connected") {
            midi.ports.value == listOf(Status("ACME-2 MIDI 1", State.CONNECTED, midi = true, mapping = "Acme DJ-2"))
        }
        assertEquals(listOf("ACME-2 MIDI 1"), midi.profiles.value["ACME-2 MIDI 1"]?.devices)
    }

    @Test(expected = MixxxImport.FormatException::class)
    fun importingSomethingElseSaysSoAtOnce() {
        start().importMixxx("x", "not xml".byteInputStream())
    }

    @Test
    fun theConsoleListsMidiBesideHid() {
        bus.plugged += FakePort("DDJ-400")
        val noHid = object : HidBus {
            override fun devices() = emptyList<HidInfo>()
            override fun open(info: HidInfo): HidConnection = throw HidOpenException(busy = false, "none")
        }
        val manager = ControllerManager(surface, noHid, temp.root, scanIntervalMs = 10, midiBus = bus, mappingsDir = temp.newFolder())
        manager.start()
        try {
            eventually("the DDJ listed") {
                manager.controllers.value == listOf(Status("DDJ-400", State.CONNECTED, midi = true, mapping = ddj.name, verified = false))
            }
        } finally {
            manager.stop()
        }
    }
}
