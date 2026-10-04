package tf.monochrome.desktop.visualizer.gl

/**
 * Hands finished frames from the render thread to the UI thread so that
 * neither waits on the other and the UI never sees a frame being written.
 *
 * Three roles, one slot each at most: the frame the UI is showing ([acquire]'s
 * result), the newest finished frame it has not picked up yet, and the one the
 * render thread is filling. The render thread takes a spare slot, writes it
 * with no lock held, and [publish]es it; a frame published before the UI got
 * to the previous one replaces it, and the unseen one becomes the next spare.
 * The UI's [acquire] swaps in the newest frame and gives up the one it was
 * showing — which is safe to overwrite from then on, because Skia copies a
 * mutable bitmap's pixels at the moment it is drawn.
 *
 * Slots are created by the producer only when no spare is left, so there are
 * never more than three. [close] ends the exchange: from then on a publish is
 * refused and the producer disposes of the slot it was holding itself.
 */
internal class TripleBuffer<T : Any> {
    private val lock = Any()
    private var front: T? = null
    private var ready: T? = null
    private val spares = ArrayDeque<T>()
    private var closed = false

    /** Producer: a slot nobody else holds, or null when one has to be made (or the exchange is closed). */
    fun takeSpare(): T? = synchronized(lock) {
        if (closed) null else spares.removeFirstOrNull()
    }

    /** Producer: whether the exchange still takes frames. */
    val isClosed: Boolean get() = synchronized(lock) { closed }

    /**
     * Producer: offers [slot] as the newest frame. False when the exchange is
     * closed, in which case [slot] is still the caller's to dispose of.
     */
    fun publish(slot: T): Boolean = synchronized(lock) {
        if (closed) return false
        ready?.let { spares.addLast(it) }
        ready = slot
        true
    }

    /** Producer: gives back a slot it took but did not publish. False when closed. */
    fun giveBack(slot: T): Boolean = synchronized(lock) {
        if (closed) return false
        spares.addLast(slot)
        true
    }

    /** Consumer: the newest frame, swapping it in if one has arrived since the last call. */
    fun acquire(): T? = synchronized(lock) {
        val next = ready
        if (next != null) {
            front?.let { spares.addLast(it) }
            front = next
            ready = null
        }
        front
    }

    /** Consumer: closes the exchange and returns every slot it held, for disposal. */
    fun close(): List<T> = synchronized(lock) {
        closed = true
        val held = ArrayList<T>(spares.size + 2)
        front?.let { held += it }
        ready?.let { held += it }
        held += spares
        front = null
        ready = null
        spares.clear()
        held
    }
}
