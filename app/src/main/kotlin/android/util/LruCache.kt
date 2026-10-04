// Desktop stand-in for android.util.LruCache: a size-bounded LinkedHashMap.
package android.util

open class LruCache<K : Any, V : Any>(private val maxSize: Int) {
    init { require(maxSize > 0) { "maxSize <= 0" } }

    private val map = object : LinkedHashMap<K, V>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
            val remove = size > this@LruCache.maxSize
            if (remove) entryRemoved(true, eldest.key, eldest.value, null)
            return remove
        }
    }

    @Synchronized
    operator fun get(key: K): V? {
        map[key]?.let { return it }
        val created = create(key) ?: return null
        map[key] = created
        return created
    }

    @Synchronized
    fun put(key: K, value: V): V? {
        val previous = map.put(key, value)
        if (previous != null) entryRemoved(false, key, previous, value)
        return previous
    }

    @Synchronized
    fun remove(key: K): V? = map.remove(key)?.also { entryRemoved(false, key, it, null) }

    @Synchronized
    fun evictAll() { map.clear() }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun snapshot(): Map<K, V> = LinkedHashMap(map)

    protected open fun create(key: K): V? = null
    protected open fun entryRemoved(evicted: Boolean, key: K, oldValue: V, newValue: V?) {}
    protected open fun sizeOf(key: K, value: V): Int = 1
}
