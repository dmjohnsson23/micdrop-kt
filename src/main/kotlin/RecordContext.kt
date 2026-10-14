package org.example

/** The output half of a record context: everything that deals with the collector and the sink. */
interface Output {
    fun put(name: Any, value: Any?)

    /** Put a value into nested structure, creating it as needed. See [bury]. */
    fun putBury(keys: List<Any?>, value: Any?)

    /** Write everything put so far to the sink, optionally clearing the collector afterwards */
    fun flush(reset: Boolean = true)

    /** Clear everything put so far */
    fun reset()

    /** Debug tool to print the current collector to the console */
    fun dumpCollector(prefix: String? = null)

    /** Run [hook] just before each flush. Must be registered before the first flush to see it. */
    fun beforeFlush(hook: FlushHook)

    /** Run [hook] just after each flush. Must be registered before the first flush to see it. */
    fun afterFlush(hook: FlushHook)
}

typealias FlushHook = (RootRecordContext, Collector) -> Unit

/**
 * Common functionality for all record contexts. A record context wraps a [Record] from which items can be taken.
 *
 * Every `take`-like function runs the optional [block] as a pipeline and returns the resulting [ItemContext], so
 * they can be used either with a block or via method chaining.
 */
@MicdropDsl
abstract class RecordContext(val record: Record?) : Output {
    abstract val root: RootRecordContext

    private fun item(value: Any?, block: Pipeline): ItemContext = ItemContext(this, value).also { block(it) }

    /** Take a single item from the record. The item must exist, but its value may be null. */
    fun take(name: Any, block: Pipeline = {}) = item(record?.fetch(name), block)

    /** Take an item from nested records. Every key along the way must exist. */
    fun takeDig(vararg names: Any, block: Pipeline = {}) = item(record.dig(names, strict = true), block)

    /** Take an item if it exists, or null otherwise */
    fun tryTake(name: Any, block: Pipeline = {}) = item(record?.get(name), block)

    /** Take the entire record as a single item */
    fun takeWhole(block: Pipeline = {}) = item(record?.raw, block)

    /** Alias for `takeWhole().eachSubrecord(...)` */
    fun eachSubrecord(flush: Boolean = false, reset: Boolean = false, block: SubRecordContext.() -> Unit) =
        takeWhole().eachSubrecord(flush, reset, block)

    /** Take each named item and put it under the same name */
    fun passthru(vararg names: Any) {
        for (name in names) take(name).put(name)
    }

    /** Like `take`, but with a value you supply rather than one from the record */
    fun static(value: Any?, block: Pipeline = {}) = item(value, block)

    /** Take the record's index (row number, map key, ...) rather than a value from the record */
    fun index(block: Pipeline = {}) = item(root.loopIndex, block)

    /** Collect the values of several already-taken items into a single list */
    fun collectList(vararg items: ItemContext, block: Pipeline = {}) = item(items.map { it.value }, block)

    /** Collect the values of several already-taken items into a single map */
    fun collectKv(items: Map<out Any?, ItemContext>, block: Pipeline = {}) =
        item(items.mapValuesTo(LinkedHashMap<Any?, Any?>()) { it.value.value }, block)

    fun collectKv(vararg items: Pair<Any?, ItemContext>, block: Pipeline = {}) = collectKv(items.toMap(), block)

    /** Collect the values of several already-taken items into a string, using [String.format] */
    fun collectFormatString(template: String, vararg items: ItemContext, block: Pipeline = {}) =
        item(template.format(*items.map { it.value }.toTypedArray()), block)

    /** Skip the current source record. Like `continue`. */
    fun skip(): Nothing = throw Skip()

    /** Stop processing the source. Like `break`. */
    fun stop(): Nothing = throw Stop()
}
