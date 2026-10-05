package tf.monochrome.desktop.dj.controller

import tf.monochrome.desktop.dj.Deck

/**
 * What a hardware controller plays: the console's two decks, its
 * crossfader, the track browser and the two FX units. [tf.monochrome.desktop.dj.DjEngine]
 * is the real one; a test hands a mapping two bare [Deck]s and records the
 * rest.
 *
 * Every call is safe from a controller's reader thread: a deck queues its
 * buttons for the audio thread, and the engine's own state is in atomic
 * flows.
 */
interface DjSurface {
    val decks: Array<Deck>

    /** -1 (all A) .. 1 (all B). */
    var crossfader: Float

    fun setSyncLock(deck: Int, on: Boolean)

    /** Moves the browser's selection [steps] tracks down. */
    fun browse(steps: Int)

    /** Loads the browser's selection on [deck]; false while that deck plays. */
    fun loadSelected(deck: Int): Boolean

    fun toggleFx(unit: Int, slot: Int)
    fun fxOn(unit: Int, slot: Int): Boolean
    fun fxMix(unit: Int): Float
    fun setFxMix(unit: Int, value: Float)
    fun fxAmount(unit: Int, slot: Int): Float
    fun setFxAmount(unit: Int, slot: Int, value: Float)

    /** Whether unit [unit] is heard on its deck: off, its effects all sound dry. */
    fun fxAssigned(unit: Int): Boolean
    fun toggleFxAssign(unit: Int)
}
