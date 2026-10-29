package micdrop

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceTest {
    @Test
    fun aListOfMapsIsASource() {
        val source = listOf(mapOf("a" to 1), mapOf("a" to 2)).asSource()
        val entries = source.entries().toList()
        check(listOf(0, 1), entries.map { it.index })
        check(listOf(1, 2), entries.map { it.record["a"] })
    }

    @Test
    fun aSequenceIsASource() {
        val entries = sequenceOf(mapOf("a" to 1)).asSource().entries().toList()
        check(1, entries.size)
    }

    @Test
    fun aMapIsASourceIndexedByKey() {
        val entries = mapOf("x" to mapOf("a" to 1), "y" to mapOf("a" to 2)).asSource().entries().toList()
        check(listOf("x", "y"), entries.map { it.index })
    }

    @Test
    fun listsCanBeRecordsToo() {
        val entries = listOf(listOf("a", "b")).asSource().entries().toList()
        check("b", entries.single().record[1])
    }

    @Test
    fun entriesAreLazy() {
        var pulled = 0
        val source = generateSequence(0) { it + 1 }.map { pulled++; mapOf("a" to it) }.asSource()
        source.entries().take(3).toList()
        check(3, pulled)
    }

    @Test
    fun unsupportedRecordTypesFail() {
        assertFailsWith<PipelineError> { listOf(5).asSource().entries().toList() }
    }

    @Test
    fun nullRecordsFail() {
        assertFailsWith<SourceError> { listOf(null).asSource().entries().toList() }
    }

    @Test
    fun customRecordTypesCanBeRegistered() {
        class Person(val name: String)
        Record.registerAdapter { value ->
            (value as? Person)?.let {
                object : Record {
                    override val raw = it
                    override fun contains(key: Any) = key == "name"
                    override fun get(key: Any) = if (key == "name") it.name else null
                }
            }
        }
        val out = runMigration(listOf(Person("Ada") as Any).map { mapOf("p" to it) }) {
            take("p").take("name").put("name")
        }
        check(listOf(mapOf("name" to "Ada")), out)
    }
}

class RecordTest {
    @Test
    fun mapRecord() {
        val record = Record.of(mapOf("a" to 1, "n" to null))
        assertTrue("a" in record)
        assertTrue("n" in record)
        assertTrue("zzz" !in record)
        check(1, record["a"])
        assertNull(record["n"])
        assertNull(record["zzz"])
        check(1, record.fetch("a"))
        assertNull(record.fetch("n"))
        assertFailsWith<PipelineError> { record.fetch("zzz") }
    }

    @Test
    fun listRecord() {
        val record = Record.of(listOf("x", "y"))
        assertTrue(1 in record)
        assertTrue(2 !in record)
        assertTrue("1" !in record)
        check("y", record[1])
        assertNull(record[5])
    }

    @Test
    fun matchRecord() {
        val record = Record.of(Regex("(?<word>\\w+)-(\\d+)").find("abc-12")!!)
        check("abc-12", record[0])
        check("abc", record["word"])
        check("12", record[2])
        assertTrue("word" in record)
        assertTrue("nope" !in record)
        assertTrue(3 !in record)
    }

    @Test
    fun digFollowsNestedRecords() {
        val record = Record.of(mapOf("a" to mapOf("b" to listOf(10, 20)), "n" to null))
        check(20, record.dig(arrayOf<Any>("a", "b", 1), strict = true))
        assertNull(record.dig(arrayOf("n", "b"), strict = true))
        assertFailsWith<PipelineError> { record.dig(arrayOf("a", "zzz"), strict = true) }
        assertNull(record.dig(arrayOf("a", "zzz", "deeper"), strict = false))
    }

    @Test
    fun aRecordIsItsOwnAdapter() {
        val record = Record.of(mapOf("a" to 1))
        check(record, Record.of(record))
    }
}

class SinkTest {
    @Test
    fun aCollectionIsASink() {
        val out = mutableListOf<Map<Any?, Any?>>()
        val sink = out.asSink()
        val collector = sink.makeCollector()
        collector["a"] = 1
        sink.write(collector)
        check(listOf(mapOf("a" to 1)), out)
    }

    @Test
    fun eachCollectorIsFresh() {
        val sink = mutableListOf<Collector>().asSink()
        assertTrue(sink.makeCollector() !== sink.makeCollector())
    }

    @Test
    fun customSinksCanSupplyTheirOwnCollector() {
        val written = mutableListOf<Collector>()
        val sink = object : Sink {
            override fun makeCollector(): Collector = linkedMapOf("preset" to 1)
            override fun write(collector: Collector) {
                written.add(collector)
            }
        }
        migrate(listOf(mapOf("a" to 1)).asSource(), sink) { take("a").put("A") }
        check(listOf(mapOf("preset" to 1, "A" to 1)), written)
    }
}

class MultiSinkTest {
    private val one = mutableListOf<Map<Any?, Any?>>()
    private val two = mutableListOf<Map<Any?, Any?>>()
    private val sink = MultiSink("one" to one.asSink(), "two" to two.asSink())

    @Test
    fun makesACollectorPerSubSink() {
        val collector = sink.makeCollector()
        check(setOf<Any?>("one", "two"), collector.keys)
        assertTrue(collector["one"] is MutableMap<*, *>)
    }

    @Test
    fun sortsIntoSeparateSinks() {
        migrate(listOf(mapOf("first" to 1, "second" to 2), mapOf("first" to 3, "second" to 4)).asSource(), sink) {
            take("first") { putBury("one", "value") }
            take("second") { putBury("two", "value") }
        }
        check(listOf(mapOf("value" to 1), mapOf("value" to 3)), one)
        check(listOf(mapOf("value" to 2), mapOf("value" to 4)), two)
    }

    @Test
    fun emptySubCollectorsAreIgnored() {
        migrate(listOf(mapOf("first" to 1)).asSource(), sink) {
            take("first") { putBury("one", "value") }
        }
        check(1, one.size)
        check(0, two.size)
    }

    @Test
    fun emptySubCollectorsCanBeKept() {
        val keeping = MultiSink(mapOf("one" to one.asSink()), ignoreEmptySubs = false)
        keeping.write(keeping.makeCollector())
        check(listOf(emptyMap<Any?, Any?>()), one)
    }

    @Test
    fun closesAllSubSinks() {
        val closed = mutableListOf<String>()
        fun closing(name: String) = object : Sink {
            override fun write(collector: Collector) {}
            override fun close() { closed.add(name) }
        }
        MultiSink("a" to closing("a"), "b" to closing("b")).close()
        check(listOf("a", "b"), closed)
    }
}
