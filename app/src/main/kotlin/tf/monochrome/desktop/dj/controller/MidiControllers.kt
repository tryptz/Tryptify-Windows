package tf.monochrome.desktop.dj.controller

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import tf.monochrome.desktop.dj.controller.ControllerManager.State
import tf.monochrome.desktop.dj.controller.ControllerManager.Status

/**
 * The USB MIDI controllers plugged in, each driven by its [MidiProfile].
 *
 * One thread, "dj-midi", owns them. Every [scanIntervalMs] it lists the
 * MIDI inputs and finds each one's mapping: the user's (learned or imported,
 * kept as JSON under [dir]) before a built-in one ([BuiltInMidiProfiles]).
 * A port with a mapping is opened and driven; one without is left closed and
 * listed as [State.UNMAPPED], for the user to [learn] or [importMixxx].
 * That matters on Windows: a WinMM input is the first program's that opens
 * it, and a port opened here for nothing would be lost to Traktor or Serato.
 *
 * The transport's own threads only queue what arrives. Everything else
 * happens on "dj-midi": the messages, in order, the mapping's clock (SYNC
 * held), learning, and the LEDs, at most every [LED_INTERVAL_MS] and only
 * those that changed.
 *
 * Learning: [learn] names a control's job; the next control moved on that
 * port takes it. A button is learned from its press. A knob or fader is
 * heard for [LEARN_WINDOW_MS], long enough to catch both halves of a 14-bit
 * control (CC n, then CC n + 32), and an encoder's values say how it counts.
 * What is learned is saved at once.
 */
class MidiControllers internal constructor(
    private val dj: DjSurface,
    private val bus: MidiBus,
    /** Where learned and imported mappings are kept, one JSON file each. */
    private val dir: File,
    private val scanIntervalMs: Long,
    private val invertTempo: () -> Boolean = { false },
    /** Called on "dj-midi" whenever [ports] changes. */
    private val onChange: (List<Status>) -> Unit = {},
) {
    /** The control on [port] that is next moved will do [target]. */
    data class Learning(val port: String, val target: MidiTarget)

    private val _ports = MutableStateFlow<List<Status>>(emptyList())
    /** Every MIDI input, and whether a mapping drives it. */
    val ports: StateFlow<List<Status>> = _ports.asStateFlow()

    private val _profiles = MutableStateFlow<Map<String, MidiProfile>>(emptyMap())
    /** The mapping for each port that has one, by port name. */
    val profiles: StateFlow<Map<String, MidiProfile>> = _profiles.asStateFlow()

    private val _learning = MutableStateFlow<Learning?>(null)
    /** The control being learned, until it is. */
    val learning: StateFlow<Learning?> = _learning.asStateFlow()

    // ── The thread's state ────────────────────────────────────────────

    private class Port(val name: String) {
        var state = State.UNMAPPED
        var profile: MidiProfile? = null
        /** The user's file [profile] came from; null for a built-in one. */
        var file: File? = null
        var session: Session? = null
    }

    /** One opening of a port: messages queued from an earlier one are dropped. */
    private class Session(val port: Port, val engine: MidiMappingEngine) {
        lateinit var connection: MidiConnection
        @Volatile var open = true
    }

    private sealed interface Event
    private class Message(val session: Session, val status: Int, val data1: Int, val data2: Int) : Event
    private class Command(val run: () -> Unit) : Event

    private val events = LinkedBlockingQueue<Event>()
    private val byName = LinkedHashMap<String, Port>()
    /** The user's mappings, by file, in the order they are matched. */
    private val user = LinkedHashMap<File, MidiProfile>()
    private val heard = ArrayList<Message>()
    private var heardAt = 0L

    private val lock = Any()
    private var thread: Thread? = null
    private var stopping = CountDownLatch(0)
    @Volatile private var running = false

    // ── Starting and stopping ─────────────────────────────────────────

    /** Starts looking. Idempotent. */
    fun start() {
        synchronized(lock) {
            if (thread != null) return
            running = true
            stopping = CountDownLatch(1)
            thread = Thread(::run, "dj-midi").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Stops, leaving every controller's LEDs dark. Waits for that. */
    fun stop() {
        val t = synchronized(lock) {
            running = false
            stopping.countDown()
            thread.also { thread = null }
        } ?: return
        events.offer(Command {})
        t.join(STOP_TIMEOUT_MS)
    }

    // ── What the screen asks for ──────────────────────────────────────

    /** The next control moved on [port] will do [target]; a port with no mapping is opened for it. */
    fun learn(port: String, target: MidiTarget) = post {
        heard.clear()
        val was = _learning.value
        _learning.value = Learning(port, target)
        if (was != null && was.port != port) byName[was.port]?.let(::refresh)
        byName[port]?.let(::refresh)
        publish()
    }

    fun cancelLearn() = post {
        val was = _learning.value ?: return@post
        heard.clear()
        _learning.value = null
        // A port opened only to learn on, with nothing learned, is let go.
        byName[was.port]?.let(::refresh)
        publish()
    }

    /** Without [binding] on [port]. */
    fun forget(port: String, binding: MidiBinding) = post {
        byName[port]?.let { p -> edit(p) { it.forget(binding) } }
    }

    /** Back to the built-in mapping, or none: the user's for [port] is deleted. */
    fun resetMapping(port: String) = post {
        val p = byName[port] ?: return@post
        val file = p.file ?: return@post
        user.remove(file)
        if (!file.delete() && file.exists()) Log.w(TAG, "could not delete $file")
        refresh(p)
        publish()
    }

    /**
     * [input], a Mixxx mapping, as [port]'s, in place of whatever it had.
     * Read here, so a file that is not one throws [MixxxImport.FormatException]
     * to the caller; applied on the MIDI thread.
     */
    @Throws(MixxxImport.FormatException::class)
    fun importMixxx(port: String, input: InputStream): MixxxImport.Result {
        val result = MixxxImport.read(input, device(port))
        post {
            val p = byName[port]
            if (p == null) {
                save(File(dir, fileName(device(port))), result.profile)
            } else {
                edit(p) { mine -> result.profile.copy(devices = mine.devices) }
            }
        }
        return result
    }

    private fun post(run: () -> Unit) {
        events.offer(Command(run))
    }

    // ── The thread ────────────────────────────────────────────────────

    private fun run() {
        try {
            load()
            var scanAt = now() - scanIntervalMs
            var ledsAt = now() - LED_INTERVAL_MS
            while (running) {
                var now = now()
                if (now - scanAt >= scanIntervalMs) {
                    scanAt = now
                    scan()
                }
                // Nothing open, nothing to watch: wait for the next look, or a command.
                val active = byName.values.any { it.session != null }
                val wait = if (active) POLL_MS else (scanIntervalMs - (now - scanAt)).coerceIn(1, scanIntervalMs)
                var event = events.poll(wait, TimeUnit.MILLISECONDS)
                // A jog wheel sends hundreds a second: take all that are waiting.
                while (event != null && running) {
                    handle(event)
                    event = events.poll()
                }
                now = now()
                for (p in byName.values) p.session?.engine?.tick(now)
                settle(now)
                if (now - ledsAt >= LED_INTERVAL_MS) {
                    ledsAt = now
                    for (p in byName.values) {
                        val s = p.session ?: continue
                        if (s.connection.hasOutput) s.engine.leds(s.connection::send)
                    }
                }
            }
        } catch (e: InterruptedException) {
            // Stopping.
        } finally {
            for (p in byName.values) close(p)
            byName.clear()
            heard.clear()
            _learning.value = null
            publish()
        }
    }

    private fun handle(event: Event) {
        try {
            when (event) {
                is Command -> event.run()
                is Message -> onMessage(event)
            }
        } catch (e: RuntimeException) {
            // A bug, not the hardware: the thread lives on.
            Log.e(TAG, "MIDI event failed", e)
        }
    }

    private fun onMessage(m: Message) {
        val s = m.session
        if (!s.open) return
        val learning = _learning.value
        if (learning != null && learning.port == s.port.name) {
            hear(learning, m)
        } else {
            s.engine.onMessage(m.status, m.data1, m.data2, now())
        }
    }

    // ── Ports ─────────────────────────────────────────────────────────

    private fun scan() {
        val names = try {
            bus.inputs()
        } catch (e: RuntimeException) {
            // The list could not be read this time: nothing has changed.
            Log.w(TAG, "listing MIDI inputs failed", e)
            return
        }
        for (gone in byName.keys - names.toSet()) {
            byName.remove(gone)?.let(::close)
            Log.i(TAG, "$gone unplugged")
        }
        for (name in names) refresh(byName.getOrPut(name) { Port(name) })
        publish()
    }

    /** Opens [p], closes it, or swaps its mapping, as its mapping now says. */
    private fun refresh(p: Port) {
        val found = resolve(p.name)
        if (found == null) {
            close(p)
            p.profile = null
            p.file = null
            p.state = State.UNMAPPED
            return
        }
        val (profile, file) = found
        p.profile = profile
        p.file = file
        val s = p.session
        if (s == null) {
            open(p, profile)
        } else if (s.engine.profile != profile) {
            // The old mapping's LEDs go out before the new one's come on.
            if (s.connection.hasOutput) s.engine.dark(s.connection::send)
            s.engine.release()
            s.engine.profile = profile
        }
    }

    private fun open(p: Port, profile: MidiProfile) {
        val session = Session(p, MidiMappingEngine(dj, profile, invertTempo))
        try {
            session.connection = bus.open(p.name) { status, data1, data2 ->
                events.offer(Message(session, status, data1, data2))
            }
            p.session = session
            p.state = State.CONNECTED
            Log.i(TAG, "${p.name} connected: ${profile.name}")
        } catch (e: MidiOpenException) {
            session.open = false
            p.state = if (e.busy) State.BUSY else State.FAILED
        }
    }

    private fun close(p: Port) {
        val s = p.session ?: return
        p.session = null
        s.open = false
        s.engine.release()
        try {
            if (s.connection.hasOutput) s.engine.dark(s.connection::send)
            s.connection.close()
        } catch (e: IOException) {
            // Unplugged: there is nothing left to close.
        } catch (e: RuntimeException) {
            Log.w(TAG, "closing ${p.name} failed", e)
        }
    }

    /**
     * [port]'s mapping: the user's for exactly this controller, then the
     * user's that matches its name, then a built-in one; an empty one while
     * a control is being learned on it; otherwise none.
     */
    private fun resolve(port: String): Pair<MidiProfile, File?>? {
        val device = device(port)
        user.entries.firstOrNull { (_, p) -> p.devices.any { it.equals(device, ignoreCase = true) || it.equals(port, ignoreCase = true) } }
            ?.let { return it.value to it.key }
        user.entries.firstOrNull { it.value.matches(port) }?.let { return it.value to it.key }
        BuiltInMidiProfiles.forPort(port)?.let { return it to null }
        if (_learning.value?.port == port) return MidiProfile(device, listOf(device)) to null
        return null
    }

    private fun publish() {
        val list = byName.values.map { p ->
            Status(p.name, p.state, midi = true, mapping = p.profile?.name, verified = p.profile?.verified ?: true)
        }
        _profiles.value = byName.values.mapNotNull { p -> p.profile?.let { p.name to it } }.toMap()
        if (list != _ports.value) {
            _ports.value = list
            onChange(list)
        }
    }

    // ── Learning ──────────────────────────────────────────────────────

    private fun hear(learning: Learning, m: Message) {
        val key = MidiKey.of(m.status, m.data1) ?: return
        val t = learning.target
        when (t.action.kind) {
            MidiAction.Kind.BUTTON -> {
                val press = when (key.type) {
                    MidiType.NOTE -> (m.status and 0xF0) == 0x90 && m.data2 > 0
                    MidiType.CC -> m.data2 > 0
                    MidiType.PITCH_BEND -> false
                }
                if (press) learned(learning, MidiBinding(key, t.action, t.unit, t.slot))
            }
            MidiAction.Kind.ABSOLUTE, MidiAction.Kind.RELATIVE -> {
                // A button pressed by the way (a platter touched to turn it) is not the control.
                if (key.type == MidiType.NOTE) return
                if (key.type == MidiType.PITCH_BEND && t.action.kind == MidiAction.Kind.RELATIVE) return
                if (heard.isEmpty()) heardAt = now()
                heard += m
            }
        }
    }

    /** Once a knob, fader or encoder has been heard for long enough, what it was. */
    private fun settle(now: Long) {
        val learning = _learning.value
        if (learning == null || heard.isEmpty()) return
        if (now - heardAt < LEARN_WINDOW_MS) return
        val binding = binding(learning.target, heard.map { Triple(it.status, it.data1, it.data2) })
        heard.clear()
        if (binding != null) learned(learning, binding)
    }

    private fun learned(learning: Learning, binding: MidiBinding) {
        val p = byName[learning.port] ?: return
        heard.clear()
        Log.i(TAG, "${p.name}: ${binding.key} learned as ${binding.action}")
        edit(p) { it.learn(binding) }
        // Only now: whoever sees learning end sees the mapping with what was learned.
        _learning.value = null
    }

    // ── The user's mappings ───────────────────────────────────────────

    /** [p]'s mapping changed and saved as the user's: a built-in one is copied first, for this controller only. */
    private fun edit(p: Port, change: (MidiProfile) -> MidiProfile) {
        val device = device(p.name)
        val file = p.file ?: File(dir, fileName(device))
        val base = p.profile ?: MidiProfile(device)
        val mine = if (p.file == null) base.copy(devices = listOf(device)) else base
        save(file, change(mine))
        refresh(p)
        publish()
    }

    private fun save(file: File, profile: MidiProfile) {
        user[file] = profile
        try {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(JSON.encodeToString(MidiProfile.serializer(), profile))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            // Kept for this run, at least.
            Log.w(TAG, "could not save $file", e)
        }
    }

    private fun load() {
        user.clear()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name } ?: return
        for (f in files) {
            runCatching { JSON.decodeFromString(MidiProfile.serializer(), f.readText()) }
                .onSuccess { user[f] = it }
                .onFailure { Log.w(TAG, "ignoring ${f.name}: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "MidiControllers"
        private const val POLL_MS = 10L
        /** As the S2's: about 30 frames a second. */
        private const val LED_INTERVAL_MS = 33L
        /** Long enough for both halves of a 14-bit control, short enough not to notice. */
        internal const val LEARN_WINDOW_MS = 50L
        private const val STOP_TIMEOUT_MS = 2000L

        private val JSON = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        private fun now() = System.nanoTime() / 1_000_000

        private val NUMBERED = Regex("""^\d+-\s*""")
        private val SUFFIX = Regex("""\s\(\d+\)$""")
        private val UNSAFE = Regex("""[^A-Za-z0-9._-]+""")

        /**
         * The controller a port is: Windows calls a second DDJ-400 "2- DDJ-400",
         * and JavaMidiBus a second port of one name "DDJ-400 (2)". Both are "DDJ-400".
         */
        internal fun device(port: String): String =
            port.replace(NUMBERED, "").replace(SUFFIX, "").trim().ifEmpty { port }

        internal fun fileName(device: String): String =
            device.replace(UNSAFE, "_").trim('_', '.').ifEmpty { "controller" } + ".json"

        /**
         * What a control moved during learning was, from the messages it sent
         * (status, data1, data2): null if they were nothing [target] can use.
         */
        internal fun binding(target: MidiTarget, messages: List<Triple<Int, Int, Int>>): MidiBinding? {
            val keys = messages.mapNotNull { (s, d1, _) -> MidiKey.of(s, d1) }
            val first = keys.firstOrNull() ?: return null
            val a = target.action
            return when {
                first.type == MidiType.PITCH_BEND -> MidiBinding(first, a, target.unit, target.slot)
                a.kind == MidiAction.Kind.RELATIVE -> {
                    val values = messages.filter { (s, d1, _) -> MidiKey.of(s, d1) == first }.map { it.third }
                    // A jog wheel says 63 or 65 for a step; an encoder 127 or 1.
                    val encoding = if (values.all { it in 48..80 }) Relative.OFFSET_64 else Relative.TWOS_COMPLEMENT
                    MidiBinding(first, a, target.unit, target.slot, encoding = encoding)
                }
                else -> {
                    // A 14-bit control: its high half on CC n (below 32), its low on CC n + 32.
                    val msb = keys.firstOrNull { k ->
                        k.type == MidiType.CC && k.number < 32 && MidiKey(MidiType.CC, k.channel, k.number + 32) in keys
                    }
                    if (msb != null) {
                        MidiBinding(msb, a, target.unit, target.slot, lsb = msb.number + 32)
                    } else {
                        keys.firstOrNull { it.type == MidiType.CC }?.let { MidiBinding(it, a, target.unit, target.slot) }
                    }
                }
            }
        }
    }
}
