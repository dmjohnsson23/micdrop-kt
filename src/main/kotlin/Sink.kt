package org.example

/** The in-progress output record that `put` fills in */
typealias Collector = MutableMap<Any?, Any?>

/**
 * The final destination of the transformed records.
 *
 * A migration calls [commit] if it finished successfully (including being ended early with `stop()`), or [rollback]
 * if it failed, and always calls [close] afterwards. Sinks which can undo their writes, like a database, should
 * hold their writes in a transaction and commit or roll back accordingly. For all others, both do nothing.
 */
interface Sink : AutoCloseable {
    /** Create a fresh collector. Override to supply a prepopulated or specially-typed map. */
    fun makeCollector(): Collector = LinkedHashMap()

    fun write(collector: Collector)

    /** Called when the migration has finished successfully and everything written should be kept */
    fun commit() {}

    /** Called when the migration has failed because of [cause], and what was written should be undone if possible */
    fun rollback(cause: Throwable) {}

    override fun close() {}
}

/** Use any mutable collection as a sink; each flushed record is added to it */
fun MutableCollection<in Collector>.asSink(): Sink = object : Sink {
    override fun write(collector: Collector) {
        add(collector)
    }
}

/**
 * A combined sink that forwards to multiple named sub-sinks. `putBury` under a sub-sink's name to send a value to it.
 * [commit], [rollback] and [close] are forwarded to every sub-sink.
 */
class MultiSink(
    private val sinks: Map<Any?, Sink>,
    private val ignoreEmptySubs: Boolean = true,
) : Sink {
    constructor(vararg sinks: Pair<Any?, Sink>) : this(linkedMapOf(*sinks))

    override fun makeCollector(): Collector =
        sinks.mapValuesTo(LinkedHashMap<Any?, Any?>()) { it.value.makeCollector() }

    @Suppress("UNCHECKED_CAST")
    override fun write(collector: Collector) {
        for ((key, sink) in sinks) {
            val sub = collector[key] as? Collector ?: continue
            if (ignoreEmptySubs && sub.isEmpty()) continue
            sink.write(sub)
        }
    }

    override fun commit() {
        sinks.values.forEach { it.commit() }
    }

    override fun rollback(cause: Throwable) {
        // Every sub-sink gets its chance to roll back, even if an earlier one fails
        var failure: Throwable? = null
        for (sink in sinks.values) {
            try {
                sink.rollback(cause)
            } catch (e: Throwable) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        failure?.let { throw it }
    }

    override fun close() {
        var failure: Throwable? = null
        for (sink in sinks.values) {
            try {
                sink.close()
            } catch (e: Throwable) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        failure?.let { throw it }
    }
}
