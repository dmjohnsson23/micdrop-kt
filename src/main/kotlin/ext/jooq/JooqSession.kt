package micdrop.ext.jooq

import micdrop.SinkError
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.jooq.impl.DefaultConnectionProvider
import java.sql.Connection
import java.sql.SQLException

/**
 * The connection and transaction that one or more database sinks write through.
 *
 * With [transactional] (the default), the session takes a connection from [ctx] the first time it is used and turns
 * off auto-commit, so that everything written is held in a transaction until [commit]. A migration calls [commit]
 * when it finishes successfully and [rollback] when it fails. Closing a session which wasn't committed discards its
 * writes.
 *
 * Sinks created from a plain [DSLContext] each make their own session. To have several sinks succeed or fail as a
 * unit, create one session and give it to each of them:
 *
 * ```
 * val session = JooqSession(db)
 * val sink = MultiSink("person" to InsertSink(session, "people"), "phone" to InsertSink(session, "phones"))
 * ```
 *
 * With `transactional = false`, nothing is managed: statements run directly on [ctx], as they would have outside a
 * transaction (so, in auto-commit mode, each is committed as it is written). Use this for very large loads where one
 * huge transaction would be a problem, or to take part in a transaction you manage yourself.
 *
 * The transaction is held on its own connection (when [ctx] is backed by a `DataSource`) or on the connection [ctx]
 * is already using. Reads made through [ctx] elsewhere in the migration, such as from a source or a [DbLookup], do
 * not see the uncommitted writes unless they happen to share that connection.
 */
class JooqSession(private val ctx: DSLContext, private val transactional: Boolean = true) : AutoCloseable {
    private var connection: Connection? = null
    private var previousAutoCommit = true
    private var current: DSLContext? = null

    /** The context to run statements on. Starts the transaction the first time it is used. */
    val dsl: DSLContext
        get() = current ?: start().also { current = it }

    private fun start(): DSLContext {
        if (!transactional) return ctx
        val provider = ctx.configuration().connectionProvider()
        val conn = try {
            provider.acquire()
        } catch (e: DataAccessException) {
            throw SinkError("Could not get a database connection: ${e.message}", e)
        } ?: throw SinkError("Could not get a database connection")
        try {
            previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
        } catch (e: SQLException) {
            provider.release(conn)
            throw SinkError("Could not start a transaction: ${e.message}", e)
        }
        connection = conn
        return ctx.configuration().derive(DefaultConnectionProvider(conn)).dsl()
    }

    /** Make everything written so far permanent. Does nothing if nothing has been written. */
    fun commit() {
        val conn = connection ?: return
        try {
            conn.commit()
        } catch (e: SQLException) {
            throw SinkError("Commit failed: ${e.message}", e)
        }
    }

    /** Discard everything written since the last commit. Does nothing if nothing has been written. */
    fun rollback() {
        val conn = connection ?: return
        try {
            conn.rollback()
        } catch (e: SQLException) {
            throw SinkError("Rollback failed: ${e.message}", e)
        }
    }

    /**
     * Discard anything not yet committed, then give the connection back with its original auto-commit setting. (The
     * discarding matters: switching auto-commit back on would otherwise commit it.) A closed session may be used
     * again, in which case it starts a new transaction.
     */
    override fun close() {
        val conn = connection
        connection = null
        current = null
        if (conn == null) return
        try {
            conn.rollback()
            conn.autoCommit = previousAutoCommit
        } catch (e: SQLException) {
            throw SinkError("Could not end the transaction cleanly: ${e.message}", e)
        } finally {
            ctx.configuration().connectionProvider().release(conn)
        }
    }
}
