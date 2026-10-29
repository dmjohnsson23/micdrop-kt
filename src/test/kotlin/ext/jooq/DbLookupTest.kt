package micdrop.ext.jooq

import micdrop.Collector
import micdrop.asSink
import micdrop.check
import micdrop.PipelineError
import micdrop.migrate
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DbLookupTest : InMemoryDatabaseTest() {
    private val expected = listOf(
        mapOf("name" to "Wesley", "asset" to "True Love", "count" to 1),
        mapOf("name" to "Wesley", "asset" to "Iocane Powder", "count" to 1),
        mapOf("name" to "Inigo", "asset" to "Wheelbarrow", "count" to 1),
        mapOf("name" to "Buttercup", "asset" to "True Love", "count" to 0),
    )

    private fun inventory(block: micdrop.RootRecordContext.() -> Unit): List<Map<Any?, Any?>> {
        val sink = mutableListOf<Collector>()
        migrate(JooqSource.table(db, "inventory"), sink.asSink(), block)
        return sink
    }

    private fun lookupOne(key: Any?, build: micdrop.ItemContext.() -> Unit): Any? {
        val sink = mutableListOf<Collector>()
        migrate(listOf(mapOf("k" to key)), sink) { take("k") { build(); put("v") } }
        return sink.single()["v"]
    }

    @Test
    fun lookupFromAQueryAsAMap() {
        val people = db.rows("people").associate { it["id"] to it["first_name"] }
        val assets = db.rows("asset_type").associate { it["id"] to it["label"] }
        val out = inventory {
            take("person") { lookup(people); put("name") }
            take("asset_type") { lookup(assets); put("asset") }
            take("count").put("count")
        }
        check(expected, out)
    }

    @Test
    fun dbLookupWithAValueColumn() {
        val out = inventory {
            take("person") { dbLookup(db, "people", "id", "first_name"); put("name") }
            take("asset_type") { dbLookup(db, "asset_type", "id", "label"); put("asset") }
            take("count").put("count")
        }
        check(expected, out)
    }

    @Test
    fun dbLookupWithoutAValueColumnGivesTheWholeRow() {
        val out = inventory {
            take("person") { dbLookup(db, "people", "id"); extract("first_name"); put("name") }
            take("asset_type") { dbLookup(db, "asset_type", "id"); extract("label"); put("asset") }
            take("count").put("count")
        }
        check(expected, out)
    }

    @Test
    fun wholeRowsAreMaps() {
        val row = lookupOne(2) { dbLookup(db, "asset_type", "id") }
        check(mapOf("id" to 2, "label" to "Wheelbarrow"), row)
    }

    @Test
    fun nullKeysAreLeftAlone() {
        assertNull(lookupOne(null) { dbLookup(db, "people", "id", "first_name") })
    }

    @Test
    fun anUnknownKeyThrows() {
        assertFailsWith<PipelineError> { lookupOne(99) { dbLookup(db, "people", "id", "first_name") } }
    }

    @Test
    fun anUnknownKeyCanBecomeNull() {
        assertNull(lookupOne(99) { pipeOrSetNull { dbLookup(db, "people", "id", "first_name") } })
    }

    @Test
    fun anUnknownKeyCanPassThrough() {
        check(99, lookupOne(99) { pipeOrRevert { dbLookup(db, "people", "id", "first_name") } })
    }

    @Test
    fun anUnknownKeyCanRunAPipeline() {
        check("Unknown", lookupOne(99) { pipeOrFallback({ dbLookup(db, "people", "id", "first_name") }, { update("Unknown") }) })
    }

    @Test
    fun aFoundNullIsNotTheSameAsNotFound() {
        db.execute("insert into people (id, first_name, last_name) values (10, 'Nameless', null)")
        // found, with a null last name: not passed through, not handled as missing
        val value = lookupOne(10) {
            pipeOrFallback({ dbLookup(db, "people", "id", "last_name") }, { update("MISSING") })
        }
        assertNull(value)
    }

    @Test
    fun theKeyColumnNeedNotBeUnique() {
        // The first match is used
        check(true, lookupOne(1) { dbLookup(db, "inventory", "person", "asset_type") } in listOf(4, 3))
    }

    // region caching

    @Test
    fun cachedLookupsRememberTheirAnswers() {
        val lookup = DbLookup(db, "people", "id", "first_name", cache = true)
        check("Wesley", lookup.find(1)?.value)
        db.execute("update people set first_name = 'Changed' where id = 1")
        check("Wesley", lookup.find(1)?.value)
    }

    @Test
    fun cachedLookupsRememberMissesToo() {
        val lookup = DbLookup(db, "people", "id", "first_name", cache = true)
        assertNull(lookup.find(99))
        db.execute("insert into people (id, first_name) values (99, 'Late')")
        assertNull(lookup.find(99))
    }

    @Test
    fun uncachedLookupsSeeChanges() {
        val lookup = DbLookup(db, "people", "id", "first_name")
        check("Wesley", lookup.find(1)?.value)
        db.execute("update people set first_name = 'Changed' where id = 1")
        check("Changed", lookup.find(1)?.value)
    }

    @Test
    fun aLookupObjectCanBeSharedByManyItems() {
        val names = DbLookup(db, "people", "id", "first_name", cache = true)
        val out = inventory { take("person") { dbLookup(names); put("name") } }
        check(listOf("Wesley", "Wesley", "Inigo", "Buttercup"), out.map { it["name"] })
    }

    // endregion
}
