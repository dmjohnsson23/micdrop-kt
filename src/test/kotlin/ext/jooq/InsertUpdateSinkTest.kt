package org.example.ext.jooq

import org.example.RootRecordContext
import org.example.SinkError
import org.example.asSource
import org.example.check
import org.example.migrate
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InsertUpdateSinkTest : InMemoryDatabaseTest() {
    private val columns = listOf("id", "title", "first_name", "last_name", "status")

    private fun person(id: Int, title: String, first: String, last: String, status: String) =
        mapOf("id" to id, "title" to title, "first_name" to first, "last_name" to last, "status" to status)

    private fun RootRecordContext.takeAll() {
        for (column in columns) take(column).put(column)
    }

    private fun people() = db.rows("people", "id").map { row -> row.filterKeys { it in columns } }

    private fun row(id: Int) = db.rows("people").single { it["id"] == id }

    private fun upsert(
        record: Map<String, Any?>,
        sink: InsertUpdateSink = InsertUpdateSink(db, "people", listOf("id")),
    ) = migrate(listOf(record).asSource(), sink) { for (key in record.keys) take(key).put(key) }

    // region basics

    @Test
    fun updatesExistingRowsAndInsertsNewOnes() {
        val source = listOf(
            person(1, "Dread Pirate", "", "Roberts", "mostly dead"),
            person(2, "", "Inigo", "Montoya", "wounded?"),
            person(3, "Princess", "Buttercup", "", "alive"),
            person(4, "Prince", "", "Humperdink", "alive"),
        )
        val sink = InsertUpdateSink(db, "people", listOf("id"), idColumn = "id")
        val insertIds = mutableListOf<Any?>()
        var insertCount = 0

        migrate(source.asSource(), sink) {
            takeAll()
            afterFlush { _, _ ->
                insertIds.add(sink.lastInsertId)
                if (sink.wasInsert == true) insertCount++
            }
        }

        check(listOf(null, null, null, 4), insertIds)
        check(1, insertCount)
        check(source, people())
    }

    @Test
    fun wasInsertIsNullBeforeAnyRecord() {
        assertNull(InsertUpdateSink(db, "people", listOf("id")).wasInsert)
    }

    @Test
    fun updatesOnlyTheColumnsThatChange() {
        // Setting just the status leaves everything else alone
        upsert(mapOf("id" to 2, "status" to "dead"))
        check("dead", row(2)["status"])
        check("Inigo", row(2)["first_name"])
        check("Montoya", row(2)["last_name"])
        check(3, db.count("people"))
    }

    @Test
    fun matchesOnCompositeKeys() {
        val sink = InsertUpdateSink(db, "inventory", listOf("person", "asset_type"))
        migrate(
            listOf(
                mapOf("person" to 1, "asset_type" to 3, "count" to 5),
                mapOf("person" to 1, "asset_type" to 1, "count" to 2),
            ).asSource(),
            sink,
        ) {
            for (key in listOf("person", "asset_type", "count")) take(key).put(key)
        }
        check(5, db.rows("inventory").single { it["person"] == 1 && it["asset_type"] == 3 }["count"])
        check(2, db.rows("inventory").single { it["person"] == 1 && it["asset_type"] == 1 }["count"])
        check(5, db.count("inventory"))
    }

    @Test
    fun keysThatMatchSeveralRowsAreASinkError() {
        val sink = InsertUpdateSink(db, "inventory", listOf("person"))
        val error = assertFailsWith<SinkError> {
            migrate(listOf(mapOf("person" to 1, "count" to 5)).asSource(), sink) {
                take("person").put("person")
                take("count").put("count")
            }
        }
        assertTrue("not unique" in error.message!!, error.message)
        check(4, db.count("inventory"))
    }

    // endregion

    // region null keys

    @Test
    fun aNullKeyCannotMatchSoItInserts() {
        db.execute("insert into people (id, first_name, status) values (10, 'Anon', null)")
        val sink = InsertUpdateSink(db, "people", listOf("status"))
        upsert(mapOf("status" to null, "first_name" to "Another"), sink)
        check(true, sink.wasInsert)
        check(5, db.count("people"))
    }

    @Test
    fun aNullKeyCanMatchNullsIfAsked() {
        db.execute("insert into people (id, first_name, status) values (10, 'Anon', null)")
        val sink = InsertUpdateSink(db, "people", listOf("status"), matchNullKeys = true)
        upsert(mapOf("status" to null, "first_name" to "Renamed"), sink)
        check(false, sink.wasInsert)
        check("Renamed", row(10)["first_name"])
        check(4, db.count("people"))
    }

    @Test
    fun aMissingKeyAlsoInserts() {
        val sink = InsertUpdateSink(db, "people", listOf("id"))
        upsert(mapOf("first_name" to "New"), sink)
        check(true, sink.wasInsert)
        check(4, db.count("people"))
    }

    // endregion

    // region update actions

    private fun seed() {
        db.execute("update people set notes = 'old', visits = 5, title = null where id = 1")
        db.execute("update people set notes = null, visits = null where id = 2")
    }

    @Test
    fun coalesceIsTheDefault() {
        seed()
        upsert(mapOf("id" to 1, "first_name" to "Westley", "last_name" to null, "notes" to null))
        check("Westley", row(1)["first_name"])
        check("", row(1)["last_name"]) // null didn't overwrite
        check("old", row(1)["notes"])
    }

    @Test
    fun overwriteNullsOnlyFillsGaps() {
        seed()
        val sink = InsertUpdateSink(db, "people", listOf("id"), defaultUpdateAction = UpdateAction.OverwriteNulls)
        upsert(mapOf("id" to 1, "title" to "Dread Pirate", "first_name" to "Westley"), sink)
        check("Dread Pirate", row(1)["title"]) // was null
        check("Wesley", row(1)["first_name"]) // was set
    }

    @Test
    fun alwaysOverwriteEvenWithNull() {
        seed()
        val sink = InsertUpdateSink(db, "people", listOf("id"), defaultUpdateAction = UpdateAction.AlwaysOverwrite)
        upsert(mapOf("id" to 1, "first_name" to null, "notes" to "new"), sink)
        check(null, row(1)["first_name"])
        check("new", row(1)["notes"])
    }

    @Test
    fun keepExistingChangesNothing() {
        seed()
        val sink = InsertUpdateSink(db, "people", listOf("id"), defaultUpdateAction = UpdateAction.KeepExisting)
        upsert(mapOf("id" to 1, "first_name" to "Westley", "notes" to "new"), sink)
        check("Wesley", row(1)["first_name"])
        check("old", row(1)["notes"])
        check(false, sink.wasInsert)
    }

    @Test
    fun actionsCanBeChosenPerColumn() {
        seed()
        val sink = InsertUpdateSink(
            db, "people", listOf("id"),
            updateActions = mapOf(
                "notes" to UpdateAction.Append,
                "visits" to UpdateAction.Add,
                "status" to UpdateAction.KeepExisting,
                "title" to UpdateAction.OverwriteNulls,
            ),
        )
        upsert(
            mapOf(
                "id" to 1, "notes" to "added", "visits" to 2, "status" to "dead", "title" to "Pirate",
                "first_name" to "Westley",
            ),
            sink,
        )
        check("old added", row(1)["notes"])
        check(7, row(1)["visits"])
        check("alive", row(1)["status"])
        check("Pirate", row(1)["title"])
        check("Westley", row(1)["first_name"]) // default: coalesce
    }

    @Test
    fun appendAndAddWorkOnEmptyColumns() {
        seed()
        val sink = InsertUpdateSink(
            db, "people", listOf("id"),
            updateActions = mapOf("notes" to UpdateAction.AppendLine, "visits" to UpdateAction.Add),
        )
        upsert(mapOf("id" to 2, "notes" to "first", "visits" to 3), sink)
        check("first", row(2)["notes"])
        check(3, row(2)["visits"])
        upsert(mapOf("id" to 2, "notes" to "second", "visits" to 4), sink)
        check("first\nsecond", row(2)["notes"])
        check(7, row(2)["visits"])
    }

    // endregion

    // region merge

    @Test
    fun mergeRules() {
        check("new", merge(UpdateAction.Coalesce, "old", "new"))
        check("old", merge(UpdateAction.Coalesce, "old", null))
        check("new", merge(UpdateAction.Coalesce, null, "new"))

        check("old", merge(UpdateAction.OverwriteNulls, "old", "new"))
        check("new", merge(UpdateAction.OverwriteNulls, null, "new"))

        check(null, merge(UpdateAction.AlwaysOverwrite, "old", null))
        check("new", merge(UpdateAction.AlwaysOverwrite, "old", "new"))

        check("old", merge(UpdateAction.KeepExisting, "old", "new"))

        check("old new", merge(UpdateAction.Append, "old", "new"))
        check("old\nnew", merge(UpdateAction.AppendLine, "old", "new"))
        check("new old", merge(UpdateAction.Prepend, "old", "new"))
        check("new\nold", merge(UpdateAction.PrependLine, "old", "new"))
    }

    @Test
    fun combiningTextIgnoresNothing() {
        for (action in listOf(UpdateAction.Append, UpdateAction.AppendLine, UpdateAction.Prepend, UpdateAction.PrependLine)) {
            check("x", merge(action, null, "x"))
            check("x", merge(action, "", "x"))
            check("x", merge(action, "x", null))
            check("x", merge(action, "x", ""))
        }
    }

    @Test
    fun addingNumbers() {
        check(3L, merge(UpdateAction.Add, 1, 2))
        check(3L, merge(UpdateAction.Add, 1L, 2))
        check(3.5, merge(UpdateAction.Add, 1, 2.5))
        check(0, BigDecimal("3.75").compareTo(merge(UpdateAction.Add, BigDecimal("1.25"), BigDecimal("2.5")) as BigDecimal))
        check(5, merge(UpdateAction.Add, null, 5))
        check(5, merge(UpdateAction.Add, 5, null))
    }

    @Test
    fun addingNonNumbersIsASinkError() {
        assertFailsWith<SinkError> { merge(UpdateAction.Add, "a", 1) }
        assertFailsWith<SinkError> { merge(UpdateAction.Add, 1, "a") }
    }

    // endregion
}
