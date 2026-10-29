package micdrop.ext.jooq

import micdrop.Collector
import micdrop.SourceError
import micdrop.asSink
import micdrop.check
import micdrop.migrate
import java.sql.Time
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JooqSourceTest : InMemoryDatabaseTest() {
    private val wesley = mapOf("title" to "Farm Boy", "first_name" to "Wesley", "last_name" to "", "status" to "alive")
    private val inigo = mapOf("title" to "", "first_name" to "Inigo", "last_name" to "Montoya", "status" to "alive")
    private val buttercup = mapOf("title" to "", "first_name" to "Buttercup", "last_name" to "", "status" to "alive")

    private fun migratePeople(source: JooqSource): List<Collector> {
        val sink = mutableListOf<Collector>()
        migrate(source, sink.asSink()) {
            take("first_name").put("first_name")
            take("last_name").put("last_name")
            take("title").put("title")
            take("status").put("status")
        }
        return sink
    }

    @Test
    fun aTableIsASource() {
        check(listOf(wesley, inigo, buttercup), migratePeople(JooqSource.table(db, "people")))
    }

    @Test
    fun anyQueryIsASource() {
        val query = db.selectFrom(table("people")).where(column("first_name").ne("Inigo")).orderBy(column("id").desc())
        check(listOf(buttercup, wesley), migratePeople(JooqSource(query)))
    }

    @Test
    fun plainSqlIsASource() {
        val source = JooqSource(db.resultQuery("select * from people where id > ? order by id", 1))
        check(listOf(inigo, buttercup), migratePeople(source))
    }

    @Test
    fun joinsAreSources() {
        val query = db.select(column("first_name"), column("label"))
            .from(table("inventory"))
            .join(table("people")).on(column("inventory", "person").eq(column("people", "id")))
            .join(table("asset_type")).on(column("inventory", "asset_type").eq(column("asset_type", "id")))
            .where(column("count").gt(0))
            .orderBy(column("people", "id"), column("asset_type", "id"))
        val rows = JooqSource(query).entries().map { it.record.raw }.toList()
        check(
            listOf(
                mapOf("first_name" to "Wesley", "label" to "Iocane Powder"),
                mapOf("first_name" to "Wesley", "label" to "True Love"),
                mapOf("first_name" to "Inigo", "label" to "Wheelbarrow"),
            ),
            rows,
        )
    }

    @Test
    fun aTableWithASchemaPrefixIsASource() {
        check(3, JooqSource.table(db, "main.people").entries().count())
    }

    @Test
    fun entriesAreIndexedFromZero() {
        check(listOf(0, 1, 2), JooqSource.table(db, "people").entries().map { it.index }.toList())
    }

    @Test
    fun columnsAreInQueryOrder() {
        val row = JooqSource.table(db, "asset_type").entries().first().record.raw as Map<*, *>
        check(listOf("id", "label"), row.keys.toList())
    }

    @Test
    fun anEmptyResultHasNoRecords() {
        val source = JooqSource(db.selectFrom(table("people")).where(column("id").lt(0)))
        check(0, source.entries().count())
    }

    @Test
    fun readsLazily() {
        val first = JooqSource(db.selectFrom(table("people")).orderBy(column("id")), fetchSize = 1).entries().first()
        check("Wesley", (first.record.raw as Map<*, *>)["first_name"])
    }

    @Test
    fun aMigrationCanStopEarlyAndClose() {
        val sink = mutableListOf<Collector>()
        migrate(JooqSource(db.selectFrom(table("people")).orderBy(column("id"))), sink.asSink()) {
            take("id") { stopIf(2); put("id") }
        }
        check(listOf<Collector>(linkedMapOf("id" to 1)), sink)
        // and the connection is still usable
        check(3, db.count("people"))
    }

    @Test
    fun aTableCanBeCopiedToAnotherOnTheSameConnection() {
        db.execute("create table people_copy (id integer, first_name text)")
        migrate(JooqSource.table(db, "people"), InsertSink(db, "people_copy")) {
            take("id").put("id")
            take("first_name").put("first_name")
        }
        check(
            listOf(mapOf("id" to 1, "first_name" to "Wesley"), mapOf("id" to 2, "first_name" to "Inigo"),
                mapOf("id" to 3, "first_name" to "Buttercup")),
            db.rows("people_copy", "id"),
        )
    }

    @Test
    fun aBadQueryIsASourceError() {
        val error = assertFailsWith<SourceError> { JooqSource.table(db, "no_such_table").entries().toList() }
        assertTrue("Query failed" in error.message!!, error.message)
    }

    @Test
    fun valuesKeepTheirTypes() {
        db.execute("create table typed (n integer, f real, s text, d date, dt datetime, empty text)")
        db.execute("insert into typed values (1, 1.5, 'x', '2024-11-14', '2024-11-14 04:55:00', null)")
        val row = JooqSource.table(db, "typed").entries().single().record.raw as Map<*, *>
        check(1, (row["n"] as Number).toInt())
        check(1.5, (row["f"] as Number).toDouble())
        check("x", row["s"])
        check(null, row["empty"])
        check(true, row.containsKey("empty"))
    }

    // region normalizing legacy date and time types

    @Test
    fun legacySqlTypesBecomeJavaTime() {
        check(LocalDateTime.of(2024, 11, 14, 4, 55), normalize(Timestamp.valueOf("2024-11-14 04:55:00")))
        check(LocalDate.of(2024, 11, 14), normalize(java.sql.Date.valueOf("2024-11-14")))
        check(LocalTime.of(4, 55), normalize(Time.valueOf("04:55:00")))
    }

    @Test
    fun otherValuesAreUntouched() {
        check("x", normalize("x"))
        check(5, normalize(5))
        check(null, normalize(null))
        val bytes = byteArrayOf(1)
        check(true, normalize(bytes) === bytes)
    }

    // endregion
}
