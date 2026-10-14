package org.example

/** One record from a [Source], along with whatever identifies it (a row number, a map key, etc.) */
data class SourceEntry(val index: Any?, val record: Record)

/**
 * A sequence of records at the start of a migration. Entries are produced lazily, and [close] is called when the
 * migration ends.
 */
interface Source : AutoCloseable {
    fun entries(): Sequence<SourceEntry>

    override fun close() {}
}

fun Sequence<*>.asSource(): Source = object : Source {
    override fun entries() = withIndex().map { (i, item) ->
        SourceEntry(i, Record.of(item ?: throw SourceError("Record $i is null")))
    }
}

fun Iterable<*>.asSource(): Source = asSequence().asSource()

/** A map as a source, using the map keys as record indexes */
fun Map<*, *>.asSource(): Source = object : Source {
    override fun entries() = asSequence().map { (key, item) ->
        SourceEntry(key, Record.of(item ?: throw SourceError("Record $key is null")))
    }
}
