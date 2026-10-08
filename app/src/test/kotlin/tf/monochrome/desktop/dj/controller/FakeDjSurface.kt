package tf.monochrome.desktop.dj.controller

import java.util.Collections
import tf.monochrome.desktop.dj.Deck

/** A console with two real decks and the rest recorded. */
internal class FakeDjSurface : DjSurface {
    override val decks = arrayOf(Deck(0), Deck(1))
    override var crossfader = 0f
    /** Synchronized: the controller tests read it while the reader thread writes it. */
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val on = Array(2) { BooleanArray(3) }
    private val mix = FloatArray(2) { 1f }
    private val amounts = Array(2) { FloatArray(3) { 0.5f } }
    private val assigned = BooleanArray(2) { true }

    override fun setSyncLock(deck: Int, on: Boolean) {
        decks[deck].syncLock = on
        calls += "lock $deck $on"
    }
    override fun browse(steps: Int) { calls += "browse $steps" }
    override fun loadSelected(deck: Int): Boolean { calls += "load $deck"; return true }
    override fun toggleFx(unit: Int, slot: Int) { on[unit][slot] = !on[unit][slot] }
    override fun fxOn(unit: Int, slot: Int) = on[unit][slot]
    override fun fxMix(unit: Int) = mix[unit]
    override fun setFxMix(unit: Int, value: Float) { mix[unit] = value }
    override fun fxAmount(unit: Int, slot: Int) = amounts[unit][slot]
    override fun setFxAmount(unit: Int, slot: Int, value: Float) { amounts[unit][slot] = value }
    override fun fxAssigned(unit: Int) = assigned[unit]
    override fun toggleFxAssign(unit: Int) { assigned[unit] = !assigned[unit] }
}
