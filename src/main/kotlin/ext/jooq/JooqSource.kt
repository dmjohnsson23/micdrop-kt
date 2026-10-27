package org.example.ext.jooq

import org.example.Record
import org.example.Source
import org.example.SourceEntry
import org.example.SourceError
import org.jooq.Cursor
import org.jooq.DSLContext
import org.jooq.ResultQuery
import org.jooq.exception.DataAccessException

/**
 * A source of database rows: one record per row of the results of a jOOQ query, indexed by position starting from 0.
 * Any query will do, so the whole jOOQ DSL is available for filtering, joining and ordering:
 *
 * ```
 * JooqSource(db.selectFrom(DSL.table(DSL.name("people"))).where(DSL.field(DSL.name("status")).eq("alive")))
 * JooqSource(db.resultQuery("select * from people where status = ?", "alive"))
 * JooqSource.table(db, "people")
 * ```
 *
 * Each record is a `Map<String, Any?>` keyed by column name (or alias), so `take("first_name")`. Dates and times
 * are given as `java.time` types rather than the legacy `java.sql` ones.
 *
 * Rows are fetched lazily as the migration needs them. [fetchSize] is passed on to the driver, as a hint of how many
 * rows to fetch at a time. MySQL's driver, for one, reads the entire result into memory unless told otherwise; for
 * large tables use `fetchSize = Int.MIN_VALUE` (or enable `useCursorFetch` in the connection URL and use a positive
 * size). A streaming MySQL result keeps its connection busy until it is fully read, so the source and the sinks of
 * such a migration must not share a connection: build the sink's [DSLContext] from a `DataSource`, or from a
 * different connection.
 *
 * The data can only be read once.
 */
class JooqSource(
    private val query: ResultQuery<*>,
    private val fetchSize: Int? = null,
) : Source {
    private var cursor: Cursor<*>? = null

    override fun entries(): Sequence<SourceEntry> = sequence {
        val cursor = try {
            (if (fetchSize != null) query.fetchSize(fetchSize) else query).fetchLazy()
        } catch (e: DataAccessException) {
            throw SourceError("Query failed: ${e.message}", e)
        }
        this@JooqSource.cursor = cursor
        cursor.use { cursor ->
            var index = 0
            while (true) {
                val row = try {
                    if (cursor.hasNext()) cursor.fetchNext() else null
                } catch (e: DataAccessException) {
                    throw SourceError("Could not read row $index: ${e.message}", e)
                } ?: break
                yield(SourceEntry(index++, Record.of(normalizeRow(row.intoMap()))))
            }
        }
    }

    override fun close() {
        cursor?.close()
    }

    companion object {
        /** Every row of a table */
        fun table(ctx: DSLContext, name: String, fetchSize: Int? = null) =
            JooqSource(ctx.selectFrom(tableOf(name)), fetchSize)
    }
}
