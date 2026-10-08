package tf.monochrome.desktop.dj.controller

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The controller thread against a fake HID bus: a Traktor S2 MK1 that can be
 * plugged in, held by another program, and pulled out, with its reports
 * queued by the test and its writes recorded.
 */
class ControllerManagerTest {

    @get:Rule val temp = TemporaryFolder()

    private class FakeS2(
        val features: Map<Int, ByteArray> = validFeatures(),
        val initial: Map<Int, ByteArray> = emptyMap(),
    ) : HidConnection {
        val reports = LinkedBlockingQueue<ByteArray>()
        val writes = CopyOnWriteArrayList<ByteArray>()
        @Volatile var gone = false
        @Volatile var closed = false

        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            if (gone) throw IOException("device not connected")
            val r = reports.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            r.copyInto(buffer)
            return r.size
        }
        override fun write(report: ByteArray): Boolean {
            if (gone) return false
            writes += report.copyOf()
            return true
        }
        override fun feature(id: Int) = features[id]?.copyOf()
        override fun inputReport(id: Int) = initial[id]?.copyOf()
        override fun close() {
            closed = true
        }
    }

    private class FakeBus : HidBus {
        @Volatile var plugged: FakeS2? = null
        @Volatile var busy = false

        override fun devices() =
            if (plugged == null) emptyList()
            else listOf(HidInfo("\\\\?\\hid#vid_17cc&pid_1101#7&1&0000", TraktorS2Mk1.VENDOR_ID, TraktorS2Mk1.PRODUCT_ID))

        override fun open(info: HidInfo): HidConnection {
            if (busy) throw HidOpenException(busy = true, "sharing violation")
            return plugged ?: throw HidOpenException(busy = false, "gone")
        }
    }

    private val surface = FakeDjSurface()
    private val bus = FakeBus()
    private lateinit var manager: ControllerManager

    private fun start(): ControllerManager =
        ControllerManager(surface, bus, temp.root, scanIntervalMs = 10).also {
            manager = it
            it.start()
        }

    @After
    fun tearDown() {
        if (::manager.isInitialized) manager.stop()
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    private fun connected() = manager.controllers.value == listOf(ControllerManager.Status(TraktorS2Mk1.NAME, ControllerManager.State.CONNECTED))

    private fun buttons(vararg pressed: TraktorS2Mk1.Bit): ByteArray {
        val r = ByteArray(TraktorS2Mk1.BUTTONS_BYTES + 1)
        r[0] = TraktorS2Mk1.REPORT_BUTTONS.toByte()
        for (b in pressed) r[b.offset] = (r[b.offset].toInt() or b.mask).toByte()
        return r
    }

    private val load1 = TraktorS2Mk1.DECKS[0].load

    @Test
    fun invertTempoIsKeptAcrossRuns() {
        val first = ControllerManager(surface, bus, temp.root, scanIntervalMs = 10)
        assertFalse(first.invertTempo)
        first.invertTempo = true
        assertTrue(ControllerManager(surface, bus, temp.root, scanIntervalMs = 10).invertTempo)
        first.invertTempo = false
        assertFalse(ControllerManager(surface, bus, temp.root, scanIntervalMs = 10).invertTempo)
    }

    @Test
    fun pluggedInItConnectsAndLights() {
        val s2 = FakeS2()
        bus.plugged = s2
        start()
        eventually("connected") { connected() }
        eventually("the first LED report") { s2.writes.isNotEmpty() }
        val leds = s2.writes.first()
        assertEquals(TraktorS2Mk1.REPORT_LEDS, leds[0].toInt() and 0xFF)
        assertEquals(1 + TraktorS2Mk1.LED_PAYLOAD, leds.size)
    }

    @Test
    fun aButtonHeldWhilePluggingInIsNotAPress() {
        val s2 = FakeS2(initial = mapOf(TraktorS2Mk1.REPORT_BUTTONS to buttons(load1)))
        bus.plugged = s2
        start()
        eventually("connected") { connected() }
        s2.reports += buttons(load1)
        s2.reports += buttons()
        eventually("the reports read") { s2.reports.isEmpty() }
        Thread.sleep(50)
        assertFalse("still held from before: no load", "load 0" in surface.calls)

        s2.reports += buttons(load1)
        eventually("the press") { "load 0" in surface.calls }
        assertEquals(1, surface.calls.count { it == "load 0" })
    }

    @Test
    fun ledsGoOutOnlyWhenTheyChange() {
        val s2 = FakeS2()
        bus.plugged = s2
        start()
        eventually("the first LED report") { s2.writes.isNotEmpty() }
        // Nothing on the console moves: nothing more goes out.
        Thread.sleep(200)
        assertEquals(1, s2.writes.size)

        surface.toggleFx(0, 0)
        eventually("the FX button's LED") { s2.writes.size == 2 }
        val lit = s2.writes[1]
        assertEquals(TraktorS2Mk1.ON, lit[TraktorS2Mk1.FX[0].ledButtons[0]].toInt())
    }

    @Test
    fun unpluggedItLetsGoAndPluggedBackItReconnects() {
        val first = FakeS2()
        bus.plugged = first
        start()
        eventually("connected") { connected() }

        first.gone = true
        bus.plugged = null
        eventually("disconnected") { manager.controllers.value.isEmpty() }
        assertTrue(first.closed)

        val second = FakeS2()
        bus.plugged = second
        eventually("reconnected") { connected() && second.writes.isNotEmpty() }
    }

    @Test
    fun stoppedItLeavesTheControllerDarkButForTheWarningLight() {
        val s2 = FakeS2()
        bus.plugged = s2
        start()
        eventually("the first LED report") { s2.writes.isNotEmpty() }
        manager.stop()
        assertTrue(s2.closed)
        val last = s2.writes.last()
        assertArrayEquals(ControllerManager.shutdownLeds(), last)
        assertEquals(TraktorS2Mk1.ON, last[TraktorS2Mk1.LED_WARNING].toInt())
        assertEquals(1, last.count { it.toInt() != 0 } - 1)
        assertTrue(manager.controllers.value.isEmpty())
    }

    @Test
    fun heldByAnotherProgramItSaysSo() {
        bus.plugged = FakeS2()
        bus.busy = true
        start()
        eventually("busy") {
            manager.controllers.value == listOf(ControllerManager.Status(TraktorS2Mk1.NAME, ControllerManager.State.BUSY))
        }
        // Traktor closed: it connects on the next look.
        bus.busy = false
        eventually("connected") { connected() }
    }

    @Test
    fun calibrationFallsBackWhenMissingOrNonsense() {
        val good = ControllerManager.calibration(TraktorS2Mk1.CALIBRATION_REPORTS.map { validFeatures()[it] })
        assertNotSame(TraktorS2Mk1.Calibration.DEFAULT, good)
        assertEquals(TraktorS2Mk1.Fader(30, 4000), good.volume[0])

        val missing = TraktorS2Mk1.CALIBRATION_REPORTS.map { if (it == 0xD2) null else validFeatures()[it] }
        assertSame(TraktorS2Mk1.Calibration.DEFAULT, ControllerManager.calibration(missing))

        // A controller that answered with zeros would leave every fader dead.
        val zeros = TraktorS2Mk1.CALIBRATION_REPORTS.map { ByteArray(1 + TraktorS2Mk1.CALIBRATION_BYTES).also { r -> r[0] = it.toByte() } }
        assertSame(TraktorS2Mk1.Calibration.DEFAULT, ControllerManager.calibration(zeros))
    }

    @Test
    fun theReportLogHasTheCalibrationAndEveryReport() {
        val s2 = FakeS2()
        bus.plugged = s2
        start()
        manager.logReports = true
        eventually("the log open") { manager.logFile.value != null }
        val file = manager.logFile.value!!
        // No initial report from this one: the first read is where everything is.
        s2.reports += buttons()
        s2.reports += buttons(load1)
        eventually("the report read") { "load 0" in surface.calls }
        manager.stop()
        assertEquals(null, manager.logFile.value)

        val lines = file.readLines()
        assertTrue(lines.any { it.startsWith("# feature d0: d0 ") })
        val press = ControllerManager.hex(buttons(load1), TraktorS2Mk1.BUTTONS_BYTES + 1)
        assertTrue("a timestamp, then the report: $lines", lines.any { it.matches(Regex("\\d+ " + Regex.escape(press))) })
    }

    companion object {
        /**
         * Feature reports 0xD0..0xD4, ID first: deck 1's volume fader runs
         * 30..4000, every knob 16 / 2048 / 4080, the platters' touch
         * thresholds 0x0CE6.
         */
        fun validFeatures(): Map<Int, ByteArray> {
            fun le(b: ByteArray, i: Int, v: Int) { b[i] = v.toByte(); b[i + 1] = (v shr 8).toByte() }
            val faders = ByteArray(32)
            le(faders, 0x0C, 30); le(faders, 0x0E, 4000)
            le(faders, 0x10, 16); le(faders, 0x12, 4080)
            le(faders, 0x14, 16); le(faders, 0x16, 4080)
            val knobs = ByteArray(96)
            for (k in 0 until 96 / 6) {
                le(knobs, 6 * k, 16); le(knobs, 6 * k + 2, 2048); le(knobs, 6 * k + 4, 4080)
            }
            val jogs = ByteArray(32)
            for (i in intArrayOf(0x02, 0x06)) { jogs[i] = 0x0C; jogs[i + 1] = 0xE6.toByte() }
            val payloads = listOf(faders, knobs.copyOfRange(0, 32), knobs.copyOfRange(32, 64), knobs.copyOfRange(64, 96), jogs)
            return TraktorS2Mk1.CALIBRATION_REPORTS.zip(payloads).associate { (id, p) -> id to byteArrayOf(id.toByte()) + p }
        }
    }
}
