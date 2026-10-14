package org.example

/**
 * Inverse of `dig`: store [value] at [keys] inside [root], creating intermediate structure as needed.
 *
 * A null key appends to a list. An Int key indexes a list. Anything else is a map key. New intermediate containers
 * are lists if the following key is null or an Int, and maps otherwise.
 */
@Suppress("UNCHECKED_CAST")
internal fun bury(root: Collector, keys: List<Any?>, value: Any?) {
    if (keys.isEmpty()) throw PipelineError("put_bury requires at least one key")
    var container: Any = root
    for (i in 0 until keys.lastIndex) {
        val key = keys[i]
        val nextKey = keys[i + 1]
        var child = getChild(container, key)
        if (child == null) {
            child = if (nextKey == null || nextKey is Int) mutableListOf<Any?>() else linkedMapOf<Any?, Any?>()
            setChild(container, key, child)
        }
        container = child
    }
    setChild(container, keys.last(), value)
}

/** Negative list indexes count from the end */
private fun resolveIndex(list: List<*>, index: Int) = if (index < 0) list.size + index else index

private fun getChild(container: Any, key: Any?): Any? = when (container) {
    is Map<*, *> -> container[key]
    is List<*> -> if (key is Int) container.getOrNull(resolveIndex(container, key)) else null
    else -> throw PipelineError("Cannot bury into ${container::class.qualifiedName}")
}

@Suppress("UNCHECKED_CAST")
private fun setChild(container: Any, key: Any?, value: Any?) {
    when (container) {
        is MutableMap<*, *> -> {
            if (key == null) throw PipelineError("Cannot append to a map; give a key")
            (container as MutableMap<Any?, Any?>)[key] = value
        }
        is MutableList<*> -> {
            val list = container as MutableList<Any?>
            when (key) {
                null -> list.add(value)
                is Int -> {
                    val index = resolveIndex(list, key)
                    if (index < 0) throw PipelineError("List index $key is out of range")
                    while (list.size <= index) list.add(null)
                    list[index] = value
                }
                else -> throw PipelineError("Cannot use '$key' as a list index")
            }
        }
        else -> throw PipelineError("Cannot bury into ${container::class.qualifiedName}")
    }
}
