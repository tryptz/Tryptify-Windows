package tf.monochrome.desktop.ui.input

/**
 * Where Forward goes: the places Back left, most recent last.
 *
 * A phone has Back and nothing else, so the Android app kept no record of what
 * Back closed. A desktop also has Forward (Alt+Right, the mouse's Forward
 * button), and it means what it means in a browser: undo the last Back. So each
 * Back hands over what it left, Forward reopens it, and going anywhere new
 * forgets the lot, because the trail it belonged to has been abandoned.
 *
 * Plain state with no Compose and no NavController in it, so these rules can be
 * tested on their own. The nav host feeds it.
 */
class ForwardHistory<T : Any> {
    // The next Forward is the last element.
    private val ahead = ArrayDeque<T>()

    // An arrival that is not a new navigation: where the last Forward went, or
    // where a Back lands when that landing is reported separately (a pager page,
    // whose change shows up after the Back that caused it).
    private var expected: T? = null

    val canGoForward: Boolean get() = ahead.isNotEmpty()

    /**
     * Back closed [left], given in the order they had been opened, so Forward
     * reopens them in that order too. [landing] is where this Back takes the
     * app, when that arrival will also be reported through [arrived].
     */
    fun wentBack(left: List<T>, landing: T? = null) {
        for (place in left.asReversed()) ahead.addLast(place)
        expected = landing
    }

    /** Takes the next Forward off the history, or null when there is none. */
    fun forward(): T? {
        val next = ahead.removeLastOrNull() ?: return null
        expected = next
        return next
    }

    /**
     * The app arrived at [at]. Arriving where Forward was sent keeps the rest of
     * the history, and so does opening the next forward place by hand, which is
     * the same step. Anywhere else starts a new trail.
     */
    fun arrived(at: T) {
        when (at) {
            expected -> expected = null
            ahead.lastOrNull() -> {
                ahead.removeLast()
                expected = null
            }
            else -> clear()
        }
    }

    /** Applies a change [BackStackMirror] worked out. */
    fun follow(move: StackMove<T>) {
        when (move) {
            is StackMove.Popped -> {
                val left = move.left.filterNotNull()
                // A screen that cannot be named cannot be reopened, and skipping
                // it would make Forward jump past a step.
                if (left.size == move.left.size) wentBack(left) else clear()
            }
            is StackMove.Pushed -> if (move.place != null) arrived(move.place) else clear()
            StackMove.Unchanged -> Unit
        }
    }

    fun clear() {
        ahead.clear()
        expected = null
    }
}

/** What happened to a back stack between two looks at it. */
sealed interface StackMove<out T : Any> {
    /** Entries came off the top. [left] holds what they were, lowest first; null where one could not be named. */
    data class Popped<out T : Any>(val left: List<T?>) : StackMove<T>

    /** A new entry went on top, possibly after others came off. */
    data class Pushed<out T : Any>(val place: T?) : StackMove<T>

    data object Unchanged : StackMove<Nothing>
}

/**
 * The navigation back stack as far as it has been seen, built from public state
 * only: the entry on top and the one under it, read every time the destination
 * changes. That is enough to tell a Back (the new top was already on the stack)
 * from a new screen (it was not), and the entry under a new screen says how
 * much a `popUpTo` took away along with it.
 */
class BackStackMirror<T : Any> {
    private val ids = ArrayList<String>()
    private val places = ArrayList<T?>()

    /**
     * The stack now has the entry [topId] on top, standing for [top], with the
     * entry [belowId] under it.
     */
    fun moved(topId: String, top: T?, belowId: String?): StackMove<T> {
        val at = ids.lastIndexOf(topId)
        if (at >= 0) {
            if (at < ids.lastIndex) {
                val left = places.subList(at + 1, places.size).toList()
                truncate(at + 1)
                return StackMove.Popped(left)
            }
            if (places[at] == top) return StackMove.Unchanged
            // The same entry with new arguments: somewhere new all the same.
            places[at] = top
            return StackMove.Pushed(top)
        }
        val under = if (belowId == null) -1 else ids.lastIndexOf(belowId)
        truncate(under + 1)
        ids += topId
        places += top
        return StackMove.Pushed(top)
    }

    private fun truncate(size: Int) {
        ids.subList(size, ids.size).clear()
        places.subList(size, places.size).clear()
    }
}

private val PLACEHOLDER = Regex("""\{([^{}]+)\}""")

/**
 * A route pattern as a destination declares it, `artist/{artistId}?name={name}`,
 * with each `{name}` filled from [values]. The values must already be encoded
 * the way the route expects, as `NavType.serializeAsValue` gives them.
 *
 * A query parameter without a value is left out, which gives it its default
 * again. A path placeholder without one cannot be left out, and an empty one
 * would leave an empty segment that no destination matches, so either way the
 * route cannot be built and the answer is null.
 */
fun fillRoutePattern(pattern: String, values: Map<String, String>): String? {
    val queryAt = pattern.indexOf('?')
    val path = if (queryAt < 0) pattern else pattern.substring(0, queryAt)
    if (PLACEHOLDER.findAll(path).any { values[it.groupValues[1]].isNullOrEmpty() }) return null
    val filledPath = PLACEHOLDER.replace(path) { values.getValue(it.groupValues[1]) }
    if (queryAt < 0) return filledPath
    val params = pattern.substring(queryAt + 1).split('&')
        .filter { param -> param.isNotEmpty() && PLACEHOLDER.findAll(param).all { it.groupValues[1] in values } }
        .map { param -> PLACEHOLDER.replace(param) { values.getValue(it.groupValues[1]) } }
    return if (params.isEmpty()) filledPath else filledPath + "?" + params.joinToString("&")
}
