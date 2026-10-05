package tf.monochrome.desktop.platform

import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.MidiUnavailableException
import javax.sound.midi.Receiver
import javax.sound.midi.Sequencer
import javax.sound.midi.ShortMessage
import javax.sound.midi.Synthesizer
import javax.sound.midi.Transmitter
import tf.monochrome.desktop.dj.controller.MidiBus
import tf.monochrome.desktop.dj.controller.MidiConnection
import tf.monochrome.desktop.dj.controller.MidiOpenException

/**
 * USB MIDI through the JDK's javax.sound.midi: WinMM on Windows, ALSA on
 * Linux. No library and no driver: class-compliant controllers (nearly all)
 * appear as soon as Windows has them.
 *
 * Windows lists a controller twice, an input and an output of one name; the
 * input is the port, and the output of the same name lights its LEDs. The
 * list is read afresh each time ([MidiSystem] rereads it when the count
 * changes), so a controller plugged in later appears.
 *
 * A WinMM input opens for one program only: a port in use elsewhere throws
 * "already allocated" or "in use", which [open] reports as busy.
 */
object JavaMidiBus : MidiBus {

    override fun inputs(): List<String> = ports(input = true).map { it.first }

    override fun open(name: String, receive: (status: Int, data1: Int, data2: Int) -> Unit): MidiConnection {
        val input = ports(input = true).firstOrNull { it.first == name }?.second
            ?: throw MidiOpenException(busy = false, "$name is gone")
        val transmitter: Transmitter
        try {
            input.open()
            transmitter = input.transmitter
        } catch (e: MidiUnavailableException) {
            runCatching { input.close() }
            throw MidiOpenException(busy(e), e.message ?: "unavailable")
        }
        transmitter.receiver = object : Receiver {
            override fun send(message: MidiMessage, timeStamp: Long) {
                // Channel messages only: no clock, no SysEx.
                if (message is ShortMessage && message.status in 0x80..0xEF) receive(message.status, message.data1, message.data2)
            }

            override fun close() = Unit
        }
        // The LEDs, where there is an output to light them; the controls work without.
        val output = ports(input = false).firstOrNull { it.first == name }?.second?.let { device ->
            try {
                device.open()
                device to device.receiver
            } catch (e: MidiUnavailableException) {
                runCatching { device.close() }
                null
            }
        }
        return Connection(input, transmitter, output?.first, output?.second)
    }

    private class Connection(
        private val input: MidiDevice,
        private val transmitter: Transmitter,
        private val output: MidiDevice?,
        private val receiver: Receiver?,
    ) : MidiConnection {
        override val hasOutput: Boolean get() = receiver != null

        override fun send(status: Int, data1: Int, data2: Int): Boolean {
            val r = receiver ?: return false
            return try {
                r.send(ShortMessage(status, data1, data2), -1)
                true
            } catch (e: Exception) {
                // An invalid message, or a device unplugged under it.
                false
            }
        }

        override fun close() {
            runCatching { transmitter.close() }
            runCatching { input.close() }
            runCatching { receiver?.close() }
            runCatching { output?.close() }
        }
    }

    /**
     * The hardware ports one way, each named as Windows names it, with " (2)"
     * and on added where two have one name. Java's own synthesizer and
     * sequencer are not ports.
     */
    private fun ports(input: Boolean): List<Pair<String, MidiDevice>> {
        val devices = MidiSystem.getMidiDeviceInfo().mapNotNull { info ->
            val device = runCatching { MidiSystem.getMidiDevice(info) }.getOrNull() ?: return@mapNotNull null
            if (device is Sequencer || device is Synthesizer) return@mapNotNull null
            val ends = if (input) device.maxTransmitters else device.maxReceivers
            if (ends == 0) null else info.name to device
        }
        val seen = HashMap<String, Int>()
        return devices.map { (name, device) ->
            val n = seen.merge(name, 1, Int::plus)!!
            (if (n == 1) name else "$name ($n)") to device
        }
    }

    private fun busy(e: MidiUnavailableException): Boolean {
        val m = e.message?.lowercase() ?: return false
        return "in use" in m || "alloc" in m
    }
}
