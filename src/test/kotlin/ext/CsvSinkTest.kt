package micdrop.ext

import micdrop.Collector
import micdrop.SinkError
import micdrop.asSource
import micdrop.check
import micdrop.migrate
import org.junit.jupiter.api.io.TempDir
import java.io.StringReader
import java.io.StringWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class RecordingWriter : Writer() {
    val out = StringBuilder()
    var closed = false
    var flushed = false
    override fun write(cbuf: CharArray, off: Int, len: Int) { out.append(cbuf, off, len) }
    override fun flush() { flushed = true }
    override fun close() { closed = true }
}

class CsvSinkTest {
    @TempDir
    lateinit var dir: Path

    private fun row(vararg pairs: Pair<Any?, Any?>): Collector = linkedMapOf(*pairs)

    private fun written(sink: CsvSink, writer: StringWriter, vararg rows: Collector): String {
        rows.forEach { sink.write(it) }
        sink.close()
        return writer.toString()
    }

    private fun csv(vararg rows: Collector, build: (StringWriter) -> CsvSink = { CsvSink(it) }): String {
        val writer = StringWriter()
        return written(build(writer), writer, *rows)
    }

    // region basics

    @Test
    fun writesAHeaderAndRows() {
        check("a,b\n1,2\n3,4\n", csv(row("a" to 1, "b" to 2), row("a" to 3, "b" to 4)))
    }

    @Test
    fun columnsComeFromTheFirstRecord() {
        check("z,a\n1,2\n3,4\n", csv(row("z" to 1, "a" to 2), row("a" to 4, "z" to 3)))
    }

    @Test
    fun missingColumnsAreWrittenEmpty() {
        check("a,b,c\n1,2,3\n4,,\n", csv(row("a" to 1, "b" to 2, "c" to 3), row("a" to 4)))
    }

    @Test
    fun aKeyOutsideTheColumnsIsAnError() {
        val error = assertFailsWith<SinkError> { csv(row("a" to 1), row("a" to 2, "extra" to 3)) }
        assertTrue("extra" in error.message!!, error.message)
    }

    @Test
    fun nonStringKeysAreUsedAsText() {
        check("1,2\nx,y\n", csv(row(1 to "x", 2 to "y")))
    }

    @Test
    fun nothingWrittenMeansNoOutput() {
        check("", csv())
    }

    // endregion

    // region headers

    @Test
    fun explicitHeadersSetTheColumnOrder() {
        check("b,a\n2,1\n", csv(row("a" to 1, "b" to 2)) { CsvSink(it, headers = listOf("b", "a")) })
    }

    @Test
    fun explicitHeadersAreWrittenEvenWithNoRecords() {
        check("a,b\n", csv { CsvSink(it, headers = listOf("a", "b")) })
    }

    @Test
    fun explicitHeadersAreOnlyWrittenOnce() {
        check("a\n1\n2\n", csv(row("a" to 1), row("a" to 2)) { CsvSink(it, headers = listOf("a")) })
    }

    @Test
    fun explicitHeadersLimitTheColumns() {
        assertFailsWith<SinkError> { csv(row("a" to 1, "b" to 2)) { CsvSink(it, headers = listOf("a")) } }
    }

    @Test
    fun theHeaderRowCanBeLeftOut() {
        check("1,2\n3,4\n", csv(row("a" to 1, "b" to 2), row("a" to 3, "b" to 4)) { CsvSink(it, writeHeader = false) })
        check("", csv { CsvSink(it, headers = listOf("a"), writeHeader = false) })
    }

    // endregion

    // region values

    @Test
    fun nullIsEmptyAndEmptyStringIsQuoted() {
        check("a,b,c\n,\"\",x\n", csv(row("a" to null, "b" to "", "c" to "x")))
    }

    @Test
    fun quotesFieldsThatNeedIt() {
        val out = csv(row("a" to "Doe, Jane", "b" to "said \"hi\"", "c" to "two\nlines", "d" to "cr\rhere"))
        check("a,b,c,d\n\"Doe, Jane\",\"said \"\"hi\"\"\",\"two\nlines\",\"cr\rhere\"\n", out)
    }

    @Test
    fun quotesFieldsWithSurroundingWhitespace() {
        check("a,b\n\" x\",\"y \"\n", csv(row("a" to " x", "b" to "y ")))
    }

    @Test
    fun leavesOrdinaryFieldsUnquoted() {
        check("a,b\nhello world,it's\n", csv(row("a" to "hello world", "b" to "it's")))
    }

    @Test
    fun writesOtherTypesWithToString() {
        val out = csv(row("n" to 1, "d" to 1.5, "b" to true, "date" to LocalDate.of(2024, 11, 14), "l" to 5L))
        check("n,d,b,date,l\n1,1.5,true,2024-11-14,5\n", out)
    }

    @Test
    fun nestedValuesAreAnError() {
        val error = assertFailsWith<SinkError> { csv(row("a" to mapOf("x" to 1))) }
        assertTrue("a" in error.message!!, error.message)
        assertFailsWith<SinkError> { csv(row("a" to listOf(1, 2))) }
    }

    // endregion

    // region format options

    @Test
    fun otherDelimiters() {
        check("a\tb\n1\tx,y\n", csv(row("a" to 1, "b" to "x,y")) { CsvSink(it, delimiter = '\t') })
        check("a;b\n1;\"x;y\"\n", csv(row("a" to 1, "b" to "x;y")) { CsvSink(it, delimiter = ';') })
    }

    @Test
    fun otherQuoteCharacters() {
        check("a\n'it''s'\n", csv(row("a" to "it's")) { CsvSink(it, quote = '\'') })
    }

    @Test
    fun otherLineSeparators() {
        check("a\r\n1\r\n", csv(row("a" to 1)) { CsvSink(it, lineSeparator = "\r\n") })
    }

    @Test
    fun byteOrderMark() {
        val bom = 0xFEFF.toChar()
        check("${bom}a\n1\n", csv(row("a" to 1)) { CsvSink(it, byteOrderMark = true) })
        check("${bom}a\n", csv { CsvSink(it, headers = listOf("a"), byteOrderMark = true) })
    }

    // endregion

    // region lifecycle

    @Test
    fun closesTheWriter() {
        val writer = RecordingWriter()
        CsvSink(writer).close()
        assertTrue(writer.closed)
    }

    @Test
    fun canLeaveTheWriterOpenButFlushesIt() {
        val writer = RecordingWriter()
        val sink = CsvSink(writer, closeWriter = false)
        sink.write(row("a" to 1))
        sink.close()
        check(false, writer.closed)
        assertTrue(writer.flushed)
        check("a\n1\n", writer.out.toString())
    }

    @Test
    fun opensAFile() {
        val path = dir.resolve("out.csv")
        CsvSink.open(path).use { it.write(row("name" to "Ada")) }
        check("name\nAda\n", path.readText())
    }

    @Test
    fun opensAFileWithOtherOptions() {
        val path = dir.resolve("latin.tsv")
        CsvSink.open(path, charset = StandardCharsets.ISO_8859_1, delimiter = '\t', headers = listOf("a", "b"))
            .use { it.write(row("a" to "café", "b" to 1)) }
        check("a\tb\ncafé\t1\n".toByteArray(StandardCharsets.ISO_8859_1).toList(), path.readBytes().toList())
    }

    // endregion

    // region in a migration

    @Test
    fun migratesToCsv() {
        val output = StringWriter()
        val source = listOf(mapOf("first" to "Ada", "age" to "36"), mapOf("first" to "Alan", "age" to "41"))
        migrate(source.asSource(), CsvSink(output)) {
            take("first").put("name")
            take("age").parseInt().put("age")
        }
        check("name,age\nAda,36\nAlan,41\n", output.toString())
    }

    @Test
    fun readsBackWhatWasWritten() {
        val rows = listOf(
            row("plain" to "x", "empty" to "", "missing" to null, "tricky" to "a,\"b\"\nc", "padded" to " p "),
            row("plain" to "y", "empty" to "", "missing" to null, "tricky" to "", "padded" to "q"),
        )
        val output = StringWriter()
        val sink = CsvSink(output)
        rows.forEach { sink.write(it) }
        sink.close()

        val back = CsvSource(StringReader(output.toString())).entries().map { it.record.raw }.toList()
        check(rows.map { it.mapKeys { (k, _) -> k.toString() } }, back)
    }

    @Test
    fun convertsCsvToCsv() {
        val input = "Name,Born\nAda,12/10/15\n\"Hopper, Grace\",12/09/06\n"
        val output = StringWriter()
        migrate(CsvSource(StringReader(input)), CsvSink(output)) {
            take("Name").put("name")
            take("Born") { parseDate("MM/dd/yy"); formatDate("yyyy-MM-dd"); put("born") }
        }
        check("name,born\nAda,2015-12-10\n\"Hopper, Grace\",2006-12-09\n", output.toString())
    }

    // endregion
}
