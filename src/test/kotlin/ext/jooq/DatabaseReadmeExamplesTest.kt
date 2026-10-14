package org.example.ext.jooq

import org.example.Collector
import org.example.MultiSink
import org.example.Sink
import org.example.asSource
import org.example.check
import org.example.migrate
import org.jooq.impl.DSL
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Runs the database examples from the README and docs */
class DatabaseReadmeExamplesTest : InMemoryDatabaseTest() {
    @Test
    fun readingAQueryAndWritingToATable() {
        db.execute("create table people_archive (first_name text, last_name text)")
        db.execute("update people set status = 'dead' where id = 3")

        val alive = db.selectFrom(DSL.table(DSL.name("people"))).where(DSL.field(DSL.name("status")).eq("alive"))
        migrate(JooqSource(alive), InsertSink(db, "people_archive")) {
            take("first_name").put("first_name")
            take("last_name").put("last_name")
        }
        check(
            listOf(mapOf("first_name" to "Wesley", "last_name" to ""), mapOf("first_name" to "Inigo", "last_name" to "Montoya")),
            db.rows("people_archive"),
        )
    }

    @Test
    fun updateActions() {
        db.execute("create table subscribers (email text, notes text, visits integer, created text)")
        db.execute("insert into subscribers values ('a@x.com', 'first', 2, 'jan')")
        val sink = InsertUpdateSink(
            db, "subscribers", keys = listOf("email"),
            updateActions = mapOf(
                "notes" to UpdateAction.AppendLine,
                "visits" to UpdateAction.Add,
                "created" to UpdateAction.KeepExisting,
            ),
        )
        val source = listOf(
            mapOf("email" to "a@x.com", "notes" to "second", "visits" to 3, "created" to "feb"),
            mapOf("email" to "b@x.com", "notes" to "new", "visits" to 1, "created" to "feb"),
        )
        migrate(source.asSource(), sink) { for (key in listOf("email", "notes", "visits", "created")) take(key).put(key) }

        check(
            listOf(
                mapOf("email" to "a@x.com", "notes" to "first\nsecond", "visits" to 5, "created" to "jan"),
                mapOf("email" to "b@x.com", "notes" to "new", "visits" to 1, "created" to "feb"),
            ),
            db.rows("subscribers", "email"),
        )
    }

    @Test
    fun reportingNewIds() {
        db.execute("create table clients (id integer primary key autoincrement, name text)")
        val sink = InsertSink(db, "clients")
        val messages = mutableListOf<String>()
        migrate(listOf(mapOf("Name" to "Ada"), mapOf("Name" to "Grace")).asSource(), sink) {
            take("Name").put("name")
            afterFlush { _, _ -> messages.add("Created client ${sink.lastInsertId}") }
        }
        check(listOf("Created client 1", "Created client 2"), messages)
    }

    private fun countries() {
        db.execute("create table countries (id integer primary key, name text)")
        db.execute("insert into countries values (1, 'France'), (2, 'Japan')")
        db.execute("create table visitors (country_id integer)")
    }

    @Test
    fun lookingUpValues() {
        countries()
        migrate(listOf(mapOf("country_name" to "Japan"), mapOf("country_name" to "France")).asSource(), InsertSink(db, "visitors")) {
            take("country_name") {
                dbLookup(db, "countries", keyColumn = "name", valueColumn = "id")
                put("country_id")
            }
        }
        check(listOf(2, 1), db.rows("visitors").map { it["country_id"] })
    }

    @Test
    fun lookingUpValuesWithACache() {
        countries()
        val lookup = DbLookup(db, "countries", "name", "id", cache = true)
        migrate(listOf(mapOf("country" to "Japan"), mapOf("country" to "Japan")).asSource(), InsertSink(db, "visitors")) {
            take("country") { dbLookup(lookup); put("country_id") }
        }
        check(listOf(2, 2), db.rows("visitors").map { it["country_id"] })
    }

    @Test
    fun lookingUpValuesInAMap() {
        countries()
        val byName = db.selectFrom(DSL.table(DSL.name("countries"))).fetchMaps().associate { it["name"] to it["id"] }
        migrate(listOf(mapOf("country" to "France")).asSource(), InsertSink(db, "visitors")) {
            take("country") { lookup(byName); put("country_id") }
        }
        check(listOf(1), db.rows("visitors").map { it["country_id"] })
    }

    @Test
    fun allOrNothing() {
        db.execute("create table newcomers (name text, age integer)")
        val source = listOf(mapOf("name" to "Ada", "age" to "36"), mapOf("name" to "Bob", "age" to "oops"))
        assertFailsWith<NumberFormatException> {
            migrate(source.asSource(), InsertSink(db, "newcomers")) {
                take("name").put("name")
                take("age").parseInt().put("age")
            }
        }
        check(0, db.count("newcomers"))
    }

    @Test
    fun severalTablesAtOnce() {
        db.execute("create table phones (person integer, number text)")
        val session = JooqSession(db)
        val sink = MultiSink("person" to InsertSink(session, "people"), "phone" to InsertSink(session, "phones"))
        migrate(listOf(mapOf("id" to 20, "phone" to "555")).asSource(), sink) {
            take("id") { putBury("person", "id"); putBury("phone", "person") }
            take("phone").putBury("phone", "number")
        }
        check(4, db.count("people"))
        check(1, db.count("phones"))
    }

    @Test
    fun aSinkWithItsOwnCommitAndRollback() {
        class Target { val rows = mutableListOf<Collector>(); fun insertAll(more: List<Collector>) { rows.addAll(more) } }

        class BatchingSink(private val target: Target) : Sink {
            private val pending = mutableListOf<Collector>()
            override fun write(collector: Collector) { pending.add(collector) }
            override fun commit() { target.insertAll(pending); pending.clear() }
            override fun rollback(cause: Throwable) { pending.clear() }
        }

        val good = Target()
        migrate(listOf(mapOf("a" to 1), mapOf("a" to 2)).asSource(), BatchingSink(good)) { take("a").put("A") }
        check(2, good.rows.size)

        val bad = Target()
        assertFailsWith<org.example.PipelineError> {
            migrate(listOf(mapOf("a" to 1), mapOf("b" to 2)).asSource(), BatchingSink(bad)) { take("a").put("A") }
        }
        check(0, bad.rows.size)
    }
}
