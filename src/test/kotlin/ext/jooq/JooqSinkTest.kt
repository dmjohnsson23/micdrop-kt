package micdrop.ext.jooq

import micdrop.RootRecordContext
import micdrop.SinkError
import micdrop.asSource
import micdrop.check
import micdrop.migrate
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val personColumns = listOf("id", "title", "first_name", "last_name", "status")

private fun person(id: Int, title: String, first: String, last: String, status: String) =
    mapOf("id" to id, "title" to title, "first_name" to first, "last_name" to last, "status" to status)

private fun RootRecordContext.takeAllPersonColumns() {
    for (column in personColumns) take(column).put(column)
}

private fun InMemoryDatabaseTest.people() = db.rows("people", "id").map { row -> row.filterKeys { it in personColumns } }

class InsertSinkTest : InMemoryDatabaseTest() {
    @Test
    fun insertsNewRows() {
        val prince = person(4, "Prince", "", "Humperdink", "alive")
        val sink = InsertSink(db, "people", idColumn = "id")
        migrate(listOf(prince).asSource(), sink) { takeAllPersonColumns() }

        check(4, sink.lastInsertId)
        check(prince, people().single { it["id"] == 4 })
        check(4, db.count("people"))
    }

    @Test
    fun reportsGeneratedIds() {
        val sink = InsertSink(db, "people")
        val ids = mutableListOf<Any?>()
        migrate(listOf(mapOf("first_name" to "A"), mapOf("first_name" to "B")).asSource(), sink) {
            afterFlush { _, _ -> ids.add(sink.lastInsertId) }
            take("first_name").put("first_name")
        }
        check(listOf<Any?>(4L, 5L), ids)
    }

    @Test
    fun noInsertsMeansNoId() {
        assertNull(InsertSink(db, "people").lastInsertId)
    }

    @Test
    fun writesEveryKindOfValue() {
        db.execute("create table typed (n numeric, d date, dt datetime, b boolean, s text, empty text, x blob)")
        migrate(listOf(mapOf("v" to 1)).asSource(), InsertSink(db, "typed")) {
            put("n", BigDecimal("1.50"))
            put("d", LocalDate.of(2024, 11, 14))
            put("dt", LocalDateTime.of(2024, 11, 14, 4, 55))
            put("b", true)
            put("s", "it's \"quoted\"")
            put("empty", null)
            put("x", byteArrayOf(1, 2))
        }
        val row = JooqSource.table(db, "typed").entries().single().record.raw as Map<*, *>
        check(0, BigDecimal("1.5").compareTo(BigDecimal(row["n"].toString())))
        check(LocalDate.of(2024, 11, 14), row["d"])
        check(LocalDateTime.of(2024, 11, 14, 4, 55), row["dt"])
        check(true, row["b"])
        check("it's \"quoted\"", row["s"])
        check(null, row["empty"])
        check(listOf<Byte>(1, 2), (row["x"] as ByteArray).toList())
    }

    @Test
    fun quotesUnusualColumnNames() {
        db.execute("""create table odd ("Deceased?" integer, "select" text)""")
        migrate(listOf(mapOf("v" to 1)).asSource(), InsertSink(db, "odd")) {
            put("Deceased?", 1)
            put("select", "x")
        }
        check(1, db.count("odd"))
    }

    @Test
    fun anUnknownColumnIsASinkError() {
        val error = assertFailsWith<SinkError> {
            migrate(listOf(mapOf("v" to 1)).asSource(), InsertSink(db, "people")) { put("no_such_column", 1) }
        }
        assertTrue("people" in error.message!!, error.message)
    }

    @Test
    fun anUnknownTableIsASinkError() {
        assertFailsWith<SinkError> {
            migrate(listOf(mapOf("v" to 1)).asSource(), InsertSink(db, "nowhere")) { put("a", 1) }
        }
    }

    @Test
    fun aConstraintViolationIsASinkError() {
        assertFailsWith<SinkError> {
            migrate(listOf(mapOf("id" to 1)).asSource(), InsertSink(db, "people")) { take("id").put("id") }
        }
    }

    @Test
    fun nothingPutMeansNoInsert() {
        migrate(listOf(mapOf("v" to 1)).asSource(), InsertSink(db, "people")) { take("v") }
        check(3, db.count("people"))
    }
}

class UpdateSinkTest : InMemoryDatabaseTest() {
    private val updated = listOf(
        person(1, "Dread Pirate", "", "Roberts", "mostly dead"),
        person(2, "", "Inigo", "Montoya", "wounded?"),
        person(3, "Princess", "Buttercup", "", "alive"),
    )

    @Test
    fun updatesExistingRows() {
        migrate(updated.asSource(), UpdateSink(db, "people", listOf("id"))) { takeAllPersonColumns() }
        check(updated, people())
    }

    @Test
    fun onlyTouchesTheColumnsPut() {
        migrate(listOf(mapOf("id" to 2, "status" to "dead")).asSource(), UpdateSink(db, "people", listOf("id"))) {
            take("id").put("id")
            take("status").put("status")
        }
        check(
            listOf("alive", "dead", "alive"),
            people().map { it["status"] },
        )
        check("Inigo", people()[1]["first_name"])
    }

    @Test
    fun matchesOnCompositeKeys() {
        val sink = UpdateSink(db, "inventory", listOf("person", "asset_type"))
        migrate(listOf(mapOf("person" to 1, "asset_type" to 3, "count" to 9)).asSource(), sink) {
            take("person").put("person")
            take("asset_type").put("asset_type")
            take("count").put("count")
        }
        check(1, sink.lastRowsUpdated)
        check(9, db.rows("inventory").single { it["person"] == 1 && it["asset_type"] == 3 }["count"])
        check(1, db.rows("inventory").single { it["person"] == 1 && it["asset_type"] == 4 }["count"])
    }

    @Test
    fun updatesEveryMatchingRow() {
        val sink = UpdateSink(db, "inventory", listOf("person"))
        migrate(listOf(mapOf("person" to 1, "count" to 7)).asSource(), sink) {
            take("person").put("person")
            take("count").put("count")
        }
        check(2, sink.lastRowsUpdated)
    }

    @Test
    fun noMatchDoesNothing() {
        val sink = UpdateSink(db, "people", listOf("id"))
        migrate(listOf(mapOf("id" to 99, "status" to "dead")).asSource(), sink) {
            take("id").put("id")
            take("status").put("status")
        }
        check(0, sink.lastRowsUpdated)
        check(3, db.count("people"))
        check(listOf("alive", "alive", "alive"), people().map { it["status"] })
    }

    @Test
    fun aNullKeyIsASinkError() {
        val error = assertFailsWith<SinkError> {
            migrate(listOf(mapOf("id" to null, "status" to "dead")).asSource(), UpdateSink(db, "people", listOf("id"))) {
                take("id").put("id")
                take("status").put("status")
            }
        }
        assertTrue("key" in error.message!!, error.message)
    }

    @Test
    fun aMissingKeyIsASinkError() {
        assertFailsWith<SinkError> {
            migrate(listOf(mapOf("status" to "dead")).asSource(), UpdateSink(db, "people", listOf("id"))) {
                take("status").put("status")
            }
        }
    }

    @Test
    fun nullKeysCanMatchNulls() {
        db.execute("insert into people (id, first_name, status) values (10, 'Anon', null)")
        val sink = UpdateSink(db, "people", listOf("status"), matchNullKeys = true)
        migrate(listOf(mapOf("status" to null, "notes" to "was unknown")).asSource(), sink) {
            take("status").put("status")
            take("notes").put("notes")
        }
        check(1, sink.lastRowsUpdated)
        check("was unknown", db.rows("people").single { it["id"] == 10 }["notes"])
    }

    @Test
    fun keysAreRequired() {
        assertFailsWith<IllegalArgumentException> { UpdateSink(db, "people", emptyList()) }
    }
}
