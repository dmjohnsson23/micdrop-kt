package org.example

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.concurrent.ConcurrentHashMap

/**
 * A single value moving through a pipeline. Transforms modify [value] in place and return `this` so they can be
 * chained. Unless noted otherwise, transforms leave a null value untouched.
 */
@MicdropDsl
class ItemContext(val recordContext: RecordContext, var value: Any?) {
    val originalValue: Any? = value

    companion object {
        private val lookups = ConcurrentHashMap<String, Map<*, *>>()

        /** SQL-style, as opposed to ISO's `T` separator */
        private val DEFAULT_DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private val DEFAULT_TRUE_VALUES: Set<Any> =
            setOf(1, "1", "true", "True", "TRUE", "yes", "Yes", "YES", "on", "On", "ON", "Y", "y")
        private val DEFAULT_FALSE_VALUES: Set<Any> =
            setOf(0, "0", "false", "False", "FALSE", "no", "No", "NO", "off", "Off", "OFF", "N", "n", "")

        /** Register a lookup, allowing it to be used by name in subsequent migrations */
        fun registerLookup(name: String, lookup: Map<*, *>) {
            lookups[name] = lookup
        }

        private fun isEmpty(value: Any?) = when (value) {
            is CharSequence -> value.isEmpty()
            is Collection<*> -> value.isEmpty()
            is Map<*, *> -> value.isEmpty()
            else -> false
        }
    }

    /**
     * Apply [f] to a non-null value, storing the result; null stays null. This is the building block for most
     * transforms, and is public so extension functions can define their own the same way.
     */
    inline fun transform(f: (Any) -> Any?): ItemContext {
        value?.let { value = f(it) }
        return this
    }

    private fun iterable(value: Any): Iterable<*> = when (value) {
        is Iterable<*> -> value
        is Array<*> -> value.asIterable()
        is Sequence<*> -> value.asIterable()
        else -> throw ValueError("Expected a list, got ${value::class.qualifiedName}")
    }

    // region Pipeline control

    /** Directly update the current value */
    fun update(value: Any?): ItemContext {
        this.value = value
        return this
    }

    /** Use plain Kotlin code to update the value. (Unlike most transforms, this also sees null.) */
    fun convert(function: (Any?) -> Any?): ItemContext {
        value = function(value)
        return this
    }

    /** Apply a pre-defined pipeline to update the value */
    fun apply(pipeline: Pipeline): ItemContext {
        pipeline(this)
        return this
    }

    /** Attempt a potentially fallible pipeline, and set the value to null if it fails */
    fun applyOrSetNull(pipeline: Pipeline): ItemContext {
        try {
            pipeline(this)
        } catch (e: Exception) {
            value = null
        }
        return this
    }

    /** Attempt a potentially fallible pipeline, and restore the previous value if it fails */
    fun applyOrRevert(pipeline: Pipeline): ItemContext {
        val revert = value
        try {
            pipeline(this)
        } catch (e: Exception) {
            value = revert
        }
        return this
    }

    /** Attempt a potentially fallible pipeline, and run [fallback] from the previous value if it fails */
    fun applyOrFallback(pipeline: Pipeline, fallback: Pipeline): ItemContext {
        val revert = value
        try {
            pipeline(this)
        } catch (e: Exception) {
            value = revert
            fallback(this)
        }
        return this
    }

    /** Debug tool to print the current value to the console */
    fun dump(prefix: String? = null): ItemContext {
        if (prefix != null) println(prefix)
        println(value)
        println()
        return this
    }

    // endregion

    // region Navigation

    /** Treat the current item as a record, allowing child items to be taken. */
    fun enter(block: SubRecordContext.() -> Unit = {}): SubRecordContext {
        val context = SubRecordContext(this, recordContext)
        context.block()
        return context
    }

    /** Alias for `scope().enter().take(...)` */
    fun take(name: Any, block: Pipeline = {}) = scope().enter().take(name, block)

    /** Alias for `scope().enter().takeDig(...)` */
    fun takeDig(vararg names: Any, block: Pipeline = {}) = scope().enter().takeDig(*names, block = block)

    /** Alias for `scope().enter().tryTake(...)` */
    fun tryTake(name: Any, block: Pipeline = {}) = scope().enter().tryTake(name, block)

    /**
     * Create a new item context with the same value as exists currently. Allows operations in a
     * scope that will not affect the value in the current scope.
     */
    fun scope(block: Pipeline = {}): ItemContext {
        val context = ItemContext(recordContext, value)
        block(context)
        return context
    }

    /**
     * Similar to take, but replaces the current value in the current scope
     */
    fun extract(name: Any): ItemContext = transform { Record.of(it).fetch(name) }

    /** Replace a list with a slice of itself */
    fun extract(range: IntRange): ItemContext = transform { iterable(it).toList().slice(range) }

    /** Extract from nested records. Every key along the way must exist. */
    fun extractDig(vararg names: Any): ItemContext = transform { Record.of(it).dig(names, strict = true) }

    // endregion

    // region Record context passthru

    /** Put the current value in the output record. */
    fun put(name: Any): ItemContext {
        recordContext.put(name, value)
        return this
    }

    /** Put a static or computed value in the output record. */
    fun put(name: Any, value: Any?): ItemContext {
        recordContext.put(name, value)
        return this
    }

    /**
     * Put the current value into a nested structure. A null key appends to a list; an Int key indexes a list;
     * anything else is a map key.
     */
    fun putBury(vararg keys: Any?): ItemContext {
        recordContext.putBury(keys.toList(), value)
        return this
    }

    fun static(value: Any?, block: Pipeline = {}) = recordContext.static(value, block)
    fun index(block: Pipeline = {}) = recordContext.index(block)
    fun collectList(vararg items: ItemContext, block: Pipeline = {}) = recordContext.collectList(*items, block = block)
    fun collectKv(items: Map<out Any?, ItemContext>, block: Pipeline = {}) = recordContext.collectKv(items, block)
    fun collectKv(vararg items: Pair<Any?, ItemContext>, block: Pipeline = {}) =
        recordContext.collectKv(*items, block = block)

    fun collectFormatString(template: String, vararg items: ItemContext, block: Pipeline = {}) =
        recordContext.collectFormatString(template, *items, block = block)

    fun skip(): Nothing = recordContext.skip()
    fun stop(): Nothing = recordContext.stop()
    fun flush(reset: Boolean = true) = recordContext.flush(reset)
    fun reset() = recordContext.reset()

    // endregion

    // region Parsing and formatting

    /** Parse a value to an integer. Fails if the text isn't a valid integer. */
    fun parseInt(radix: Int = 10): ItemContext = transform {
        when (it) {
            is Int -> it
            is Number -> it.toInt()
            else -> it.toString().trim().toInt(radix)
        }
    }

    /** Parse a value to a floating-point number. Fails if the text isn't a valid number. */
    fun parseDouble(): ItemContext = transform {
        when (it) {
            is Double -> it
            is Number -> it.toDouble()
            else -> it.toString().trim().toDouble()
        }
    }

    /** Parse a date. Converts the value to a string before parsing if it isn't one already. */
    fun parseDate(format: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE): ItemContext = transform {
        when (it) {
            is LocalDate -> it
            is LocalDateTime -> it.toLocalDate()
            else -> LocalDate.parse(it.toString(), format)
        }
    }

    /** Parse a date using a [DateTimeFormatter] pattern (note: not a strftime format) */
    fun parseDate(pattern: String) = parseDate(DateTimeFormatter.ofPattern(pattern))

    /** Parse a date and time. Converts the value to a string before parsing if it isn't one already. */
    fun parseDateTime(format: DateTimeFormatter = DEFAULT_DATETIME_FORMAT): ItemContext = transform {
        when (it) {
            is LocalDateTime -> it
            is LocalDate -> it.atStartOfDay()
            else -> LocalDateTime.parse(it.toString(), format)
        }
    }

    fun parseDateTime(pattern: String) = parseDateTime(DateTimeFormatter.ofPattern(pattern))

    /** Format a date (or anything with date fields) as a string */
    fun formatDate(format: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE): ItemContext = transform {
        if (it is TemporalAccessor) format.format(it) else throw ValueError("Cannot format $it as a date")
    }

    fun formatDate(pattern: String) = formatDate(DateTimeFormatter.ofPattern(pattern))

    /** Format a date and time as a string */
    fun formatDateTime(format: DateTimeFormatter = DEFAULT_DATETIME_FORMAT): ItemContext = transform {
        if (it is TemporalAccessor) format.format(it) else throw ValueError("Cannot format $it as a datetime")
    }

    fun formatDateTime(pattern: String) = formatDateTime(DateTimeFormatter.ofPattern(pattern))

    /** Parse a boolean based on a list of pre-defined true/false values */
    fun parseBoolean(
        trueValues: Set<Any> = DEFAULT_TRUE_VALUES,
        falseValues: Set<Any> = DEFAULT_FALSE_VALUES,
    ): ItemContext = transform {
        when (it) {
            is Boolean -> it
            in trueValues -> true
            in falseValues -> false
            else -> throw ValueError("Unrecognized boolean value: $it")
        }
    }

    /** Format a boolean as something else, typically a string */
    fun formatBoolean(trueValue: Any? = "Yes", falseValue: Any? = "No"): ItemContext = transform {
        when (it) {
            true, 1 -> trueValue
            false, 0 -> falseValue
            else -> throw ValueError("Cannot format $it as a boolean")
        }
    }

    /** Format the value with [String.format], or with `toString` if no template is given */
    fun formatString(template: String? = null): ItemContext = transform {
        if (template == null) it.toString() else template.format(it)
    }

    // endregion

    // region Common operations

    /**
     * Look up the value in a map.
     *
     * If there is no match: [onNotFound] (if given) is applied to the item; otherwise the value passes through
     * unchanged if [passIfNotFound], or becomes null if not. A warning is printed if [warnIfNotFound].
     */
    fun lookup(
        mapping: Map<*, *>,
        passIfNotFound: Boolean = false,
        onNotFound: Pipeline? = null,
        warnIfNotFound: Boolean = onNotFound == null,
    ): ItemContext = transform {
        @Suppress("UNCHECKED_CAST")
        val map = mapping as Map<Any?, Any?>
        when {
            map.containsKey(it) -> map[it]
            else -> {
                if (warnIfNotFound) System.err.println("Value $it not found in lookup")
                when {
                    onNotFound != null -> apply(onNotFound).value
                    passIfNotFound -> it
                    else -> null
                }
            }
        }
    }

    /** Look up the value in a lookup previously registered with [registerLookup] */
    fun lookup(
        registeredLookup: String,
        passIfNotFound: Boolean = false,
        onNotFound: Pipeline? = null,
        warnIfNotFound: Boolean = onNotFound == null,
    ): ItemContext {
        val mapping = lookups[registeredLookup] ?: throw PipelineError("No lookup '$registeredLookup' found")
        return lookup(mapping, passIfNotFound, onNotFound, warnIfNotFound)
    }

    /** Search and replace in a string. Converts the value to a string first if needed. */
    fun stringReplace(find: String, replace: String): ItemContext = transform { it.toString().replace(find, replace) }

    fun stringReplace(find: Regex, replace: String): ItemContext = transform { it.toString().replace(find, replace) }

    /** Strip whitespace from the ends of a string */
    fun strip(): ItemContext = transform { it.toString().trim() }

    /** Converts empty strings, collections or maps to null */
    fun nullIfEmpty(): ItemContext {
        if (isEmpty(value)) value = null
        return this
    }

    /** Convert a known sentinel value to null */
    fun nullIf(sentinel: Any?): ItemContext {
        if (value == sentinel) value = null
        return this
    }

    /** Skip the record on empty strings, collections or maps */
    fun skipIfEmpty(): ItemContext {
        if (isEmpty(value)) skip()
        return this
    }

    /** Skip the record on a known sentinel value */
    fun skipIf(sentinel: Any?): ItemContext {
        if (value == sentinel) skip()
        return this
    }

    /** Stop the migration on empty strings, collections or maps */
    fun stopIfEmpty(): ItemContext {
        if (isEmpty(value)) stop()
        return this
    }

    /** Stop the migration on a known sentinel value */
    fun stopIf(sentinel: Any?): ItemContext {
        if (value == sentinel) stop()
        return this
    }

    /** Provide a default if the value is null. (Unlike most transforms, this only acts on null.) */
    fun default(defaultValue: Any?): ItemContext {
        if (value == null) value = defaultValue
        return this
    }

    // endregion

    // region Strings and lists

    /**
     * Split a string on a delimiter into a list. If a [block] is given, it runs with the list as a record, so that
     * elements can be taken by index.
     */
    fun split(delimiter: String, block: (SubRecordContext.() -> Unit)? = null): ItemContext {
        transform { it.toString().split(delimiter) }
        if (block != null && value != null) enter(block)
        return this
    }

    /** Join a list into a string */
    fun join(delimiter: String): ItemContext = transform { iterable(it).joinToString(delimiter) }

    /**
     * Split a string into a map of key/value pairs. Items are separated by [itemDelimiter] (blank items are
     * ignored), and each is split at the first [kvDelimiter]. An item with no [kvDelimiter] gets a null value.
     * If a [block] is given, it runs with the map as a record.
     */
    fun splitKv(
        kvDelimiter: String,
        itemDelimiter: String = "\n",
        block: (SubRecordContext.() -> Unit)? = null,
    ): ItemContext {
        transform { text ->
            val kv = LinkedHashMap<String, String?>()
            for (item in text.toString().split(itemDelimiter)) {
                if (item.isEmpty()) continue
                val parts = item.split(kvDelimiter, limit = 2)
                kv[parts[0]] = parts.getOrNull(1)
            }
            kv
        }
        if (block != null && value != null) enter(block)
        return this
    }

    /** Join a map into a string; the inverse of [splitKv]. Null values are written as empty. */
    fun joinKv(kvDelimiter: String, itemDelimiter: String = "\n"): ItemContext = transform {
        if (it !is Map<*, *>) throw ValueError("Expected a map, got ${it::class.qualifiedName}")
        it.entries.joinToString(itemDelimiter) { (k, v) -> "$k$kvDelimiter${v ?: ""}" }
    }

    /** Perform a regex search, setting the value to the match (or null, with a warning, if none). */
    fun regex(pattern: Regex, block: (SubRecordContext.() -> Unit)? = null): ItemContext {
        transform {
            pattern.find(it.toString()) ?: run {
                System.err.println("$pattern does not match '$it'")
                null
            }
        }
        if (block != null && value != null) enter(block)
        return this
    }

    /** Replace a list with its first non-null element */
    fun coalesce(): ItemContext = transform { iterable(it).firstOrNull { e -> e != null } }

    /** Remove nulls from a list */
    fun compact(): ItemContext = transform { iterable(it).filterNotNull() }

    fun filter(predicate: (Any?) -> Boolean): ItemContext = transform { iterable(it).filter(predicate) }

    fun map(function: (Any?) -> Any?): ItemContext = transform { iterable(it).map(function) }

    /** Like [map], but the block is a pipeline run on each element, so all of the transforms are available */
    fun mapApply(pipeline: Pipeline): ItemContext = transform {
        iterable(it).map { element -> ItemContext(recordContext, element).apply(pipeline).value }
    }

    /**
     * Run [block] in a sub-record context for each element of a list, optionally flushing and/or resetting after
     * each. Used to produce multiple sink records from a single source record.
     */
    fun eachSubrecord(flush: Boolean = false, reset: Boolean = false, block: SubRecordContext.() -> Unit): ItemContext {
        value?.let { list ->
            for (element in iterable(list)) {
                SubRecordContext(ItemContext(recordContext, element), recordContext).block()
                if (flush) recordContext.flush(reset = false)
                if (reset) recordContext.reset()
            }
        }
        return this
    }

    // endregion

    // TODO binary pack/unpack, string encoding, JSON (these need design: Kotlin has no native equivalents)
}
