package org.example.ext.jooq

import org.jooq.Field
import org.jooq.Table
import org.jooq.impl.DSL
import java.sql.Time
import java.sql.Timestamp

/** A table by name, quoted properly for the dialect. A dotted name (`schema.table`) is taken as qualified. */
internal fun tableOf(name: String): Table<*> = DSL.table(DSL.name(*name.split(".").toTypedArray()))

/** A column by name, quoted properly for the dialect */
internal fun fieldOf(name: String): Field<Any?> = DSL.field(DSL.name(name))

/**
 * Convert the legacy `java.sql` date and time types which JDBC drivers return into `java.time` ones, so that
 * `parseDate`, `formatDate` and the other transforms work on database values.
 */
internal fun normalize(value: Any?): Any? = when (value) {
    is Timestamp -> value.toLocalDateTime()
    is java.sql.Date -> value.toLocalDate()
    is Time -> value.toLocalTime()
    else -> value
}

internal fun normalizeRow(row: Map<String, Any?>): MutableMap<String, Any?> =
    row.mapValuesTo(LinkedHashMap()) { normalize(it.value) }
