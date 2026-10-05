package tf.monochrome.desktop.dj.controller

import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A Mixxx controller mapping (`.midi.xml`) as a [MidiProfile].
 *
 * Mixxx's community maps hundreds of controllers. Each `<control>` names a
 * Mixxx control, `[Channel1] play`, and the message that drives it; each
 * `<output>` a Mixxx state and the message that lights it. Those this
 * console has are taken over; the rest are counted and left out:
 *
 *  - script bindings (`<script-binding/>`), which are JavaScript: many
 *    mappings do their jog wheels, pads or SHIFT layers that way;
 *  - decks 3 and 4, samplers, and controls the console does not have.
 *
 * Options: `invert`, 14-bit pairs (`fourteen-bit-msb` / `-lsb`), and the
 * relative encodings (`rot64`, `selectknob`, `diff`). Soft takeover is
 * always on here, so `soft-takeover` changes nothing.
 */
object MixxxImport {

    class Result(
        val profile: MidiProfile,
        /** Controls that are JavaScript in Mixxx, and so have no binding here. */
        val scripted: Int,
        /** Controls and outputs with no counterpart on this console. */
        val unsupported: Int,
    )

    class FormatException(message: String) : Exception(message)

    /** Reads a mapping for port [port]; throws [FormatException] for a file that is not one. */
    fun read(input: InputStream, port: String): Result {
        val doc = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                // A mapping is plain XML; nothing it says reaches outside the file.
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(input)
        }.getOrElse { throw FormatException(it.message ?: "not XML") }
        val root = doc.documentElement
        if (root?.tagName != "MixxxControllerPreset" && root?.tagName != "MixxxMIDIPreset") {
            throw FormatException("not a Mixxx controller mapping")
        }
        val name = root.child("info")?.child("name")?.text()?.takeIf { it.isNotBlank() } ?: port
        val controller = root.child("controller") ?: throw FormatException("no <controller>")

        var scripted = 0
        var unsupported = 0

        class Control(val group: String, val key: String, val midi: MidiKey, val options: Set<String>)

        val controls = controller.child("controls")?.children("control").orEmpty().mapNotNull { e ->
            val status = e.child("status")?.text()?.let(::number) ?: return@mapNotNull null
            val midino = e.child("midino")?.text()?.let(::number) ?: 0
            val midi = MidiKey.of(status, midino) ?: return@mapNotNull null.also { unsupported++ }
            val options = e.child("options")?.childElements()?.map { it.tagName.lowercase() }?.toSet().orEmpty()
            Control(e.child("group")?.text().orEmpty(), e.child("key")?.text().orEmpty(), midi, options)
        }

        // A 14-bit control is two <control>s for the same Mixxx control: the LSB's is found here.
        val lsbs = controls.filter { "fourteen-bit-lsb" in it.options }
            .associate { (it.group to it.key) to it.midi.number }

        val bindings = LinkedHashSet<MidiBinding>()
        for (c in controls) {
            if ("script-binding" in c.options) { scripted++; continue }
            if ("fourteen-bit-lsb" in c.options) continue
            val target = target(c.group, c.key)
            if (target == null) { unsupported++; continue }
            val encoding = when {
                "rot64" in c.options || "rot64inv" in c.options || "rot64fast" in c.options -> Relative.OFFSET_64
                "selectknob" in c.options || "diff" in c.options -> Relative.TWOS_COMPLEMENT
                // Mixxx's jog takes 64 as still, its browse knob two's complement.
                target.action == MidiAction.JOG -> Relative.OFFSET_64
                else -> Relative.TWOS_COMPLEMENT
            }
            val lsb = if ("fourteen-bit-msb" in c.options && target.action.kind == MidiAction.Kind.ABSOLUTE) lsbs[c.group to c.key] else null
            val invert = "invert" in c.options || "rot64inv" in c.options
            // Mixxx lists a button's note off beside its note on; here both are the one key.
            bindings += MidiBinding(c.midi, target.action, target.unit, target.slot, invert = invert, encoding = encoding, lsb = lsb)
        }

        val leds = LinkedHashSet<MidiLed>()
        for (e in controller.child("outputs")?.children("output").orEmpty()) {
            val status = e.child("status")?.text()?.let(::number) ?: continue
            val midino = e.child("midino")?.text()?.let(::number) ?: 0
            val midi = MidiKey.of(status, midino)
            val light = light(e.child("group")?.text().orEmpty(), e.child("key")?.text().orEmpty())
            if (midi == null || midi.type == MidiType.PITCH_BEND || light == null) { unsupported++; continue }
            val on = e.child("on")?.text()?.let(::number) ?: 0x7F
            val off = e.child("off")?.text()?.let(::number) ?: 0x00
            leds += MidiLed(midi, light.light, light.unit, light.slot, on.coerceIn(0, 127), off.coerceIn(0, 127))
        }

        return Result(
            MidiProfile(name = name, devices = listOf(port), bindings = bindings.toList(), leds = leds.toList()),
            scripted = scripted,
            unsupported = unsupported,
        )
    }

    // ── Mixxx's controls, as this console's ──────────────────────────

    private val CHANNEL = Regex("""\[Channel([12])]""")
    private val HOTCUE = Regex("""hotcue_([1-8])_(activate|set|clear|enabled|status)""")
    private val EQ = Regex("""\[EqualizerRack1_\[Channel([12])]_Effect1]""")
    private val QUICK_EFFECT = Regex("""\[QuickEffectRack1_\[Channel([12])]]""")
    private val FX_UNIT = Regex("""\[EffectRack1_EffectUnit([12])]""")
    private val FX_EFFECT = Regex("""\[EffectRack1_EffectUnit([12])_Effect([1-3])]""")
    private val GROUP_ENABLE = Regex("""group_\[Channel([12])]_enable""")

    private fun target(group: String, key: String): MidiTarget? {
        CHANNEL.matchEntire(group)?.let { m ->
            val d = m.groupValues[1].toInt() - 1
            HOTCUE.matchEntire(key)?.let { h ->
                val slot = h.groupValues[1].toInt() - 1
                return when (h.groupValues[2]) {
                    "activate", "set" -> MidiTarget(MidiAction.HOT_CUE, d, slot)
                    "clear" -> MidiTarget(MidiAction.CLEAR_HOT_CUE, d, slot)
                    else -> null
                }
            }
            val action = when (key) {
                "play" -> MidiAction.PLAY
                "cue_default" -> MidiAction.CUE
                "start", "start_play" -> MidiAction.START
                "sync_enabled", "beatsync" -> MidiAction.SYNC
                "keylock" -> MidiAction.KEYLOCK
                "quantize" -> MidiAction.QUANTIZE
                "LoadSelectedTrack" -> MidiAction.LOAD
                "eject" -> MidiAction.EJECT
                "loop_in" -> MidiAction.LOOP_IN
                "loop_out" -> MidiAction.LOOP_OUT
                "beatloop_activate" -> MidiAction.LOOP
                "reloop_toggle", "reloop_exit" -> MidiAction.RELOOP
                "loop_halve" -> MidiAction.LOOP_HALVE
                "loop_double" -> MidiAction.LOOP_DOUBLE
                "beatjump_backward" -> MidiAction.JUMP_BACK
                "beatjump_forward" -> MidiAction.JUMP_FORWARD
                "scratch2_enable" -> MidiAction.JOG_TOUCH
                "volume" -> MidiAction.VOLUME
                "rate" -> MidiAction.TEMPO
                "pregain" -> MidiAction.TRIM
                "filterHigh" -> MidiAction.EQ_HIGH
                "filterMid" -> MidiAction.EQ_MID
                "filterLow" -> MidiAction.EQ_LOW
                "jog" -> MidiAction.JOG
                else -> null
            } ?: return null
            return MidiTarget(action, d)
        }
        EQ.matchEntire(group)?.let { m ->
            val d = m.groupValues[1].toInt() - 1
            return when (key) {
                "parameter3" -> MidiTarget(MidiAction.EQ_HIGH, d)
                "parameter2" -> MidiTarget(MidiAction.EQ_MID, d)
                "parameter1" -> MidiTarget(MidiAction.EQ_LOW, d)
                else -> null
            }
        }
        QUICK_EFFECT.matchEntire(group)?.let { m ->
            return if (key == "super1") MidiTarget(MidiAction.FILTER, m.groupValues[1].toInt() - 1) else null
        }
        FX_EFFECT.matchEntire(group)?.let { m ->
            val unit = m.groupValues[1].toInt() - 1
            val slot = m.groupValues[2].toInt() - 1
            return when (key) {
                "enabled" -> MidiTarget(MidiAction.FX_TOGGLE, unit, slot)
                "meta" -> MidiTarget(MidiAction.FX_AMOUNT, unit, slot)
                else -> null
            }
        }
        FX_UNIT.matchEntire(group)?.let { m ->
            val unit = m.groupValues[1].toInt() - 1
            if (key == "mix") return MidiTarget(MidiAction.FX_MIX, unit)
            // Each deck has its own unit here: only a unit's assign to its own deck means anything.
            GROUP_ENABLE.matchEntire(key)?.let { g ->
                return if (g.groupValues[1].toInt() - 1 == unit) MidiTarget(MidiAction.FX_ASSIGN, unit) else null
            }
            return null
        }
        return when {
            group == "[Master]" && key == "crossfader" -> MidiTarget(MidiAction.CROSSFADER)
            group == "[Library]" && key == "MoveVertical" -> MidiTarget(MidiAction.BROWSE)
            group == "[Playlist]" && key == "SelectTrackKnob" -> MidiTarget(MidiAction.BROWSE)
            else -> null
        }
    }

    private class Lit(val light: MidiLight, val unit: Int = 0, val slot: Int = 0)

    private fun light(group: String, key: String): Lit? {
        CHANNEL.matchEntire(group)?.let { m ->
            val d = m.groupValues[1].toInt() - 1
            HOTCUE.matchEntire(key)?.let { h ->
                val kind = h.groupValues[2]
                return if (kind == "enabled" || kind == "status") Lit(MidiLight.HOT_CUE, d, h.groupValues[1].toInt() - 1) else null
            }
            val light = when (key) {
                "play_indicator", "play" -> MidiLight.PLAYING
                "cue_indicator", "cue_default" -> MidiLight.CUE
                "sync_enabled" -> MidiLight.SYNC_LOCK
                "keylock" -> MidiLight.KEYLOCK
                "quantize" -> MidiLight.QUANTIZE
                "loop_enabled" -> MidiLight.LOOP
                "track_loaded" -> MidiLight.LOADED
                "VuMeter", "vu_meter" -> MidiLight.METER
                else -> null
            } ?: return null
            return Lit(light, d)
        }
        FX_EFFECT.matchEntire(group)?.let { m ->
            return if (key == "enabled") Lit(MidiLight.FX_ON, m.groupValues[1].toInt() - 1, m.groupValues[2].toInt() - 1) else null
        }
        FX_UNIT.matchEntire(group)?.let { m ->
            val unit = m.groupValues[1].toInt() - 1
            val g = GROUP_ENABLE.matchEntire(key) ?: return null
            return if (g.groupValues[1].toInt() - 1 == unit) Lit(MidiLight.FX_ASSIGNED, unit) else null
        }
        return null
    }

    // ── DOM ───────────────────────────────────────────────────────────

    /** Mixxx writes numbers in hex (`0x90`) or decimal (`144`). */
    private fun number(s: String): Int? {
        val t = s.trim()
        return if (t.startsWith("0x", ignoreCase = true)) t.substring(2).toIntOrNull(16) else t.toIntOrNull()
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun Element.children(tag: String): List<Element> = childElements().filter { it.tagName == tag }

    private fun Element.child(tag: String): Element? = childElements().firstOrNull { it.tagName == tag }

    private fun Element.text(): String = textContent.trim()
}
