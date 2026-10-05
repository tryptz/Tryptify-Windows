package tf.monochrome.desktop.audio.dsp.model

import kotlinx.serialization.Serializable

@Serializable
data class BusConfig(
    val index: Int,
    val name: String,
    val gainDb: Float = 0f,
    val pan: Float = 0f,
    val muted: Boolean = false,
    val soloed: Boolean = false,
    val inputEnabled: Boolean = false,
    /**
     * Desktop: which input the bus hears while [inputEnabled]: the player
     * ([INPUT_PLAYER], deck A while the DJ console runs) or deck B
     * ([INPUT_SIDE]), which is silent outside the console.
     */
    val inputSource: Int = INPUT_PLAYER,
    val plugins: List<PluginInstance> = emptyList(),
    /**
     * The channel group of a multichannel stream this bus carries right now
     * ("Centre", "Top Front"…), or null. Set only while such a stream plays;
     * never saved.
     */
    val channelGroup: String? = null,
    /**
     * Where this bus's post-fader signal goes: destination bus index
     * ([MASTER_INDEX] for the master) to linear send level 0..1, mirroring the
     * engine's routing. A mix bus goes to the master alone until routed
     * elsewhere; the master sends nowhere.
     */
    val sends: Map<Int, Float> = if (index == MASTER_INDEX) emptyMap() else DEFAULT_SENDS,
) {
    /** Routed anywhere other than the master alone. */
    val hasCustomSends: Boolean get() = !isMaster && sends != DEFAULT_SENDS

    val isMaster: Boolean get() = index == MASTER_INDEX

    /**
     * Buses 1–4 and the master stay; bus 5 and up can be removed, except
     * while a channel group is routed to it.
     */
    val isRemovable: Boolean get() = index > MASTER_INDEX && channelGroup == null

    /** The number on the strip: 1–48, whatever the index behind it. */
    val number: Int get() = numberFor(index)

    companion object {
        /**
         * The master's index, fixed since the first build: every saved mix and
         * preset has it fifth. Mix buses 1–4 are indices 0–3 and buses added
         * later take 5, 6, … — so the list stays in index order while the
         * screen shows the master last (see [displayOrder]).
         */
        const val MASTER_INDEX = 4
        const val MIN_MIX_BUSES = 4
        const val MAX_MIX_BUSES = 48

        /** A mix bus's routing until it is changed: the master, at unity. */
        val DEFAULT_SENDS: Map<Int, Float> = mapOf(MASTER_INDEX to 1f)

        /** Every bus a mix can hold, master included. */
        const val MAX_TOTAL_BUSES = MAX_MIX_BUSES + 1

        fun numberFor(index: Int): Int = if (index < MASTER_INDEX) index + 1 else index

        /** Bus input sources, as the engine numbers them. */
        const val INPUT_PLAYER = 0
        const val INPUT_SIDE = 1

        /**
         * Desktop: the four fixed buses are a two-deck DJ console. Each deck
         * has a channel ("Mix") that feeds its own effects bus ("FX"), and
         * both FX buses go to the master.
         */
        const val MIX_A = 0
        const val FX_A = 1
        const val MIX_B = 2
        const val FX_B = 3
        private val CONSOLE_NAMES = listOf("Mix A", "FX A", "Mix B", "FX B")

        fun nameFor(index: Int): String = when {
            index == MASTER_INDEX -> "Master"
            index < MASTER_INDEX -> CONSOLE_NAMES[index]
            else -> "Bus ${numberFor(index)}"
        }

        /** Mix buses in number order, then the master. */
        fun displayOrder(buses: List<BusConfig>): List<BusConfig> =
            buses.filter { !it.isMaster }.sortedBy { it.index } + buses.filter { it.isMaster }

        fun mixBusCount(buses: List<BusConfig>): Int = buses.count { !it.isMaster }

        /**
         * The console's routing for bus [index] 0–3: input on the Mix buses
         * (A from the player, B from deck B), each Mix into its FX bus, the FX
         * buses into the master. Null for any other bus.
         */
        fun consoleRouting(index: Int): ConsoleRouting? = when (index) {
            MIX_A -> ConsoleRouting(inputEnabled = true, inputSource = INPUT_PLAYER, sends = mapOf(FX_A to 1f))
            FX_A -> ConsoleRouting(inputEnabled = false, inputSource = INPUT_PLAYER, sends = DEFAULT_SENDS)
            MIX_B -> ConsoleRouting(inputEnabled = true, inputSource = INPUT_SIDE, sends = mapOf(FX_B to 1f))
            FX_B -> ConsoleRouting(inputEnabled = false, inputSource = INPUT_PLAYER, sends = DEFAULT_SENDS)
            else -> null
        }

        /** Whether some bus in [buses] hears deck B and reaches the master unmuted. */
        fun hearsDeckB(buses: List<BusConfig>): Boolean = buses.any {
            it.inputEnabled && it.inputSource == INPUT_SIDE && !it.muted && it.sends.values.any { lv -> lv > 0f }
        }

        fun defaultBuses(): List<BusConfig> = (0 until MIN_MIX_BUSES).map { index ->
            val routing = consoleRouting(index)!!
            BusConfig(
                index = index,
                name = nameFor(index),
                inputEnabled = routing.inputEnabled,
                inputSource = routing.inputSource,
                sends = routing.sends,
            )
        } + BusConfig(index = MASTER_INDEX, name = nameFor(MASTER_INDEX))
    }

    /** One bus's place in the console layout ([consoleRouting]). */
    data class ConsoleRouting(val inputEnabled: Boolean, val inputSource: Int, val sends: Map<Int, Float>)
}
