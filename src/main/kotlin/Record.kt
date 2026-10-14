package org.example

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A uniform view over anything that has items which can be taken by name or position: maps, lists, regex matches,
 * and (via [RecordAdapter]s registered by extensions) things like JSON nodes or XML elements.
 */
interface Record {
    /** The underlying object this record wraps */
    val raw: Any

    operator fun contains(key: Any): Boolean

    /** Get the item for [key], or null if there isn't one */
    operator fun get(key: Any): Any?

    /** Get the item for [key], failing if there isn't one. (A present item may still be null.) */
    fun fetch(key: Any): Any? =
        if (key in this) get(key) else throw PipelineError("Key '$key' not found in record")

    companion object {
        private val adapters = CopyOnWriteArrayList<RecordAdapter>(
            listOf(RecordAdapter { (it as? Record) ?: defaultAdapt(it) })
        )

        /** Register support for another record type. Later registrations take precedence. */
        fun registerAdapter(adapter: RecordAdapter) {
            adapters.add(0, adapter)
        }

        /** Wrap [value] as a record, or return null if no adapter supports it */
        fun adapt(value: Any): Record? = adapters.firstNotNullOfOrNull { it.adapt(value) }

        /** Wrap [value] as a record, failing if no adapter supports it */
        fun of(value: Any): Record =
            adapt(value) ?: throw PipelineError("Cannot treat ${value::class.qualifiedName} as a record")

        private fun defaultAdapt(value: Any): Record? = when (value) {
            is Map<*, *> -> MapRecord(value)
            is List<*> -> ListRecord(value)
            is MatchResult -> MatchRecord(value)
            else -> null
        }
    }
}

fun interface RecordAdapter {
    /** Return a [Record] wrapping [value], or null if this adapter doesn't handle it */
    fun adapt(value: Any): Record?
}

class MapRecord(override val raw: Map<*, *>) : Record {
    @Suppress("UNCHECKED_CAST")
    private val map get() = raw as Map<Any, Any?>

    override fun contains(key: Any) = map.containsKey(key)
    override fun get(key: Any) = map[key]
}

class ListRecord(override val raw: List<*>) : Record {
    override fun contains(key: Any) = key is Int && key in raw.indices
    override fun get(key: Any) = if (key in this) raw[key as Int] else null
}

/** Group 0 is the whole match; groups can be taken by index or by name */
class MatchRecord(override val raw: MatchResult) : Record {
    override fun contains(key: Any) = when (key) {
        is Int -> key in raw.groups.indices
        is String -> runCatching { raw.groups[key] }.isSuccess
        else -> false
    }

    override fun get(key: Any): String? = when (key) {
        is Int -> raw.groups[key]?.value
        is String -> runCatching { raw.groups[key]?.value }.getOrNull()
        else -> null
    }
}

/**
 * Follow [keys] down through nested records. A null on the way down yields null. When [strict], a missing key is an
 * error; otherwise it is treated as null.
 */
internal fun Record?.dig(keys: Array<out Any>, strict: Boolean): Any? {
    var record = this
    var current: Any? = null
    for ((i, key) in keys.withIndex()) {
        if (record == null) return null
        current = if (strict) record.fetch(key) else record[key]
        if (i < keys.lastIndex) record = current?.let { Record.of(it) }
    }
    return current
}
