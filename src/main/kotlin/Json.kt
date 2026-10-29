package micdrop

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Parse a string as JSON into plain Kotlin values: maps, lists, strings, longs, doubles, booleans and null.
 * If a [block] is given, it runs with the result as a record, so that properties (or list elements) can be taken.
 */
fun ItemContext.parseJson(block: (SubRecordContext.() -> Unit)? = null): ItemContext {
    transform { text -> parseJsonValue(text.toString()) }
    if (block != null && value != null) enter(block)
    return this
}

/**
 * Format maps, lists and scalars as a compact JSON string. Other types (dates, for example) are written as
 * strings using `toString`.
 */
fun ItemContext.formatJson(): ItemContext = transform { formatJsonValue(it) }

/** Parse JSON text into plain Kotlin values, failing with a [ValueError] if it is not valid JSON */
internal fun parseJsonValue(text: String): Any? = try {
    Json.parseToJsonElement(text).toPlain()
} catch (e: SerializationException) {
    throw ValueError("Invalid JSON: ${e.message}", e)
}

/** Write plain Kotlin values as compact JSON text */
internal fun formatJsonValue(value: Any?): String = Json.encodeToString(JsonElement.serializer(), value.toJson())

private fun JsonElement.toPlain(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> mapValuesTo(LinkedHashMap<String, Any?>()) { it.value.toPlain() }
    is JsonArray -> map { it.toPlain() }
    is JsonPrimitive -> when {
        isString -> content
        else -> booleanOrNull ?: content.toLongOrNull() ?: content.toDouble()
    }
}

private fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is CharSequence -> JsonPrimitive(toString())
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJson() })
    is Iterable<*> -> JsonArray(map { it.toJson() })
    is Array<*> -> JsonArray(map { it.toJson() })
    else -> JsonPrimitive(toString())
}
