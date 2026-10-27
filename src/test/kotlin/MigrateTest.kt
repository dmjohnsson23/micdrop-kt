package org.example

import kotlin.test.Test
import kotlin.test.assertFailsWith

class MigrateTest {
    private fun run(source: List<Map<String, Any?>>, block: RootRecordContext.() -> Unit) =
        runMigration(source, block)

    private val abSource = listOf(
        mapOf("a" to 1, "b" to 2),
        mapOf("a" to 3, "b" to 4),
        mapOf("a" to 5, "b" to 6),
    )

    @Test
    fun usingBlocks() {
        val out = run(abSource) {
            take("a") { update((value as Int) + 1); put("A") }
            take("b") { convert { (it as Int) * 2 }; put("B") }
        }
        check(
            listOf(mapOf("A" to 2, "B" to 4), mapOf("A" to 4, "B" to 8), mapOf("A" to 6, "B" to 12)),
            out,
        )
    }

    @Test
    fun usingApplyPipelines() {
        val plusser: Pipeline = { update((value as Int) + 1) }
        val timeser: Pipeline = { convert { (it as Int) * 2 } }
        val out = run(abSource) {
            take("a").pipe(plusser).put("A")
            take("b") { pipe(timeser); put("B") }
        }
        check(
            listOf(mapOf("A" to 2, "B" to 4), mapOf("A" to 4, "B" to 8), mapOf("A" to 6, "B" to 12)),
            out,
        )
    }

    @Test
    fun noDoubleFlush() {
        val out = run(abSource) {
            take("a").put("A")
            take("b").put("B")
            flush()
        }
        check(
            listOf(mapOf("A" to 1, "B" to 2), mapOf("A" to 3, "B" to 4), mapOf("A" to 5, "B" to 6)),
            out,
        )
    }

    @Test
    fun multiFlush() {
        val out = run(abSource) {
            for (name in listOf("a", "b")) {
                take(name).put("it")
                flush()
            }
        }
        check((1..6).map { mapOf("it" to it) }, out)
    }

    @Test
    fun indexIsTheRecordPosition() {
        val out = run(abSource) { index().put("i") }
        check(listOf(0, 1, 2).map { mapOf("i" to it) }, out)
    }

    @Test
    fun passthru() {
        check(listOf(mapOf("a" to 1, "b" to 2)), run(abSource.take(1)) { passthru("a", "b") })
    }

    @Test
    fun staticValues() {
        check(listOf(mapOf("t" to "Home")), run(abSource.take(1)) { static("Home").put("t") })
    }

    @Test
    fun nothingPutMeansNothingFlushed() {
        check(emptyList<Any?>(), run(abSource) { take("a") })
    }

    @Test
    fun sourceAndSinkAreClosed() {
        val closed = mutableListOf<String>()
        val source = object : Source {
            override fun entries() = listOf(mapOf("a" to 1)).asSource().entries()
            override fun close() { closed.add("source") }
        }
        val sink = object : Sink {
            override fun write(collector: Collector) {}
            override fun close() { closed.add("sink") }
        }
        migrate(source, sink) { take("a").put("A") }
        check(setOf("source", "sink"), closed.toSet())
    }

    @Test
    fun sourceAndSinkAreClosedOnError() {
        val closed = mutableListOf<String>()
        val sink = object : Sink {
            override fun write(collector: Collector) {}
            override fun close() { closed.add("sink") }
        }
        assertFailsWith<PipelineError> {
            migrate(abSource.asSource(), sink) { take("nope") }
        }
        check(listOf("sink"), closed)
    }

    @Test
    fun simpleTakeAndPut() {
        val out = run(listOf(mapOf("a" to 1, "b" to 2), mapOf("a" to 3, "b" to 4))) {
            take("a").put("A")
            take("b") { put("B") }
        }
        check(listOf(mapOf("A" to 1, "B" to 2), mapOf("A" to 3, "B" to 4)), out)
    }

    @Test
    fun pipelinesAndChaining() {
        val out = run(
            listOf(
                mapOf("a" to "Yes", "b" to "08/07/22", "c" to "Stuff"),
                mapOf("a" to "No", "b" to "11/12/21", "c" to null),
            )
        ) {
            take("a") { parseBoolean(); put("A") }
            take("b") { parseDate("MM/dd/yy"); formatDate("yyyy-dd-MM"); put("B") }
            take("c").default("Whatsit").put("C")
        }
        check(
            listOf(
                mapOf("A" to true, "B" to "2022-07-08", "C" to "Stuff"),
                mapOf("A" to false, "B" to "2021-12-11", "C" to "Whatsit"),
            ),
            out,
        )
    }

    @Test
    fun takeAllowsNullValuesButNotMissingKeys() {
        val out = run(listOf(mapOf("a" to null))) { take("a").put("A") }
        check(listOf(mapOf<Any?, Any?>("A" to null)), out)
        assertFailsWith<PipelineError> { run(listOf(mapOf("a" to 1))) { take("nope").put("X") } }
        check(listOf(mapOf<Any?, Any?>("X" to null)), run(listOf(mapOf("a" to 1))) { tryTake("nope").put("X") })
    }

    @Test
    fun skipAndStop() {
        val out = run(listOf(1, 0, 3, 99, 5).map { mapOf("a" to it) }) {
            take("a") { skipIf(0); stopIf(99); put("a") }
        }
        check(listOf(mapOf<Any?, Any?>("a" to 1), mapOf("a" to 3)), out)
    }

    @Test
    fun pipeOrSetNullDoesNotSwallowControlFlow() {
        val out = run(listOf(mapOf("a" to "x"), mapOf("a" to "5"))) {
            take("a").pipeOrSetNull { parseInt() }.put("A")
        }
        check(listOf(mapOf<Any?, Any?>("A" to null), mapOf("A" to 5)), out)
        val skipped = run(listOf(mapOf("a" to "x"))) {
            take("a").pipeOrSetNull { skip() }.put("A")
        }
        check(emptyList<Any?>(), skipped)
    }

    @Test
    fun destructuring() {
        val out = run(listOf(mapOf("some" to mapOf("deeply" to mapOf("nested" to mapOf("data" to "1")), "other" to "3")))) {
            take("some") {
                scope { extract("other"); put("other") }
                scope { extract("deeply"); extract("nested"); extract("data"); put("data") }
            }
            takeDig("some", "deeply", "nested", "data").put("dug")
        }
        check(listOf(mapOf<Any?, Any?>("other" to "3", "data" to "1", "dug" to "1")), out)
    }

    @Test
    fun collectList() {
        val out = run(listOf(mapOf("p" to 1, "h" to null, "w" to "555", "c" to "777"))) {
            take("p").put("person")
            collectList(take("h"), take("w"), take("c")) { compact(); join(","); put("phones") }
        }
        check(listOf(mapOf<Any?, Any?>("person" to 1, "phones" to "555,777")), out)
    }

    @Test
    fun putBury() {
        val out = run(listOf(mapOf("h" to "1", "w" to null))) {
            take("h") { putBury("phones", "home") }
            take("w") { if (value != null) putBury("phones", "work") }
            take("h") { putBury("list", null) }
            take("h") { putBury("list", null) }
        }
        check(
            listOf(mapOf("phones" to mapOf("home" to "1"), "list" to listOf("1", "1"))),
            out,
        )
    }

    @Test
    fun flushProducesMultipleRecords() {
        val out = run(listOf(mapOf("p" to 1, "h" to "H", "w" to "W"))) {
            take("p").put("id"); take("h").put("n"); flush()
            take("p").put("id"); take("w").put("n")
        }
        check(listOf(mapOf("id" to 1, "n" to "H"), mapOf("id" to 1, "n" to "W")), out)
    }

    @Test
    fun eachSubrecord() {
        val out = run(
            listOf(
                mapOf("p" to 1, "addrs" to listOf(mapOf("city" to "A"))),
                mapOf("p" to 2, "addrs" to listOf(mapOf("city" to "B"), mapOf("city" to "C"))),
            )
        ) {
            val person = take("p")
            take("addrs").eachSubrecord(flush = true, reset = true) {
                person.put("person")
                take("city").put("city")
            }
        }
        check(
            listOf(
                mapOf("person" to 1, "city" to "A"),
                mapOf("person" to 2, "city" to "B"),
                mapOf("person" to 2, "city" to "C"),
            ),
            out,
        )
    }

    @Test
    fun splitAndRegexEnterRecords() {
        val out = run(listOf(mapOf("name" to "Doe, Jane", "id" to "ab-12"))) {
            take("name").split(", ") { take(0).put("last"); take(1).put("first") }
            take("id").regex(Regex("(?<letters>[a-z]+)-(?<n>\\d+)")) {
                take("letters").put("letters")
                take(2).parseInt().put("n")
            }
        }
        check(listOf(mapOf<Any?, Any?>("last" to "Doe", "first" to "Jane", "letters" to "ab", "n" to 12)), out)
    }

    @Test
    fun lookups() {
        ItemContext.registerLookup("sex", mapOf("M" to "Male", "F" to "Female"))
        val out = run(listOf(mapOf("s" to "M"), mapOf("s" to "?"))) {
            take("s").pipeOrRevert { lookup("sex") }.put("sex")
        }
        check(listOf(mapOf<Any?, Any?>("sex" to "Male"), mapOf("sex" to "?")), out)
    }

    @Test
    fun multiSink() {
        val people = mutableListOf<Map<Any?, Any?>>()
        val addresses = mutableListOf<Map<Any?, Any?>>()
        val sink = MultiSink("person" to people.asSink(), "address" to addresses.asSink())
        migrate(listOf(mapOf("id" to 1, "name" to "A", "city" to "X")).asSource(), sink) {
            take("id") { putBury("person", "id"); putBury("address", "person_id") }
            take("name").putBury("person", "name")
            take("city").putBury("address", "city")
        }
        check(listOf(mapOf<Any?, Any?>("id" to 1, "name" to "A")), people)
        check(listOf(mapOf<Any?, Any?>("person_id" to 1, "city" to "X")), addresses)
    }

    @Test
    fun hooksSeeCollector() {
        var seen: Map<Any?, Any?>? = null
        run(listOf(mapOf("a" to 1))) {
            afterFlush { _, collected -> seen = collected.toMap() }
            take("a").put("A")
        }
        check(mapOf<Any?, Any?>("A" to 1), seen)
    }
}
