package org.example.ext.jooq

import org.example.Collector
import org.example.Sink
import org.example.SinkError
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import java.math.BigDecimal

/**
 * Common behavior of the database sinks: they write through a [JooqSession], which holds their writes in a
 * transaction until the migration commits or rolls back. Statement failures are reported as [SinkError]s.
 */
abstract class JooqSink internal constructor(protected val session: JooqSession, table: String) : Sink {
    protected val tableName = table
    protected val tableRef = tableOf(table)

    override fun commit() = session.commit()

    override fun rollback(cause: Throwable) = session.rollback()

    override fun close() = session.close()

    protected fun <T> statement(what: String, block: () -> T): T = try {
        block()
    } catch (e: DataAccessException) {
        throw SinkError("Could not $what '$tableName': ${e.message}", e)
    }

    protected fun insertRow(collector: Collector, idColumn: String?): Any? {
        statement("insert into") {
            session.dsl.insertInto(tableRef).set(fieldMap(collector)).execute()
        }
        return idColumn?.let { collector[it] }
    }

    protected fun updateRows(values: Map<String, Any?>, condition: Condition): Int = statement("update") {
        session.dsl.update(tableRef).set(fieldMap(values)).where(condition).execute()
    }

    protected fun generatedId(): Any? = statement("read the new ID of") { session.dsl.lastID().toLong() }

    private fun fieldMap(values: Map<*, *>): Map<Field<Any?>, Any?> =
        values.entries.associate { (key, value) -> fieldOf(key.toString()) to value }
}

/**
 * A condition matching the row identified by the [keys] in [collector]. Returns null if that is impossible because
 * a key is null (or missing), unless [matchNullKeys], in which case a null key matches rows where the column is null.
 */
internal fun keyCondition(collector: Map<*, *>, keys: List<String>, matchNullKeys: Boolean): Condition? {
    var condition: Condition = DSL.noCondition()
    for (key in keys) {
        val value = collector[key]
        val field = fieldOf(key)
        condition = condition.and(
            when {
                value != null -> field.eq(value)
                matchNullKeys -> field.isNull
                else -> return null
            }
        )
    }
    return condition
}

/**
 * A sink which inserts every record as a new row of [table].
 *
 * Records are written through a [JooqSession]; given a [DSLContext] instead, the sink makes its own, with
 * [transactional] choosing whether it holds a transaction.
 *
 * [lastInsertId] gives the ID of the row inserted last, for use in an `afterFlush` hook. It is the ID the database
 * generated (so it needs an auto-increment column). If you supply the ID yourself, name its column as [idColumn] so
 * the value you supplied is reported instead. (Some databases, MySQL among them, don't report supplied IDs as
 * generated ones.)
 */
class InsertSink(
    session: JooqSession,
    table: String,
    private val idColumn: String? = null,
) : JooqSink(session, table) {
    constructor(ctx: DSLContext, table: String, idColumn: String? = null, transactional: Boolean = true) :
        this(JooqSession(ctx, transactional), table, idColumn)

    private var inserted = false
    private var suppliedId: Any? = null

    /** The ID of the last row inserted, or null if nothing was inserted. Read it before the migration ends. */
    val lastInsertId: Any?
        get() = if (!inserted) null else suppliedId ?: generatedId()

    override fun write(collector: Collector) {
        suppliedId = insertRow(collector, idColumn)
        inserted = true
    }
}

/**
 * A sink which updates the row of [table] identified by the [keys] for every record. Each record needs a non-null
 * value for every key, or the update is a [SinkError] (unless [matchNullKeys], where null matches rows with a null in
 * that column). Records matching no row do nothing; check [lastRowsUpdated] in an `afterFlush` hook if that matters.
 */
class UpdateSink(
    session: JooqSession,
    table: String,
    private val keys: List<String>,
    private val matchNullKeys: Boolean = false,
) : JooqSink(session, table) {
    constructor(
        ctx: DSLContext,
        table: String,
        keys: List<String>,
        matchNullKeys: Boolean = false,
        transactional: Boolean = true,
    ) : this(JooqSession(ctx, transactional), table, keys, matchNullKeys)

    init {
        require(keys.isNotEmpty()) { "At least one key column is required" }
    }

    /** How many rows the last record updated */
    var lastRowsUpdated = 0
        private set

    override fun write(collector: Collector) {
        val condition = keyCondition(collector, keys, matchNullKeys)
            ?: throw SinkError("Cannot update '$tableName': a key (${keys.joinToString(", ")}) has no value")
        lastRowsUpdated = updateRows(collector.mapKeys { it.key.toString() }, condition)
    }
}

/** What an [InsertUpdateSink] does with a column's value when the row already exists */
enum class UpdateAction {
    /** Use the new value, unless it is null, in which case keep the existing one */
    Coalesce,

    /** Use the new value only if the existing one is null */
    OverwriteNulls,

    /** Always use the new value, even if it is null */
    AlwaysOverwrite,

    /** Always keep the existing value */
    KeepExisting,

    /** Add the new text after the existing text, separated by a space */
    Append,

    /** Add the new text after the existing text, on a new line */
    AppendLine,

    /** Add the new text before the existing text, separated by a space */
    Prepend,

    /** Add the new text before the existing text, on a new line */
    PrependLine,

    /** Add the new number to the existing number */
    Add,
}

/**
 * A sink which updates the row of [table] identified by the [keys] if there is one, or inserts a new row otherwise.
 *
 * How an existing row is updated is decided column by column by [updateActions], with [defaultUpdateAction] for the
 * rest. The default, [UpdateAction.Coalesce], overwrites with every value except nulls. Only columns the record has
 * are touched, and only if the result is different. When [UpdateAction.Append] and friends combine text, a null or
 * empty existing (or new) value counts as nothing, so no stray separator is added.
 *
 * A key which is null (or missing) can't identify a row, so the record is inserted. With [matchNullKeys], it instead
 * matches the rows where that column is null. Keys which match more than one row are a [SinkError].
 *
 * [wasInsert] tells whether the last record was inserted rather than used to update, and [lastInsertId] gives the ID
 * of the last insert, or null if the last record was an update. See [InsertSink] about [idColumn].
 */
class InsertUpdateSink(
    session: JooqSession,
    table: String,
    private val keys: List<String>,
    private val updateActions: Map<String, UpdateAction> = emptyMap(),
    private val defaultUpdateAction: UpdateAction = UpdateAction.Coalesce,
    private val matchNullKeys: Boolean = false,
    private val idColumn: String? = null,
) : JooqSink(session, table) {
    constructor(
        ctx: DSLContext,
        table: String,
        keys: List<String>,
        updateActions: Map<String, UpdateAction> = emptyMap(),
        defaultUpdateAction: UpdateAction = UpdateAction.Coalesce,
        matchNullKeys: Boolean = false,
        idColumn: String? = null,
        transactional: Boolean = true,
    ) : this(
        JooqSession(ctx, transactional), table, keys, updateActions, defaultUpdateAction, matchNullKeys, idColumn,
    )

    init {
        require(keys.isNotEmpty()) { "At least one key column is required" }
    }

    /** Whether the last record was inserted (true) or updated an existing row (false). Null before any record. */
    var wasInsert: Boolean? = null
        private set

    private var suppliedId: Any? = null

    /** The ID of the row the last record inserted, or null if it updated one instead */
    val lastInsertId: Any?
        get() = if (wasInsert != true) null else suppliedId ?: generatedId()

    override fun write(collector: Collector) {
        val condition = keyCondition(collector, keys, matchNullKeys)
        val existing = if (condition == null) {
            emptyList()
        } else {
            statement("look up the existing row in") {
                session.dsl.selectFrom(tableRef).where(condition).limit(2).fetchMaps()
            }
        }
        when {
            existing.size > 1 ->
                throw SinkError("Key column(s) ${keys.joinToString(", ")} of '$tableName' are not unique")
            existing.isEmpty() -> {
                suppliedId = insertRow(collector, idColumn)
                wasInsert = true
            }
            else -> {
                val changes = changesTo(normalizeRow(existing.single()), collector)
                if (changes.isNotEmpty()) updateRows(changes, condition!!)
                suppliedId = null
                wasInsert = false
            }
        }
    }

    /** The columns of [collector] which should be written, with the values to write, given the [existing] row */
    private fun changesTo(existing: Map<String, Any?>, collector: Collector): Map<String, Any?> {
        val changes = LinkedHashMap<String, Any?>()
        for ((key, new) in collector) {
            val column = key.toString()
            val old = existing[column]
            val merged = merge(updateActions[column] ?: defaultUpdateAction, old, new)
            if (merged != old) changes[column] = merged
        }
        return changes
    }
}

internal fun merge(action: UpdateAction, old: Any?, new: Any?): Any? = when (action) {
    UpdateAction.Coalesce -> new ?: old
    UpdateAction.OverwriteNulls -> old ?: new
    UpdateAction.AlwaysOverwrite -> new
    UpdateAction.KeepExisting -> old
    UpdateAction.Append -> combine(old, new, " ")
    UpdateAction.AppendLine -> combine(old, new, "\n")
    UpdateAction.Prepend -> combine(new, old, " ")
    UpdateAction.PrependLine -> combine(new, old, "\n")
    UpdateAction.Add -> add(old, new)
}

private fun combine(first: Any?, second: Any?, separator: String): Any? = when {
    first == null || first.toString().isEmpty() -> second
    second == null || second.toString().isEmpty() -> first
    else -> "$first$separator$second"
}

private fun add(old: Any?, new: Any?): Any? {
    if (old == null) return new
    if (new == null) return old
    if (old !is Number || new !is Number) {
        throw SinkError("Cannot add ${new::class.simpleName} to ${old::class.simpleName}")
    }
    return when {
        old.isWholeNumber() && new.isWholeNumber() -> old.toLong() + new.toLong()
        old is Double || old is Float || new is Double || new is Float -> old.toDouble() + new.toDouble()
        else -> BigDecimal(old.toString()).add(BigDecimal(new.toString()))
    }
}

private fun Number.isWholeNumber() = this is Byte || this is Short || this is Int || this is Long
