package org.example.ext.jooq

import org.example.Collector
import org.example.MultiSink
import org.example.SinkError
import org.example.asSink
import org.example.asSource
import org.example.check
import org.example.migrate
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Transactions need more than one connection to observe, so these use a SQLite file with a DataSource: every
 * `db` statement gets its own connection, just like another session looking at the database would have.
 */
class JooqTransactionTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var db: DSLContext

    @BeforeTest
    fun createDatabase() {
        val dataSource = SQLiteDataSource().apply { url = "jdbc:sqlite:${dir.resolve("test.db")}" }
        db = DSL.using(dataSource, SQLDialect.SQLITE)
        createSchema(db)
    }

    private val newPeople = (10..14).map { mapOf("id" to it, "first_name" to "Person $it") }

    private fun org.example.RootRecordContext.putPerson() {
        take("id").put("id")
        take("first_name").put("first_name")
    }

    /** Fails on the record at [failAt], after earlier ones have been written */
    private fun org.example.RootRecordContext.failAt(failAt: Int) {
        if (index().value == failAt) throw IllegalStateException("boom at $failAt")
    }

    // region the default: transactional

    @Test
    fun commitsWhenTheMigrationSucceeds() {
        migrate(newPeople.asSource(), InsertSink(db, "people")) { putPerson() }
        check(8, db.count("people"))
    }

    @Test
    fun rollsBackEverythingWhenTheMigrationFails() {
        val error = assertFailsWith<IllegalStateException> {
            migrate(newPeople.asSource(), InsertSink(db, "people")) {
                putPerson()
                failAt(3)
            }
        }
        check("boom at 3", error.message)
        check(3, db.count("people"))
    }

    @Test
    fun rollsBackWhenAStatementFails() {
        val records = newPeople.take(2) + mapOf("id" to 1, "first_name" to "Duplicate") + newPeople.drop(2)
        assertFailsWith<SinkError> { migrate(records.asSource(), InsertSink(db, "people")) { putPerson() } }
        check(3, db.count("people"))
    }

    @Test
    fun rollsBackWhenTheSourceFails() {
        val source = JooqSource(db.resultQuery("select id, first_name from people where cast(id as text) != 'x'"))
        val copy = InsertSink(db, "people")
        assertFailsWith<IllegalStateException> {
            migrate(source, copy) {
                take("id") { update((value as Int) + 100); put("id") }
                take("first_name").put("first_name")
                failAt(2)
            }
        }
        check(3, db.count("people"))
    }

    @Test
    fun otherConnectionsDontSeeUncommittedWrites() {
        val seenMidway = mutableListOf<Int>()
        migrate(newPeople.asSource(), InsertSink(db, "people")) {
            putPerson()
            afterFlush { _, _ -> seenMidway.add(db.count("people")) }
        }
        check(listOf(3, 3, 3, 3, 3), seenMidway)
        check(8, db.count("people"))
    }

    @Test
    fun theSinkSeesItsOwnWrites() {
        val sink = InsertSink(db, "people", idColumn = "id")
        val ids = mutableListOf<Any?>()
        migrate(newPeople.take(2).asSource(), sink) {
            putPerson()
            afterFlush { _, _ -> ids.add(sink.lastInsertId) }
        }
        check(listOf<Any?>(10, 11), ids)
    }

    @Test
    fun generatedIdsWorkInsideTheTransaction() {
        val sink = InsertSink(db, "people")
        val ids = mutableListOf<Any?>()
        migrate(listOf(mapOf("n" to "a"), mapOf("n" to "b")).asSource(), sink) {
            take("n").put("first_name")
            afterFlush { _, _ -> ids.add(sink.lastInsertId) }
        }
        check(listOf<Any?>(4L, 5L), ids)
    }

    @Test
    fun updatesRollBackToo() {
        val records = listOf(
            mapOf("id" to 1, "status" to "dead"),
            mapOf("id" to 2, "status" to "dead"),
            mapOf("id" to 3, "status" to "dead"),
        )
        assertFailsWith<IllegalStateException> {
            migrate(records.asSource(), UpdateSink(db, "people", listOf("id"))) {
                take("id").put("id")
                take("status").put("status")
                failAt(2)
            }
        }
        check(listOf("alive", "alive", "alive"), db.rows("people", "id").map { it["status"] })
    }

    @Test
    fun upsertsRollBackToo() {
        val records = listOf(
            mapOf("id" to 1, "status" to "dead"),
            mapOf("id" to 20, "status" to "new"),
            mapOf("id" to 3, "status" to "dead"),
        )
        assertFailsWith<IllegalStateException> {
            migrate(records.asSource(), InsertUpdateSink(db, "people", listOf("id"))) {
                take("id").put("id")
                take("status").put("status")
                failAt(2)
            }
        }
        check(3, db.count("people"))
        check("alive", db.rows("people", "id").first()["status"])
    }

    @Test
    fun readingFromTheDatabaseWhileWritingToIt() {
        db.execute("create table people_copy (id integer, first_name text)")
        migrate(JooqSource.table(db, "people"), InsertSink(db, "people_copy")) {
            putPerson()
        }
        check(3, db.count("people_copy"))
    }

    @Test
    fun aFailedCopyLeavesTheTargetEmpty() {
        db.execute("create table people_copy (id integer, first_name text)")
        assertFailsWith<IllegalStateException> {
            migrate(JooqSource.table(db, "people"), InsertSink(db, "people_copy")) {
                putPerson()
                failAt(2)
            }
        }
        check(0, db.count("people_copy"))
    }

    @Test
    fun anEmptyMigrationDoesNothing() {
        migrate(emptyList<Map<String, Any?>>().asSource(), InsertSink(db, "people")) { putPerson() }
        check(3, db.count("people"))
    }

    @Test
    fun stoppingEarlyKeepsWhatWasWritten() {
        migrate(newPeople.asSource(), InsertSink(db, "people")) {
            putPerson()
            if (index().value == 2) stop()
        }
        check(5, db.count("people")) // 10 and 11 written; the record that stopped was not
    }

    // endregion

    // region sharing a session

    private fun assets() = listOf(mapOf("id" to 10, "label" to "A"), mapOf("id" to 11, "label" to "B"))

    @Test
    fun sinksSharingASessionSucceedTogether() {
        val session = JooqSession(db)
        val sink = MultiSink("person" to InsertSink(session, "people"), "asset" to InsertSink(session, "asset_type"))
        migrate(assets().asSource(), sink) {
            take("id") { putBury("person", "id"); putBury("asset", "id") }
            take("label") { putBury("person", "first_name"); putBury("asset", "label") }
        }
        check(5, db.count("people"))
        check(6, db.count("asset_type"))
    }

    @Test
    fun sinksSharingASessionFailTogether() {
        val session = JooqSession(db)
        val sink = MultiSink("person" to InsertSink(session, "people"), "asset" to InsertSink(session, "asset_type"))
        assertFailsWith<IllegalStateException> {
            migrate(assets().asSource(), sink) {
                take("id") { putBury("person", "id"); putBury("asset", "id") }
                take("label") { putBury("person", "first_name"); putBury("asset", "label") }
                failAt(1)
            }
        }
        check(3, db.count("people"))
        check(4, db.count("asset_type"))
    }

    // endregion

    // region not transactional

    @Test
    fun withoutTransactionsEachWriteIsKept() {
        assertFailsWith<IllegalStateException> {
            migrate(newPeople.asSource(), InsertSink(db, "people", transactional = false)) {
                putPerson()
                failAt(3)
            }
        }
        check(6, db.count("people"))
    }

    @Test
    fun withoutTransactionsWritesAreImmediatelyVisible() {
        val seenMidway = mutableListOf<Int>()
        migrate(newPeople.take(3).asSource(), InsertSink(db, "people", transactional = false)) {
            putPerson()
            afterFlush { _, _ -> seenMidway.add(db.count("people")) }
        }
        check(listOf(4, 5, 6), seenMidway)
    }

    @Test
    fun aTransactionYouManageYourselfIsLeftAlone() {
        assertFailsWith<IllegalStateException> {
            db.transaction { config ->
                val tx = DSL.using(config)
                migrate(newPeople.asSource(), InsertSink(tx, "people", transactional = false)) {
                    putPerson()
                }
                throw IllegalStateException("your own failure")
            }
        }
        check(3, db.count("people"))

        db.transaction { config ->
            migrate(newPeople.asSource(), InsertSink(DSL.using(config), "people", transactional = false)) { putPerson() }
        }
        check(8, db.count("people"))
    }

    // endregion

    // region sessions directly

    @Test
    fun closingWithoutCommittingDiscardsWrites() {
        val session = JooqSession(db)
        session.dsl.insertInto(table("people")).set(mapOf(column("id") to 50)).execute()
        session.close()
        check(3, db.count("people"))
    }

    @Test
    fun committingMakesWritesPermanent() {
        val session = JooqSession(db)
        session.dsl.insertInto(table("people")).set(mapOf(column("id") to 50)).execute()
        session.commit()
        session.close()
        check(4, db.count("people"))
    }

    @Test
    fun rollingBackDiscardsWritesButKeepsTheSessionUsable() {
        val session = JooqSession(db)
        session.dsl.insertInto(table("people")).set(mapOf(column("id") to 50)).execute()
        session.rollback()
        session.dsl.insertInto(table("people")).set(mapOf(column("id") to 51)).execute()
        session.commit()
        session.close()
        check(listOf(1, 2, 3, 51), db.rows("people", "id").map { it["id"] })
    }

    @Test
    fun aSessionCanBeReusedAfterClosing() {
        val session = JooqSession(db)
        session.dsl.insertInto(table("people")).set(mapOf(column("id") to 50)).execute()
        session.commit()
        session.close()
        session.dsl.insertInto(table("people")).set(mapOf(column("id") to 51)).execute()
        session.close() // not committed
        check(4, db.count("people"))
    }

    @Test
    fun anUnusedSessionDoesNothing() {
        val session = JooqSession(db)
        session.commit()
        session.rollback()
        session.close()
        session.close()
        check(3, db.count("people"))
    }

    // endregion
}

/** The same behaviors when everything shares a single connection, as with a `DSLContext` made from a `Connection` */
class JooqSingleConnectionTransactionTest : InMemoryDatabaseTest() {
    @Test
    fun commitsAndRestoresAutoCommit() {
        check(true, connection.autoCommit)
        var duringMigration: Boolean? = null
        migrate(listOf(mapOf("id" to 10)).asSource(), InsertSink(db, "people")) {
            take("id").put("id")
            afterFlush { _, _ -> duringMigration = connection.autoCommit }
        }
        check(false, duringMigration)
        check(true, connection.autoCommit)
        check(4, db.count("people"))
    }

    @Test
    fun rollsBackAndRestoresAutoCommit() {
        assertFailsWith<IllegalStateException> {
            migrate(listOf(mapOf("id" to 10), mapOf("id" to 11)).asSource(), InsertSink(db, "people")) {
                take("id").put("id")
                if (index().value == 1) throw IllegalStateException("boom")
            }
        }
        check(true, connection.autoCommit)
        check(3, db.count("people"))
    }

    @Test
    fun leavesAnOriginalNonAutoCommitSettingAlone() {
        connection.autoCommit = false
        migrate(listOf(mapOf("id" to 10)).asSource(), InsertSink(db, "people")) { take("id").put("id") }
        check(false, connection.autoCommit)
        check(4, db.count("people"))
    }

    @Test
    fun nonTransactionalSinksDontTouchTheConnection() {
        var duringMigration: Boolean? = null
        migrate(listOf(mapOf("id" to 10)).asSource(), InsertSink(db, "people", transactional = false)) {
            take("id").put("id")
            afterFlush { _, _ -> duringMigration = connection.autoCommit }
        }
        check(true, duringMigration)
    }

    @Test
    fun theSessionOnlyTakesTheConnectionWhenFirstUsed() {
        val session = JooqSession(db)
        check(true, connection.autoCommit)
        session.dsl
        check(false, connection.autoCommit)
        session.close()
        check(true, connection.autoCommit)
    }

    @Test
    fun closingAnUncommittedSessionDoesNotCommitByRestoringAutoCommit() {
        val session = JooqSession(db)
        session.dsl.insertInto(table("people")).set(mapOf(column("id") to 50)).execute()
        session.close()
        check(3, db.count("people"))
    }

    @Test
    fun csvToDatabaseExample() {
        val csv = "Name,Born,Deceased?\nAda,12/10/15,\nGrace,12/09/06,Y\n"
        db.execute("create table clients (id integer primary key autoincrement, name text, dob date, is_deceased boolean)")
        val sink = InsertSink(db, "clients")
        val ids = mutableListOf<Any?>()
        migrate(org.example.ext.CsvSource(java.io.StringReader(csv)), sink) {
            take("Name").put("name")
            take("Born") { parseDate("MM/dd/yy"); put("dob") }
            take("Deceased?") { parseBoolean(); default(false); put("is_deceased") }
            afterFlush { _, _ -> ids.add(sink.lastInsertId) }
        }
        check(listOf<Any?>(1L, 2L), ids)
        val rows = db.rows("clients", "id")
        check(listOf("Ada", "Grace"), rows.map { it["name"] })
        check(listOf(java.time.LocalDate.of(2015, 12, 10), java.time.LocalDate.of(2006, 12, 9)), rows.map { normalize(it["dob"]) })
        check(listOf(false, true), rows.map { it["is_deceased"] })
    }

    @Test
    fun aStreamingReadAndWriteTransactionOnOneConnection() {
        db.execute("create table people_copy (id integer, first_name text)")
        val copied = mutableListOf<Collector>()
        migrate(JooqSource.table(db, "people"), MultiSink("copy" to InsertSink(db, "people_copy"), "mirror" to copied.asSink())) {
            take("id") { putBury("copy", "id"); putBury("mirror", "id") }
            take("first_name") { putBury("copy", "first_name") }
        }
        check(3, db.count("people_copy"))
        check(3, copied.size)
        assertTrue(connection.autoCommit)
    }
}
