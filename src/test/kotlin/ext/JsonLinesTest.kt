package micdrop.ext

import micdrop.Collector
import micdrop.SourceError
import micdrop.asSink
import micdrop.check
import micdrop.migrate
import org.junit.jupiter.api.io.TempDir
import java.io.StringReader
import java.io.StringWriter
import java.io.Reader
import java.io.Writer
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class TrackingReader(text: String) : Reader() {
    private val inner = StringReader(text)
    var closed = false
    override fun read(cbuf: CharArray, off: Int, len: Int) = inner.read(cbuf, off, len)
    override fun close() { closed = true; inner.close() }
}

private class TrackingWriter : Writer() {
    val out = StringBuilder()
    var closed = false
    var flushed = false
    override fun write(cbuf: CharArray, off: Int, len: Int) { out.append(cbuf, off, len) }
    override fun flush() { flushed = true }
    override fun close() { closed = true }
}

class JsonLinesSourceTest {
    @TempDir
    lateinit var dir: Path

    private fun records(text: String) = JsonLinesSource(StringReader(text)).entries().toList()

    @Test
    fun parsesJsonLines() {
        val records = records("{\"a\":1}\n{\"a\":2}")
        check(2, records.size)
        check(1L, records[0].record["a"])
        check(2L, records[1].record["a"])
    }

    @Test
    fun indexesFromZero() {
        check(listOf(0, 1), records("{\"a\":1}\n{\"a\":2}\n").map { it.index })
    }

    @Test
    fun toleratesTrailingNewlinesAndBlankLines() {
        check(2, records("{\"a\":1}\n\n   \n{\"a\":2}\n\n").size)
        check(2, records("{\"a\":1}\r\n{\"a\":2}\r\n").size)
    }

    @Test
    fun anEmptyInputHasNoRecords() {
        check(0, records("").size)
    }

    @Test
    fun arraysAreRecordsToo() {
        check("y", records("[\"x\", \"y\"]").single().record[1])
    }

    @Test
    fun keepsNestedStructure() {
        val record = records("""{"a": {"b": [1, {"c": null}]}}""").single().record
        check(mapOf("b" to listOf(1L, mapOf("c" to null))), record["a"])
    }

    @Test
    fun isLazy() {
        val reader = TrackingReader("{\"a\":1}\nnot json\n")
        val first = JsonLinesSource(reader).entries().first()
        check(1L, first.record["a"]) // never reaches the bad line
    }

    @Test
    fun reportsInvalidJsonWithTheLineNumber() {
        val error = assertFailsWith<SourceError> { records("{\"a\":1}\n\n{nope\n") }
        assertTrue("Line 3" in error.message!!, error.message)
    }

    @Test
    fun rejectsLinesThatAreNotRecords() {
        assertFailsWith<SourceError> { records("42") }
        assertFailsWith<SourceError> { records("\"text\"") }
        assertFailsWith<SourceError> { records("null") }
    }

    @Test
    fun closesTheReader() {
        val reader = TrackingReader("{\"a\":1}")
        val source = JsonLinesSource(reader)
        source.entries().toList()
        source.close()
        assertTrue(reader.closed)
    }

    @Test
    fun canLeaveTheReaderOpen() {
        val reader = TrackingReader("{\"a\":1}")
        JsonLinesSource(reader, closeReader = false).close()
        check(false, reader.closed)
    }

    @Test
    fun tellsOnCloseWhetherEverythingWasRead() {
        val results = mutableListOf<Boolean>()
        JsonLinesSource(StringReader("{\"a\":1}\n{\"a\":2}")) { results.add(it) }.also {
            it.entries().first()
            it.close()
        }
        JsonLinesSource(StringReader("{\"a\":1}\n{\"a\":2}")) { results.add(it) }.also {
            it.entries().toList()
            it.close()
        }
        check(listOf(false, true), results)
    }

    @Test
    fun opensAFile() {
        val path = dir.resolve("data.jsonl")
        path.writeText("{\"a\":1}\n{\"a\":2}\n")
        JsonLinesSource.open(path).use { check(2, it.entries().count()) }
    }

    @Test
    fun fromCommandReadsStandardOutput() {
        val program = javaProgram("""System.out.println("{\"a\":1}"); System.out.println("{\"a\":2}");""")
        JsonLinesSource.fromCommand(*program).use { source ->
            check(listOf(1L, 2L), source.entries().map { it.record["a"] }.toList())
        }
    }

    @Test
    fun fromCommandFailsOnANonZeroExit() {
        val program = javaProgram("""System.out.println("{\"a\":1}"); System.exit(3);""")
        val source = JsonLinesSource.fromCommand(*program)
        source.entries().toList()
        val error = assertFailsWith<SourceError> { source.close() }
        assertTrue("status 3" in error.message!!, error.message)
    }

    @Test
    fun fromCommandIgnoresTheExitStatusIfStoppedEarly() {
        val program = javaProgram("""System.out.println("{\"a\":1}"); System.out.flush(); System.exit(3);""")
        JsonLinesSource.fromCommand(*program).use { it.entries().first() }
    }

    private fun javaProgram(body: String): Array<String> {
        val file = dir.resolve("P.java")
        file.writeText("public class P { public static void main(String[] args) { $body } }")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        return arrayOf(java, file.toString())
    }
}

class JsonLinesSinkTest {
    @TempDir
    lateinit var dir: Path

    private fun collector(vararg pairs: Pair<Any?, Any?>): Collector = linkedMapOf(*pairs)

    @Test
    fun writesJsonLines() {
        val writer = StringWriter()
        val sink = JsonLinesSink(writer)
        sink.write(collector("a" to 1))
        sink.write(collector("a" to 2))
        check("{\"a\":1}\n{\"a\":2}\n", writer.toString())
    }

    @Test
    fun writesNestedStructure() {
        val writer = StringWriter()
        JsonLinesSink(writer).write(collector("a" to mapOf("b" to listOf(1, null, "x"))))
        check("{\"a\":{\"b\":[1,null,\"x\"]}}\n", writer.toString())
    }

    @Test
    fun closesTheWriter() {
        val writer = TrackingWriter()
        JsonLinesSink(writer).close()
        assertTrue(writer.closed)
    }

    @Test
    fun canLeaveTheWriterOpenButFlushesIt() {
        val writer = TrackingWriter()
        JsonLinesSink(writer, closeWriter = false).close()
        check(false, writer.closed)
        assertTrue(writer.flushed)
    }

    @Test
    fun opensAFile() {
        val path = dir.resolve("out.jsonl")
        JsonLinesSink.open(path).use { it.write(collector("a" to 1)) }
        check("{\"a\":1}\n", path.readText())
    }

    @Test
    fun migratesBetweenJsonLines() {
        val input = "{\"first\":\"Ada\",\"age\":\"36\"}\n{\"first\":\"Alan\",\"age\":\"41\"}\n"
        val output = StringWriter()
        migrate(JsonLinesSource(StringReader(input)), JsonLinesSink(output)) {
            take("first").put("name")
            take("age").parseInt().put("age")
        }
        check("{\"name\":\"Ada\",\"age\":36}\n{\"name\":\"Alan\",\"age\":41}\n", output.toString())
    }

    @Test
    fun roundTripsThroughMigration() {
        val text = "{\"a\":1,\"b\":[true,null],\"c\":{\"d\":\"x\"}}\n"
        val output = StringWriter()
        migrate(JsonLinesSource(StringReader(text)), JsonLinesSink(output)) {
            passthru("a", "b", "c")
        }
        check(text, output.toString())
    }
}
