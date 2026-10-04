// Desktop stand-in for android.os.Bundle: a typed key/value map. Only used by
// the ported code to carry MediaMetadata extras (PlayedFrom) and saved state.
package android.os

class Bundle() {
    private val map = LinkedHashMap<String, Any?>()

    constructor(other: Bundle) : this() { map.putAll(other.map) }

    fun putString(key: String, value: String?) { map[key] = value }
    fun getString(key: String): String? = map[key] as? String
    fun getString(key: String, default: String): String = (map[key] as? String) ?: default
    fun putLong(key: String, value: Long) { map[key] = value }
    fun getLong(key: String, default: Long = 0L): Long = (map[key] as? Long) ?: default
    fun putInt(key: String, value: Int) { map[key] = value }
    fun getInt(key: String, default: Int = 0): Int = (map[key] as? Int) ?: default
    fun putBoolean(key: String, value: Boolean) { map[key] = value }
    fun getBoolean(key: String, default: Boolean = false): Boolean = (map[key] as? Boolean) ?: default
    fun putFloat(key: String, value: Float) { map[key] = value }
    fun getFloat(key: String, default: Float = 0f): Float = (map[key] as? Float) ?: default
    fun putDouble(key: String, value: Double) { map[key] = value }
    fun getDouble(key: String, default: Double = 0.0): Double = (map[key] as? Double) ?: default
    fun putStringArray(key: String, value: Array<String>?) { map[key] = value }
    @Suppress("UNCHECKED_CAST")
    fun getStringArray(key: String): Array<String>? = map[key] as? Array<String>
    fun putBundle(key: String, value: Bundle?) { map[key] = value }
    fun getBundle(key: String): Bundle? = map[key] as? Bundle
    fun putAll(other: Bundle) { map.putAll(other.map) }
    fun containsKey(key: String): Boolean = map.containsKey(key)
    fun remove(key: String) { map.remove(key) }
    fun keySet(): Set<String> = map.keys
    fun isEmpty(): Boolean = map.isEmpty()
    fun size(): Int = map.size
    operator fun get(key: String): Any? = map[key]
    override fun toString(): String = "Bundle$map"

    companion object {
        @JvmField val EMPTY = Bundle()
    }
}

fun bundleOf(vararg pairs: Pair<String, Any?>): Bundle = Bundle().apply {
    for ((k, v) in pairs) when (v) {
        null -> putString(k, null)
        is String -> putString(k, v)
        is Long -> putLong(k, v)
        is Int -> putInt(k, v)
        is Boolean -> putBoolean(k, v)
        is Float -> putFloat(k, v)
        is Double -> putDouble(k, v)
        is Bundle -> putBundle(k, v)
        else -> putString(k, v.toString())
    }
}
