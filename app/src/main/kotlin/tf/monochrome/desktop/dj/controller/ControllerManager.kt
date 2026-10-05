package tf.monochrome.desktop.dj.controller

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tf.monochrome.desktop.dj.DjEngine
import tf.monochrome.desktop.platform.AppPaths

/**
 * The DJ controllers plugged in, found and driven without a setup step.
 *
 * One thread, "dj-controllers", owns the hardware. While nothing is
 * connected it looks for a known controller every [scanIntervalMs]; plugged
 * in, it is opened, its calibration read, the position of every control
 * taken as the starting point (so a button held while plugging in is not a
 * press, and a fader that is down does not mute a deck: see [SoftTakeover]),
 * and from then on every report goes to the mapping and the LEDs follow the
 * console. Unplugged, the read fails, the mapping forgets the controller, and
 * the thread goes back to looking.
 *
 * Over HID, that is the Traktor Kontrol S2 MK1. Its controls need no driver:
 * Windows' own HID class driver serves them (Native Instruments' driver is
 * only for its sound card). Over USB MIDI, it is any controller with a
 * mapping, built in, learned or imported from Mixxx: [midi] has those, on a
 * thread of its own, and [controllers] lists both.
 *
 * [logReports] writes every report read, with the calibration, to a file
 * under the logs directory: what verifying the mapping against a real
 * controller needs, and what a bug report about one should carry.
 */
@Singleton
class ControllerManager internal constructor(
    private val dj: DjSurface,
    private val hid: HidBus,
    private val logsDir: File,
    private val scanIntervalMs: Long,
    midiBus: MidiBus = NoMidiBus,
    mappingsDir: File = File(logsDir, "controllers"),
) {
    @Inject constructor(dj: DjEngine, hid: HidBus, midiBus: MidiBus, paths: AppPaths) :
        this(dj, hid, paths.logsDir, SCAN_INTERVAL_MS, midiBus, paths.dataDir.resolve("controllers"))

    enum class State {
        CONNECTED,
        /** Plugged in, but another program holds it (Traktor, NI's Controller Editor). */
        BUSY,
        /** Plugged in, and it would not open. */
        FAILED,
        /** A MIDI port with no mapping: left closed for other programs, until one is learned or imported. */
        UNMAPPED,
    }

    data class Status(
        val name: String,
        val state: State,
        /** A MIDI port, [name] as Windows calls it; otherwise a HID controller. */
        val midi: Boolean = false,
        /** The MIDI mapping driving it. */
        val mapping: String? = null,
        /** False while [mapping] is a built-in one not yet checked on the hardware. */
        val verified: Boolean = true,
    )

    private val _controllers = MutableStateFlow<List<Status>>(emptyList())
    /** The known controllers plugged in, and whether each is driving the console. */
    val controllers: StateFlow<List<Status>> = _controllers.asStateFlow()

    private val _logFile = MutableStateFlow<File?>(null)
    /** Where [logReports] is writing, while it is. */
    val logFile: StateFlow<File?> = _logFile.asStateFlow()

    private val s2 = S2Mk1Mapping(dj)

    private val statusLock = Any()
    private var hidStatus: List<Status> = emptyList()
    private var midiStatus: List<Status> = emptyList()

    /** The MIDI controllers: their mappings, learning, and importing. */
    val midi = MidiControllers(dj, midiBus, mappingsDir, scanIntervalMs, invertTempo = { s2.invertTempo }) { list ->
        synchronized(statusLock) {
            midiStatus = list
            _controllers.value = hidStatus + midiStatus
        }
    }

    /** See [S2Mk1Mapping.invertTempo]; MIDI tempo faders follow it too. */
    var invertTempo: Boolean
        get() = s2.invertTempo
        set(value) {
            s2.invertTempo = value
        }

    /** Write every report to a file in the logs directory; the thread opens and closes it. */
    @Volatile var logReports: Boolean = false

    private val lock = Any()
    private var hooked = false
    private var thread: Thread? = null
    private var stopping = CountDownLatch(0)
    @Volatile private var running = false

    /** Starts looking. Idempotent. */
    fun start() {
        synchronized(lock) {
            if (thread != null) return
            if (!hooked) {
                hooked = true
                // However the app exits, the controller is left saying nothing drives it.
                Runtime.getRuntime().addShutdownHook(Thread(::stop, "dj-controllers-stop"))
            }
            running = true
            stopping = CountDownLatch(1)
            thread = Thread(::run, "dj-controllers").apply {
                isDaemon = true
                start()
            }
        }
        midi.start()
    }

    /**
     * Stops, and hands a connected controller back dark but for its warning
     * light, as Mixxx leaves it: nothing is driving it now. Waits for that.
     */
    fun stop() {
        midi.stop()
        val t = synchronized(lock) {
            running = false
            stopping.countDown()
            thread.also { thread = null }
        } ?: return
        t.join(STOP_TIMEOUT_MS)
    }

    private fun run() {
        try {
            while (running) {
                val info = hid.devices().firstOrNull { it.vendorId == TraktorS2Mk1.VENDOR_ID && it.productId == TraktorS2Mk1.PRODUCT_ID }
                if (info == null) {
                    publish(null)
                } else {
                    try {
                        drive(hid.open(info))
                    } catch (e: HidOpenException) {
                        publish(if (e.busy) State.BUSY else State.FAILED)
                    } catch (e: RuntimeException) {
                        // A bug, not the hardware: the thread lives on, and tries again.
                        Log.e(TAG, "controller session failed", e)
                        publish(State.FAILED)
                    }
                }
                if (stopping.await(scanIntervalMs, TimeUnit.MILLISECONDS)) break
            }
        } finally {
            publish(null)
            closeLog()
        }
    }

    /** One connection, until it is unplugged or [stop] is called. */
    private fun drive(connection: HidConnection) {
        val mapping = s2
        mapping.reset()
        try {
            features = TraktorS2Mk1.CALIBRATION_REPORTS.map { connection.feature(it) }
            mapping.calibration = calibration(features)
            connectedAt = now()
            publish(State.CONNECTED)
            Log.i(TAG, "${TraktorS2Mk1.NAME} connected" + if (mapping.calibration === TraktorS2Mk1.Calibration.DEFAULT) ", uncalibrated" else "")
            syncLog()
            // Where every control is now: the baseline the first report is compared with.
            for (id in INITIAL_REPORTS) {
                val report = connection.inputReport(id) ?: continue
                log(report, report.size)
                mapping.onReport(report, report.size, now())
            }
            val buffer = ByteArray(REPORT_BUFFER)
            val leds = ByteArray(1 + TraktorS2Mk1.LED_PAYLOAD)
            val payload = ByteArray(TraktorS2Mk1.LED_PAYLOAD)
            var sent: ByteArray? = null
            // Due at once. (Not Long.MIN_VALUE: now minus that overflows, and they'd never be due.)
            var ledsAt = now() - LED_INTERVAL_MS
            while (running) {
                val n = connection.read(buffer, READ_TIMEOUT_MS)
                val now = now()
                if (n > 0) {
                    log(buffer, n)
                    mapping.onReport(buffer, n, now)
                }
                mapping.tick(now)
                if (now - ledsAt >= LED_INTERVAL_MS) {
                    ledsAt = now
                    // Only what changed goes out: an idle console sends nothing.
                    mapping.leds(payload)
                    if (sent == null || !payload.contentEquals(sent)) {
                        leds[0] = TraktorS2Mk1.REPORT_LEDS.toByte()
                        payload.copyInto(leds, 1)
                        sent = if (connection.write(leds)) payload.copyOf() else null
                    }
                }
                syncLog()
            }
            connection.write(shutdownLeds())
        } catch (e: IOException) {
            // Unplugged: back to looking.
            Log.i(TAG, "${TraktorS2Mk1.NAME} disconnected: ${e.message}")
        } finally {
            connection.close()
            mapping.release()
            mapping.reset()
            features = emptyList()
            publish(null)
        }
    }

    private fun publish(state: State?) {
        synchronized(statusLock) {
            hidStatus = if (state == null) emptyList() else listOf(Status(TraktorS2Mk1.NAME, state))
            _controllers.value = hidStatus + midiStatus
        }
    }

    // ── The report log ────────────────────────────────────────────────

    /** The calibration reports as read, for the log's header. Reader thread. */
    private var features: List<ByteArray?> = emptyList()
    private var connectedAt = 0L
    private var log: BufferedWriter? = null
    private var flushedAt = 0L

    /** Opens or closes the log as [logReports] asks; flushes it now and then. Reader thread. */
    private fun syncLog() {
        val want = logReports && hidStatus.any { it.state == State.CONNECTED }
        val writer = log
        if (want && writer == null) {
            openLog()
        } else if (!want && writer != null) {
            closeLog()
        } else if (writer != null && now() - flushedAt >= LOG_FLUSH_MS) {
            runCatching { writer.flush() }
            flushedAt = now()
        }
    }

    private fun openLog() {
        val file = logsDir.resolve("controller-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".log")
        val writer = runCatching { file.bufferedWriter() }.getOrNull() ?: return
        log = writer
        _logFile.value = file
        runCatching {
            writer.write("# ${TraktorS2Mk1.NAME}: ms since connected, then the report, ID first\n")
            TraktorS2Mk1.CALIBRATION_REPORTS.forEachIndexed { i, id ->
                writer.write("# feature %02x: %s\n".format(id, features.getOrNull(i)?.let { hex(it, it.size) } ?: "none"))
            }
        }
    }

    private fun closeLog() {
        log?.let { runCatching { it.close() } }
        log = null
        _logFile.value = null
    }

    private fun log(report: ByteArray, length: Int) {
        val writer = log ?: return
        runCatching {
            writer.write((now() - connectedAt).toString())
            writer.write(" ")
            writer.write(hex(report, length))
            writer.write("\n")
        }.onFailure { closeLog() }
    }

    companion object {
        private const val TAG = "ControllerManager"
        const val SCAN_INTERVAL_MS = 1500L
        private const val READ_TIMEOUT_MS = 10
        /** About 30 frames a second: the meters move smoothly, the bus stays quiet. */
        private const val LED_INTERVAL_MS = 33L
        private const val LOG_FLUSH_MS = 1000L
        private const val STOP_TIMEOUT_MS = 2000L
        /** Longer than any report the S2 sends; Windows pads them to the longest. */
        private const val REPORT_BUFFER = 256
        private val INITIAL_REPORTS = intArrayOf(TraktorS2Mk1.REPORT_BUTTONS, TraktorS2Mk1.REPORT_ANALOG)

        private fun now() = System.nanoTime() / 1_000_000

        /**
         * The controller's own calibration, from feature reports 0xD0..0xD4
         * as read (ID first). The defaults when any is missing or reads as
         * nonsense: every control still works, over the factory range.
         */
        internal fun calibration(features: List<ByteArray?>): TraktorS2Mk1.Calibration {
            val parts = features.map { r ->
                if (r == null || r.size < 1 + TraktorS2Mk1.CALIBRATION_BYTES) return TraktorS2Mk1.Calibration.DEFAULT
                r.copyOfRange(1, 1 + TraktorS2Mk1.CALIBRATION_BYTES)
            }
            if (parts.size != TraktorS2Mk1.CALIBRATION_REPORTS.size) return TraktorS2Mk1.Calibration.DEFAULT
            val parsed = TraktorS2Mk1.Calibration.parse(parts[0], parts[1], parts[2], parts[3], parts[4])
            return if (parsed.plausible) parsed else TraktorS2Mk1.Calibration.DEFAULT
        }

        /** The LED report a controller is left with: dark, the warning light on. */
        internal fun shutdownLeds(): ByteArray = ByteArray(1 + TraktorS2Mk1.LED_PAYLOAD).also {
            it[0] = TraktorS2Mk1.REPORT_LEDS.toByte()
            it[TraktorS2Mk1.LED_WARNING] = TraktorS2Mk1.ON.toByte()
        }

        internal fun hex(bytes: ByteArray, length: Int): String =
            (0 until length).joinToString(" ") { "%02x".format(bytes[it].toInt() and 0xFF) }
    }
}
