package org.example.ext.jooq

import org.example.ItemContext
import org.example.PipelineError
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException

/**
 * Looks up values in a database table, for converting keys from the source into something from the sink's database
 * (the ID of a row, say). It finds the first row of [table] whose [keyColumn] equals the key, and yields the whole
 * row as a map, or just its [valueColumn] if given. See [dbLookup].
 *
 * Each lookup is a query. If the same keys come up often, set [cache] to remember the answers (including the
 * absence of an answer) for as long as this object lives, so create it once, outside the migration block. Don't
 * use it if the table changes during the migration.
 *
 * To preload a whole small table into an in-memory lookup instead, build a map with jOOQ's `fetchMap` and use the
 * ordinary `lookup`.
 */
class DbLookup(
    private val ctx: DSLContext,
    private val table: String,
    private val keyColumn: String,
    private val valueColumn: String? = null,
    private val cache: Boolean = false,
) {
    /** The result of a successful lookup. (The [value] may still be null if the table says so.) */
    class Found(val value: Any?)

    private val cached = HashMap<Any, Found?>()

    /** Look up [key], returning null if the table has no such row */
    fun find(key: Any): Found? {
        if (cache && cached.containsKey(key)) return cached[key]
        return query(key).also { if (cache) cached[key] = it }
    }

    private fun query(key: Any): Found? = try {
        val where = fieldOf(keyColumn).eq(key)
        if (valueColumn == null) {
            ctx.selectFrom(tableOf(table)).where(where).limit(1).fetchMaps().firstOrNull()
                ?.let { Found(normalizeRow(it)) }
        } else {
            val result = ctx.select(fieldOf(valueColumn)).from(tableOf(table)).where(where).limit(1).fetch()
            if (result.isEmpty()) null else Found(normalize(result.getValue(0, 0)))
        }
    } catch (e: DataAccessException) {
        throw PipelineError("Database lookup in '$table' failed: ${e.message}", e)
    }
}

/**
 * Look up the value in a database, using a [DbLookup]. Throws a [PipelineError] if nothing is found; use
 * [ItemContext.pipeOrSetNull] and friends to recover. Null values are left alone.
 */
fun ItemContext.dbLookup(lookup: DbLookup): ItemContext = transform { key ->
    val found = lookup.find(key) ?: throw PipelineError("Value $key not found in db lookup")
    found.value
}

/** Look up the value in a database table, without caching. See [DbLookup]. */
fun ItemContext.dbLookup(
    ctx: DSLContext,
    table: String,
    keyColumn: String,
    valueColumn: String? = null,
): ItemContext = dbLookup(DbLookup(ctx, table, keyColumn, valueColumn))
