package tf.monochrome.desktop.debug

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feeds the process's own log into [DebugLogBuffer] for the in-app debug log.
 *
 * Android: a long-running `logcat -v threadtime --pid=<us>` subprocess, which
 * captured everything the platform logger saw for the process — our Log.*
 * calls, `System.err` (tag `System.err`, level W), framework warnings.
 *
 * Desktop: there is no logcat. The `android.util.Log` shim offers a [Log.sink]
 * that receives every line it prints, so our own Log.* calls arrive with their
 * level and tag intact; and `System.err` / `System.out` are teed, so library
 * warnings and uncaught-exception traces land under `System.err` /
 * `System.out` exactly as logcat filed them. Every entry is given a logcat
 * `threadtime` line as its [DebugLogEntry.raw], so the export reads the same
 * as on Android and [parse] round-trips it.
 *
 * Desktop: what native code writes straight to file descriptor 2 (the DSP's
 * `<android/log.h>` compat layer) bypasses `System.err` and is not captured;
 * the shim's `Log.minLevel` also drops DEBUG/VERBOSE lines before the sink.
 */
@Singleton
class DebugLogCollector @Inject constructor(
    private val buffer: DebugLogBuffer,
) {
    private val lock = Any()
    private var running = false

    private var previousSink: ((level: Int, tag: String, message: String) -> Unit)? = null
    private var ourSink: ((level: Int, tag: String, message: String) -> Unit)? = null
    private var originalErr: PrintStream? = null
    private var originalOut: PrintStream? = null
    private var teeErr: PrintStream? = null
    private var teeOut: PrintStream? = null

    private val pid: Int = runCatching { ProcessHandle.current().pid().toInt() }.getOrDefault(0)

    /** Guards against a capture that itself prints (it never should) looping forever. */
    private val recording = ThreadLocal.withInitial { false }

    /** Idempotent; safe to call multiple times. Starts at app boot. */
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            val previous = Log.sink
            val sink: (Int, String, String) -> Unit = { level, tag, message ->
                previous?.invoke(level, tag, message)
                record(levelChar(level), tag, message)
            }
            previousSink = previous
            ourSink = sink
            Log.sink = sink
            installStreamTees()
        }
    }

    /** Only used by unit-test / teardown paths — the collector normally runs for the whole process lifetime. */
    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            if (Log.sink === ourSink) Log.sink = previousSink
            ourSink = null
            previousSink = null
            if (System.err === teeErr) originalErr?.let { System.setErr(it) }
            if (System.out === teeOut) originalOut?.let { System.setOut(it) }
            teeErr = null
            teeOut = null
            originalErr = null
            originalOut = null
        }
    }

    private fun installStreamTees() {
        runCatching {
            val err = System.err
            val tee = TeePrintStream(CapturingStream(err, err.charset()) { line -> if (!Log.isEmittingConsoleLine()) record('W', "System.err", line) }, err.charset())
            originalErr = err
            teeErr = tee
            System.setErr(tee)
        }
        runCatching {
            val out = System.out
            val tee = TeePrintStream(CapturingStream(out, out.charset()) { line -> record('I', "System.out", line) }, out.charset())
            originalOut = out
            teeOut = tee
            System.setOut(tee)
        }
    }

    /** One entry per line, as logcat splits a multi-line message. */
    private fun record(level: Char, tag: String, message: String) {
        if (recording.get()) return
        recording.set(true)
        try {
            val timestamp = LocalDateTime.now().format(TIMESTAMP)
            val tid = Thread.currentThread().threadId().toInt()
            val lines = message.split('\n').map { it.trimEnd('\r') }.dropLastWhile { it.isEmpty() }.ifEmpty { listOf("") }
            for (line in lines) {
                val raw = String.format(Locale.US, "%s %5d %5d %c %s: %s", timestamp, pid, tid, level, tag, line)
                val entry = DebugLogEntry(
                    timestamp = timestamp,
                    pid = pid,
                    tid = tid,
                    level = level,
                    tag = tag,
                    message = line,
                    raw = raw,
                )
                if (!isNoise(entry)) buffer.append(entry)
            }
        } catch (_: Throwable) {
            // Logging must never take the app down with it.
        } finally {
            recording.set(false)
        }
    }

    /**
     * Parse a single `-v threadtime` line:
     * `MM-DD HH:MM:SS.SSS  PID  TID LVL TAG: MESSAGE`
     *
     * Returns a best-effort entry; anything that doesn't match is preserved
     * verbatim as an INFO line so nothing is silently dropped. On the desktop
     * the collector writes this shape itself (see [record]); the parser stays
     * for anything that reads an export back.
     */
    internal fun parse(line: String): DebugLogEntry {
        val match = THREADTIME_REGEX.matchEntire(line)
        if (match == null) {
            return DebugLogEntry(
                timestamp = "",
                pid = 0,
                tid = 0,
                level = 'I',
                tag = "?",
                message = line,
                raw = line,
            )
        }
        val (ts, pidStr, tidStr, levelStr, tag, message) = match.destructured
        return DebugLogEntry(
            timestamp = ts,
            pid = pidStr.toIntOrNull() ?: 0,
            tid = tidStr.toIntOrNull() ?: 0,
            level = levelStr.firstOrNull() ?: 'I',
            tag = tag.trim(),
            message = message,
            raw = line,
        )
    }

    /**
     * Filter out framework-internal chatter that floods the in-app debug log
     * without telling us anything we'd act on. PipelineWatcher's
     * "pipelineFull: too many frames in pipeline (N)" is normal Media3
     * codec back-pressure during buffering and prebuffering — not an error.
     * Same idea for the other entries: pure plumbing noise from system
     * components, never our app's behavior.
     *
     * Some of it is logged at ERROR, which matters more than the volume does:
     * the viewer's Errors tab is where you look when something is actually
     * wrong, and a screen of vendor chatter there is worse than a long All tab.
     * Everything dropped below was filling that tab on a ColorOS device while
     * the app was working perfectly.
     *
     * Desktop: none of these Android sources exist here, so the lists never
     * match; they are kept so the filter (and its test) stays one rule set.
     */
    internal fun isNoise(entry: DebugLogEntry): Boolean {
        // Drop debug-level lines from these tags entirely.
        if (entry.level == 'D' && entry.tag in NOISE_DEBUG_TAGS) return true
        // Some tags spam at info level too — drop those regardless of level.
        if (entry.tag in NOISE_TAGS_ALL_LEVELS) return true
        // And some arrive under a tag worth keeping — see NOISE_MESSAGES.
        if (NOISE_MESSAGES.any { entry.message.startsWith(it) }) return true
        return false
    }

    /**
     * The console stream with a copy of every line handed to [onLine].
     *
     * The Log shim prints its own lines to `System.err` before handing them to
     * [Log.sink]; those arrive here through `println(String)` in the shim's
     * `L/Tag: message` shape and are passed through without being captured a
     * second time. Bytes go to the original stream untouched, in the original
     * stream's charset, so the console reads exactly as before.
     */
    private class TeePrintStream(
        private val capturing: CapturingStream,
        charset: Charset,
    ) : PrintStream(capturing, true, charset) {

        override fun println(x: String?) {
            if (x != null && LOG_SHIM_LINE.containsMatchIn(x)) {
                capturing.suppressed.set(true)
                try {
                    super.println(x)
                } finally {
                    capturing.suppressed.set(false)
                }
            } else {
                super.println(x)
            }
        }
    }

    private class CapturingStream(
        private val original: PrintStream,
        private val charset: Charset,
        private val onLine: (String) -> Unit,
    ) : OutputStream() {
        val suppressed: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        private val line = ByteArrayOutputStream()

        override fun write(b: Int) {
            original.write(b)
            if (!suppressed.get()) capture(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            original.write(b, off, len)
            if (!suppressed.get()) capture(b, off, len)
        }

        override fun flush() = original.flush()

        // The console is not ours to close.
        override fun close() = original.flush()

        private fun capture(b: ByteArray, off: Int, len: Int) {
            val complete = ArrayList<String>(1)
            synchronized(line) {
                for (i in off until off + len) {
                    val byte = b[i]
                    if (byte == '\n'.code.toByte()) {
                        complete += String(line.toByteArray(), charset).trimEnd('\r')
                        line.reset()
                    } else if (line.size() < MAX_LINE_BYTES) {
                        line.write(byte.toInt())
                    }
                }
            }
            // Outside the lock: the buffer has its own.
            for (text in complete) if (text.isNotEmpty()) onLine(text)
        }
    }

    private companion object {
        /**
         * threadtime format from AOSP's `logcat.cpp`:
         *   `01-02 03:04:05.678  1234  5678 I SomeTag: body`
         */
        private val THREADTIME_REGEX = Regex(
            """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFS])\s+([^:]+):\s?(.*)$"""
        )

        /** The shape the Log shim prints: `W/Tag: message`. */
        private val LOG_SHIM_LINE = Regex("""^[VDIWEF]/[^:\n]+: """)

        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)

        /** One runaway line (a binary blob printed by mistake) must not eat the heap. */
        private const val MAX_LINE_BYTES = 16 * 1024

        private fun levelChar(priority: Int): Char = when (priority) {
            Log.VERBOSE -> 'V'
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            else -> 'F'
        }

        private val NOISE_DEBUG_TAGS = setOf(
            "PipelineWatcher",       // Media3 codec input/output queue depth
            "AidlBufferPool",        // C2 component buffer pool fetch/transfer
            "BufferPoolAccessor2.0", // older C2 buffer pool name
            "C2SoftMP3:DecImpl",     // C2 codec component init logs
        )

        private val NOISE_TAGS_ALL_LEVELS = setOf(
            "ViewRootImplExtImpl",        // OnePlus / OPlus skin: focus + motion event chatter
            "JankManager",                // OPlus skin: per-frame jank stats
            "[JankManager]",
            "DynamicFramerate",           // OPlus skin: refresh-rate adaptation
            "VRR",                        // OPlus skin: variable refresh rate state
            "OplusScrollToTopManager",    // OPlus skin: focus tracking
            "CoreBackPreview",            // System: predictive back gesture
            // Both of these log at ERROR, several times a minute, on OPlus
            // devices. OplusBracketLog is the skin's view-mirroring manager
            // saying it does not handle a plain ViewRootImpl — which is every
            // window that is not in its split-screen bracket, i.e. ours.
            // AudioTrackExtImpl is the vendor's AudioTrack extension reporting
            // that the platform returned no fade type, once per track start.
            "OplusBracketLog",
            "AudioTrackExtImpl",
        )

        /**
         * Noise that arrives under a tag we cannot drop wholesale.
         *
         * Native logging uses the process name as its tag, so this lands on
         * `notrypt.android` — the tail of our own applicationId — alongside
         * linker and ART messages that would matter. Matched on the message
         * instead, by prefix, so only the known line goes.
         *
         * The codec one is Android 15's `getRequiredSystemResources` query
         * against a component that does not implement it. It is logged once per
         * MediaCodec the player creates, and playback is unaffected.
         */
        private val NOISE_MESSAGES = listOf(
            "Failed to query component interface for required system resources",
        )
    }
}
