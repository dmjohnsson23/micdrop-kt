package org.example

import org.example.ext.CsvSink
import org.example.ext.CsvSource
import java.io.StringReader
import java.io.StringWriter
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFailsWith

// Extension functions from docs/extending.md
fun ItemContext.subtract(amount: Int) = transform { (it as Int) - amount }

fun ItemContext.titleCase() = transform { text ->
    text.toString().split(" ").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}

fun ItemContext.orUnknown(): ItemContext {
    if (value == null) value = "unknown"
    return this
}

fun ItemContext.parseKeyValueLine(block: (SubRecordContext.() -> Unit)? = null): ItemContext {
    transform { line -> line.toString().split(";").associate { it.substringBefore("=") to it.substringAfter("=") } }
    if (block != null && value != null) enter(block)
    return this
}

class Person(val name: String, val age: Int)

class CounterSource(private val count: Int) : Source {
    override fun entries(): Sequence<SourceEntry> =
        (1..count).asSequence().map { SourceEntry(it, Record.of(mapOf("n" to it))) }
}

class PrefilledSink : Sink {
    val written = mutableListOf<Collector>()
    override fun makeCollector(): Collector = linkedMapOf("source" to "migration")
    override fun write(collector: Collector) {
        written.add(collector)
    }
}

/** Runs the code from the README and docs, and checks the output shown there */
class ReadmeExamplesTest {
    private fun sink() = mutableListOf<Collector>()

    @Test
    fun quickExample() {
        val csv = "Name,Birth Date,Deceased?\nAda,12/10/15,\nGrace,12/09/06,Y\n"
        val output = StringWriter()
        migrate(CsvSource(StringReader(csv)), CsvSink(output)) {
            take("Name").put("name")
            take("Birth Date") {
                parseDate("MM/dd/yy")
                formatDate("yyyy-MM-dd")
                put("dob")
            }
            take("Deceased?") {
                parseBoolean()
                default(false)
                put("is_deceased")
            }
        }
        check("name,dob,is_deceased\nAda,2015-12-10,false\nGrace,2006-12-09,true\n", output.toString())
    }

    @Test
    fun sourcesAndSinks() {
        val source = listOf(mapOf("a" to 1, "b" to 2), mapOf("a" to 3, "b" to 4))
        val sink = sink()
        migrate(source, sink) {
            take("a").put("A")
            take("b").put("B")
        }
        check(listOf(mapOf("A" to 1, "B" to 2), mapOf("A" to 3, "B" to 4)), sink)
    }

    @Test
    fun blocksAndChainingAreEquivalent() {
        val source = listOf(mapOf("a" to null))
        val sink = sink()
        migrate(source, sink) {
            take("a") {
                default(0)
                put("A")
            }
            take("a").default(0).put("A")
        }
        check(listOf(mapOf("A" to 0)), sink)
    }

    @Test
    fun simpleMigrations() {
        val source = listOf(
            mapOf("a" to "Yes", "b" to "08/07/22", "c" to "Stuff"),
            mapOf("a" to "No", "b" to "11/12/21", "c" to null),
        )
        val sink = sink()
        migrate(source, sink) {
            take("a") {
                parseBoolean()
                put("A")
            }
            take("b") {
                parseDate("MM/dd/yy")
                formatDate("yyyy-dd-MM")
                put("B")
            }
            take("c") {
                default("Whatsit")
                put("C")
            }
        }
        check(
            listOf(
                mapOf("A" to true, "B" to "2022-07-08", "C" to "Stuff"),
                mapOf("A" to false, "B" to "2021-12-11", "C" to "Whatsit"),
            ),
            sink,
        )
    }

    @Test
    fun passthru() {
        val sink = sink()
        migrate(listOf(mapOf("a" to 1, "b" to 2, "c" to 3)), sink) { passthru("a", "b") }
        check(listOf(mapOf("a" to 1, "b" to 2)), sink)
    }

    @Test
    fun takingItems() {
        val sink = sink()
        migrate(listOf(mapOf("a" to 1), mapOf("a" to 2)), sink) {
            index().put("row")
            static("Mailing").put("role")
        }
        check(listOf(mapOf("row" to 0, "role" to "Mailing"), mapOf("row" to 1, "role" to "Mailing")), sink)
    }

    @Test
    fun destructuring() {
        val source = listOf(
            mapOf("some" to mapOf("deeply" to mapOf("nested" to mapOf("data" to "1", "stuff" to "2")), "other" to "3")),
        )
        val sink = sink()
        migrate(source, sink) {
            take("some") {
                scope {
                    extract("other")
                    put("some_other")
                }
                scope {
                    extract("deeply")
                    extract("nested")
                    scope {
                        extract("data")
                        put("some_deeply_nested_data")
                    }
                }
            }
            take("some").extract("deeply").extract("nested").extract("stuff").put("stuff")
            takeDig("some", "deeply", "nested", "stuff").put("stuff_again")
        }
        check(
            listOf(
                mapOf(
                    "some_other" to "3", "some_deeply_nested_data" to "1", "stuff" to "2", "stuff_again" to "2",
                ),
            ),
            sink,
        )
    }

    @Test
    fun enteringResults() {
        val source = listOf(mapOf("name" to "Doe, Jane", "details" to """{"age": 36}"""))
        val sink = sink()
        migrate(source, sink) {
            take("name").split(", ") {
                take(0).put("last_name")
                take(1).put("first_name")
            }
            take("details").parseJson {
                take("age").put("age")
            }
        }
        check(listOf(mapOf("last_name" to "Doe", "first_name" to "Jane", "age" to 36L)), sink)
    }

    private val phones = listOf(
        mapOf("person" to 1, "home_phone" to null, "work_phone" to "(354) 756-4796", "cell_phone" to "(234) 678-7564"),
    )

    @Test
    fun collectList() {
        val sink = sink()
        migrate(phones, sink) {
            take("person").put("person_id")
            collectList(take("home_phone"), take("work_phone"), take("cell_phone")) {
                compact()
                formatJson()
                put("phones")
            }
        }
        check(listOf(mapOf("person_id" to 1, "phones" to """["(354) 756-4796","(234) 678-7564"]""")), sink)
    }

    @Test
    fun collectKvAndFormatString() {
        val sink = sink()
        migrate(phones, sink) {
            collectKv("Home" to take("home_phone"), "Work" to take("work_phone")) {
                formatJson()
                put("phones_by_kind")
            }
            collectFormatString("Home: %s, Work: %s", take("home_phone"), take("work_phone")) {
                put("contact")
            }
        }
        check(
            listOf(
                mapOf(
                    "phones_by_kind" to """{"Home":null,"Work":"(354) 756-4796"}""",
                    "contact" to "Home: , Work: (354) 756-4796",
                ),
            ),
            sink,
        )
    }

    @Test
    fun putBury() {
        val sink = sink()
        migrate(phones, sink) {
            take("person").put("person_id")
            take("home_phone") { if (value != null) putBury("phones", "home") }
            take("work_phone") { if (value != null) putBury("phones", "work") }
            take("cell_phone") { if (value != null) putBury("phones", "cell") }
        }
        check(
            listOf(
                mapOf(
                    "person_id" to 1,
                    "phones" to mapOf("work" to "(354) 756-4796", "cell" to "(234) 678-7564"),
                ),
            ),
            sink,
        )
    }

    @Test
    fun flush() {
        val source = listOf(
            mapOf("person" to 1, "home_phone" to "(634) 654-2457", "work_phone" to "(354) 756-4796"),
        )
        val sink = sink()
        migrate(source, sink) {
            take("person").put("person_id")
            take("home_phone").put("number")
            static("Home").put("type")
            flush()
            take("person").put("person_id")
            take("work_phone").put("number")
            static("Work").put("type")
        }
        check(
            listOf(
                mapOf("person_id" to 1, "number" to "(634) 654-2457", "type" to "Home"),
                mapOf("person_id" to 1, "number" to "(354) 756-4796", "type" to "Work"),
            ),
            sink,
        )
    }

    @Test
    fun eachSubrecord() {
        val source = listOf(
            mapOf("person" to 1, "addresses" to listOf(mapOf("city" to "Anytown", "role" to "Mailing"))),
            mapOf(
                "person" to 2,
                "addresses" to listOf(
                    mapOf("city" to "Thereabouts", "role" to "Home"),
                    mapOf("city" to "Elsewhere", "role" to "Mailing"),
                ),
            ),
        )
        val sink = sink()
        migrate(source, sink) {
            val person = take("person")
            take("addresses").eachSubrecord(flush = true, reset = true) {
                person.put("person_id")
                take("city").put("city")
                take("role").put("role")
            }
        }
        check(
            listOf(
                mapOf("person_id" to 1, "city" to "Anytown", "role" to "Mailing"),
                mapOf("person_id" to 2, "city" to "Thereabouts", "role" to "Home"),
                mapOf("person_id" to 2, "city" to "Elsewhere", "role" to "Mailing"),
            ),
            sink,
        )
    }

    @Test
    fun multiSink() {
        val source = listOf(mapOf("id" to 1, "first_name" to "Alice", "mail_city" to "Anytown"))
        val people = mutableListOf<Collector>()
        val addresses = mutableListOf<Collector>()
        val sink = MultiSink("person" to people.asSink(), "address" to addresses.asSink())

        migrate(source.asSource(), sink) {
            take("id") {
                putBury("person", "id")
                putBury("address", "person_id")
            }
            take("first_name").putBury("person", "fname")
            take("mail_city").putBury("address", "city")
            static("Mailing").putBury("address", "role")
        }
        check(listOf(mapOf("id" to 1, "fname" to "Alice")), people)
        check(listOf(mapOf("person_id" to 1, "city" to "Anytown", "role" to "Mailing")), addresses)
    }

    @Test
    fun skippingAndStopping() {
        val source = listOf("ok", "deleted", "fine", "END", "never").map { mapOf("status" to it) }
        val sink = sink()
        migrate(source, sink) {
            take("status") {
                skipIf("deleted")
                stopIf("END")
                put("status")
            }
        }
        check(listOf(mapOf("status" to "ok"), mapOf("status" to "fine")), sink)
    }

    @Test
    fun reusablePipelines() {
        val defaultZero: Pipeline = { default(0) }
        val sink = sink()
        migrate(listOf(mapOf("a" to 1, "b" to null), mapOf("a" to null, "b" to 4)), sink) {
            take("a").pipe(defaultZero).put("A")
            take("b") {
                pipe(defaultZero)
                put("B")
            }
        }
        check(listOf(mapOf("A" to 1, "B" to 0), mapOf("A" to 0, "B" to 4)), sink)
    }

    @Test
    fun plainKotlin() {
        val sink = sink()
        migrate(listOf(mapOf("a" to 1, "c" to 3), mapOf("a" to 5, "c" to 6)), sink) {
            take("a").convert { (it as Int) + 1 }.put("A")
            take("c") {
                update(if ((value as Int) % 2 == 0) "Even" else "Odd")
                put("C")
            }
        }
        check(listOf(mapOf("A" to 2, "C" to "Odd"), mapOf("A" to 6, "C" to "Even")), sink)
    }

    @Test
    fun lookups() {
        val source = listOf(mapOf("sex" to "M"), mapOf("sex" to "?"))
        val sink = sink()
        migrate(source, sink) {
            take("sex") {
                pipeOrRevert { lookup(mapOf("M" to "Male", "F" to "Female")) }
                put("sex")
            }
        }
        check(listOf(mapOf("sex" to "Male"), mapOf("sex" to "?")), sink)
    }

    @Test
    fun registeredLookups() {
        ItemContext.registerLookup("readme_sex", mapOf("M" to "Male", "F" to "Female"))
        val sink = sink()
        migrate(listOf(mapOf("sex" to "F")), sink) {
            take("sex").lookup("readme_sex").put("sex")
        }
        check(listOf(mapOf("sex" to "Female")), sink)
    }

    @Test
    fun handlingBadData() {
        val source = listOf(
            mapOf("age" to "x", "born" to "nope", "zip" to "N/A"),
            mapOf("age" to "36", "born" to "01/02/1990", "zip" to "12345"),
        )
        val sink = sink()
        migrate(source, sink) {
            take("age").pipeOrSetNull { parseInt() }.put("age")
            take("born").pipeOrRevert { parseDate("MM/dd/yyyy") }.put("born")
            take("zip").pipeOrFallback({ parseInt() }, { nullIf("N/A") }).put("zip")
        }
        check(
            listOf(
                mapOf("age" to null, "born" to "nope", "zip" to null),
                mapOf("age" to 36, "born" to LocalDate.of(1990, 1, 2), "zip" to 12345),
            ),
            sink,
        )
    }

    @Test
    fun hooks() {
        val seen = mutableListOf<String>()
        migrate(listOf(mapOf("a" to 1), mapOf("a" to 2)), sink()) {
            afterFlush { root, collected -> seen.add("Wrote record ${root.loopIndex}: $collected") }
            take("a").put("A")
        }
        check(listOf("Wrote record 0: {A=1}", "Wrote record 1: {A=2}"), seen)
    }

    // region docs/extending.md

    @Test
    fun customTransforms() {
        val sink = sink()
        migrate(listOf(mapOf("a" to 5, "name" to "ada lovelace", "n" to null)), sink) {
            take("a").subtract(1).put("A")
            take("name") {
                titleCase()
                put("name")
            }
            take("n").orUnknown().put("n")
        }
        check(listOf(mapOf("A" to 4, "name" to "Ada Lovelace", "n" to "unknown")), sink)
    }

    @Test
    fun customTransformThatEntersARecord() {
        val sink = sink()
        migrate(listOf(mapOf("line" to "a=1;b=2")), sink) {
            take("line").parseKeyValueLine { take("b").put("b") }
        }
        check(listOf(mapOf("b" to "2")), sink)
    }

    @Test
    fun customRecordType() {
        Record.registerAdapter { value ->
            (value as? Person)?.let { person ->
                object : Record {
                    override val raw = person
                    override fun contains(key: Any) = key == "name" || key == "age"
                    override fun get(key: Any): Any? = when (key) {
                        "name" -> person.name
                        "age" -> person.age
                        else -> null
                    }
                }
            }
        }
        val sink = sink()
        migrate(listOf(mapOf("owner" to Person("Ada", 36))), sink) {
            take("owner").take("name").put("owner_name")
        }
        check(listOf(mapOf("owner_name" to "Ada")), sink)
    }

    @Test
    fun customSourceAndSinks() {
        val sink = PrefilledSink()
        migrate(CounterSource(3), sink) { take("n").put("n") }
        check(listOf(1, 2, 3).map { mapOf("source" to "migration", "n" to it) }, sink.written)
    }

    @Test
    fun missingTakesFail() {
        assertFailsWith<PipelineError> { migrate(listOf(mapOf("a" to 1)), sink()) { take("typo") } }
    }

    // endregion
}
