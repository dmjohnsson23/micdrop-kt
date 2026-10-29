package micdrop.ext.jooq

import micdrop.RootRecordContext
import micdrop.asSource
import micdrop.check
import micdrop.migrate
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockDataProvider
import org.jooq.tools.jdbc.MockExecuteContext
import org.jooq.tools.jdbc.MockResult
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * There is no MySQL server to test against here, so these check the SQL that the sinks and sources produce for the
 * MySQL dialect, by running them against a mock JDBC connection which records every statement.
 */
class JooqMySqlTest {
    private class Recorder(private val existingRows: Int = 0) : MockDataProvider {
        val statements = mutableListOf<String>()
        val bindings = mutableListOf<List<Any?>>()

        override fun execute(ctx: MockExecuteContext): Array<MockResult> {
            statements.add(ctx.sql())
            bindings.add(ctx.bindings().toList())
            val sql = ctx.sql().trim().lowercase()
            return when {
                sql.startsWith("select last_insert_id") -> arrayOf(
                    MockResult(1, DSL.using(SQLDialect.MYSQL).newResult(DSL.field("id", Long::class.java)).also {
                        it.add(DSL.using(SQLDialect.MYSQL).newRecord(DSL.field("id", Long::class.java)).also { r -> r.set(DSL.field("id", Long::class.java), 42L) })
                    })
                )
                sql.startsWith("select") -> {
                    val result = DSL.using(SQLDialect.MYSQL).newResult(DSL.field("id", Int::class.java))
                    repeat(existingRows) {
                        result.add(DSL.using(SQLDialect.MYSQL).newRecord(DSL.field("id", Int::class.java)).also { r ->
                            r.set(DSL.field("id", Int::class.java), 1)
                        })
                    }
                    arrayOf(MockResult(existingRows, result))
                }
                else -> arrayOf(MockResult(1))
            }
        }
    }

    private fun mysql(recorder: Recorder): DSLContext = DSL.using(MockConnection(recorder), SQLDialect.MYSQL)

    private fun run(source: List<Map<String, Any?>>, sink: micdrop.Sink, block: RootRecordContext.() -> Unit) =
        migrate(source.asSource(), sink, block)

    @Test
    fun insertsUseBackticksAndBindValues() {
        val recorder = Recorder()
        run(listOf(mapOf("id" to 1, "first_name" to "Ada")), InsertSink(mysql(recorder), "people", transactional = false)) {
            take("id").put("id")
            take("first_name").put("first_name")
        }
        check(listOf("insert into `people` (`id`, `first_name`) values (?, ?)"), recorder.statements)
        check(listOf(listOf<Any?>(1, "Ada")), recorder.bindings)
    }

    @Test
    fun unusualNamesAreQuotedTheMySqlWay() {
        val recorder = Recorder()
        run(listOf(mapOf("v" to 1)), InsertSink(mysql(recorder), "my db.people", transactional = false)) {
            put("Deceased?", 1)
        }
        check(listOf("insert into `my db`.`people` (`Deceased?`) values (?)"), recorder.statements)
    }

    @Test
    fun updatesMatchOnKeys() {
        val recorder = Recorder()
        val sink = UpdateSink(mysql(recorder), "people", listOf("id", "site"), transactional = false)
        run(listOf(mapOf("id" to 1, "site" to "A", "status" to "ok")), sink) {
            take("id").put("id")
            take("site").put("site")
            take("status").put("status")
        }
        check(
            listOf("update `people` set `id` = ?, `site` = ?, `status` = ? where (`id` = ? and `site` = ?)"),
            recorder.statements,
        )
        check(listOf(listOf<Any?>(1, "A", "ok", 1, "A")), recorder.bindings)
    }

    @Test
    fun nullKeysMatchWithIsNull() {
        val recorder = Recorder()
        val sink = UpdateSink(mysql(recorder), "people", listOf("status"), matchNullKeys = true, transactional = false)
        run(listOf(mapOf("status" to null, "notes" to "x")), sink) {
            take("status").put("status")
            take("notes").put("notes")
        }
        assertTrue("where `status` is null" in recorder.statements.single(), recorder.statements.single())
    }

    @Test
    fun upsertsSelectThenInsert() {
        val recorder = Recorder(existingRows = 0)
        val sink = InsertUpdateSink(mysql(recorder), "people", listOf("id"), transactional = false)
        run(listOf(mapOf("id" to 1, "status" to "ok")), sink) {
            take("id").put("id")
            take("status").put("status")
        }
        check(2, recorder.statements.size)
        assertTrue(
            recorder.statements[0].startsWith("select") && "from `people` where `id` = ?" in recorder.statements[0] &&
                "limit ?" in recorder.statements[0],
            recorder.statements[0],
        )
        check("insert into `people` (`id`, `status`) values (?, ?)", recorder.statements[1])
        check(true, sink.wasInsert)
    }

    @Test
    fun upsertsSelectThenUpdate() {
        val recorder = Recorder(existingRows = 1)
        val sink = InsertUpdateSink(mysql(recorder), "people", listOf("id"), transactional = false)
        run(listOf(mapOf("id" to 1, "status" to "ok")), sink) {
            take("id").put("id")
            take("status").put("status")
        }
        check(2, recorder.statements.size)
        check("update `people` set `status` = ? where `id` = ?", recorder.statements[1])
        check(false, sink.wasInsert)
    }

    @Test
    fun generatedIdsUseLastInsertId() {
        val recorder = Recorder()
        val sink = InsertSink(mysql(recorder), "people", transactional = false)
        run(listOf(mapOf("first_name" to "Ada")), sink) { take("first_name").put("first_name") }
        check(42L, sink.lastInsertId)
        check("select last_insert_id()", recorder.statements.last().trim().lowercase())
    }

    @Test
    fun suppliedIdsAreReportedWithoutAnExtraQuery() {
        val recorder = Recorder()
        val sink = InsertSink(mysql(recorder), "people", idColumn = "id", transactional = false)
        run(listOf(mapOf("id" to 7)), sink) { take("id").put("id") }
        check(7, sink.lastInsertId)
        check(1, recorder.statements.size)
    }

    @Test
    fun sourcesRenderForMySql() {
        val recorder = Recorder()
        val db = mysql(recorder)
        JooqSource(db.selectFrom(table("people")).where(column("status").eq("alive")), fetchSize = Int.MIN_VALUE)
            .entries().toList()
        assertTrue("from `people` where `status` = ?" in recorder.statements.single(), recorder.statements.single())
    }

    @Test
    fun dbLookupsRenderForMySql() {
        val recorder = Recorder()
        DbLookup(mysql(recorder), "asset_type", "id", "label").find(2)
        val sql = recorder.statements.single()
        assertTrue(sql.startsWith("select `label` from `asset_type` where `id` = ?"), sql)
        assertTrue("limit ?" in sql, sql)
    }
}
