package micdrop.ext

import micdrop.Collector
import micdrop.SourceError
import micdrop.asSink
import micdrop.check
import micdrop.migrate
import org.junit.jupiter.api.io.TempDir
import java.io.Reader
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class CountingReader(text: String) : Reader() {
    private val inner = StringReader(text)
    var closed = false
    var charsRead = 0
    override fun read(cbuf: CharArray, off: Int, len: Int) = inner.read(cbuf, off, len).also { if (it > 0) charsRead += it }
    override fun close() { closed = true; inner.close() }
}

class CsvLiberalQuotesTest {
    private fun rows(text: String, liberal: Boolean = true) =
        CsvSource(StringReader(text), liberalQuotes = liberal).entries().map { it.record.raw }.toList()

    @Test
    fun strictByDefault() {
        assertFailsWith<SourceError> { rows("a,b\n\"Test \"VFN\"\",x\n", liberal = false) }
    }

    @Test
    fun strayQuotesInsideQuotedFieldsAreText() {
        check(
            listOf(mapOf("a" to "Test \"VFN\"", "b" to "x"), mapOf("a" to "5\" pipe, big", "b" to "y")),
            rows("a,b\n\"Test \"VFN\"\",x\n\"5\" pipe, big\",y\n"),
        )
    }

    @Test
    fun wellFormedDataIsUnchanged() {
        val text = "a,b,c\n\"x\"\"y\",\"\",\"line1\nline2\"\n\"ends with quote\"\"\",,z"
        check(rows(text, liberal = false), rows(text))
        check("ends with quote\"", (rows(text)[1] as Map<*, *>)["a"])
    }

    @Test
    fun aQuoteAtTheEndOfTheDataClosesTheField() {
        check(listOf(mapOf("a" to "q\"")), rows("a\n\"q\"\""))
    }

    @Test
    fun unquotedFieldsKeepTheirQuotes() {
        check(listOf(mapOf("a" to "5\" pipe", "b" to "y")), rows("a,b\n5\" pipe,y\n"))
    }
}

class CsvSourceTest {
    @TempDir
    lateinit var dir: Path

    private fun rows(text: String, vararg options: Pair<String, Any?>): List<Any?> = source(text, *options).entries()
        .map { it.record.raw }.toList()

    private fun source(text: String, vararg options: Pair<String, Any?>): CsvSource {
        val o = options.toMap()
        @Suppress("UNCHECKED_CAST")
        return CsvSource(
            StringReader(text),
            headers = o["headers"] as List<String>?,
            firstRowIsHeader = o["firstRowIsHeader"] as Boolean? ?: true,
            delimiter = o["delimiter"] as Char? ?: ',',
            trim = o["trim"] as Boolean? ?: false,
        )
    }

    // region headers

    @Test
    fun usesTheFirstRowAsHeaders() {
        check(
            listOf(mapOf("a" to "1", "b" to "2"), mapOf("a" to "3", "b" to "4")),
            rows("a,b\n1,2\n3,4\n"),
        )
    }

    @Test
    fun indexesDataRowsFromZero() {
        check(listOf(0, 1), source("a\n1\n2").entries().map { it.index }.toList())
    }

    @Test
    fun keepsColumnOrder() {
        val row = rows("z,a,m\n1,2,3").single() as Map<*, *>
        check(listOf("z", "a", "m"), row.keys.toList())
    }

    @Test
    fun explicitHeadersReplaceTheHeaderRow() {
        check(listOf(mapOf("x" to "1", "y" to "2")), rows("a,b\n1,2", "headers" to listOf("x", "y")))
    }

    @Test
    fun explicitHeadersForDataWithoutAHeaderRow() {
        check(
            listOf(mapOf("x" to "1", "y" to "2"), mapOf("x" to "3", "y" to "4")),
            rows("1,2\n3,4", "headers" to listOf("x", "y"), "firstRowIsHeader" to false),
        )
    }

    @Test
    fun noHeadersGivesLists() {
        check(listOf(listOf("a", "b"), listOf("1", "2")), rows("a,b\n1,2", "firstRowIsHeader" to false))
    }

    @Test
    fun duplicateHeadersKeepTheFirstColumn() {
        check(listOf(mapOf("a" to "1", "b" to "3")), rows("a,a,b\n1,2,3"))
    }

    private val bom = 0xFEFF.toChar()

    @Test
    fun aByteOrderMarkIsIgnored() {
        check(listOf(mapOf("a" to "1")), rows("${bom}a\n1"))
        check(listOf(listOf("a"), listOf("1")), rows("${bom}a\n1", "firstRowIsHeader" to false))
    }

    @Test
    fun aByteOrderMarkInFrontOfAQuotedHeaderIsIgnored() {
        check(listOf(mapOf("a" to "1", "b" to "2")), rows("$bom\"a\",b\n1,2"))
    }

    @Test
    fun aByteOrderMarkOnlyMattersAtTheStart() {
        check(listOf(mapOf("a" to "x${bom}y")), rows("a\nx${bom}y"))
    }

    // endregion

    // region fields

    @Test
    fun handlesQuotedFields() {
        val text = "name,note\n\"Doe, Jane\",\"said \"\"hi\"\"\"\n\"multi\nline\",plain\n"
        check(
            listOf(
                mapOf("name" to "Doe, Jane", "note" to "said \"hi\""),
                mapOf("name" to "multi\nline", "note" to "plain"),
            ),
            rows(text),
        )
    }

    @Test
    fun unquotedEmptyFieldsAreNullButQuotedEmptyFieldsAreNot() {
        val row = rows("a,b,c\n,\"\",x").single() as Map<*, *>
        assertNull(row["a"])
        check("", row["b"])
        check("x", row["c"])
    }

    @Test
    fun keepsWhitespaceUnlessTrimming() {
        check(listOf(mapOf("a" to " x ")), rows("a\n x "))
        check(listOf(mapOf("a" to "x")), rows("a\n x ", "trim" to true))
    }

    @Test
    fun handlesWindowsLineEndings() {
        check(listOf(mapOf("a" to "1", "b" to "2")), rows("a,b\r\n1,2\r\n"))
    }

    @Test
    fun ignoresBlankLines() {
        check(listOf(mapOf("a" to "1"), mapOf("a" to "2")), rows("a\n\n1\n\n\n2\n\n"))
    }

    @Test
    fun supportsOtherDelimiters() {
        check(listOf(mapOf("a" to "1", "b" to "x,y")), rows("a\tb\n1\tx,y", "delimiter" to '\t'))
        check(listOf(mapOf("a" to "1", "b" to "2")), rows("a;b\n1;2", "delimiter" to ';'))
    }

    @Test
    fun supportsOtherQuoteCharacters() {
        val source = CsvSource(StringReader("a,b\n'x,y',z"), quote = '\'')
        check(listOf(mapOf("a" to "x,y", "b" to "z")), source.entries().map { it.record.raw }.toList())
    }

    @Test
    fun fieldsKeepTheirTextExactly() {
        check(listOf(mapOf("a" to "007", "b" to "1.50", "c" to "2021-09-01")), rows("a,b,c\n007,1.50,2021-09-01"))
    }

    // endregion

    // region ragged rows

    @Test
    fun shortRowsArePaddedWithNull() {
        check(listOf(mapOf("a" to "1", "b" to null, "c" to null)), rows("a,b,c\n1"))
    }

    @Test
    fun emptyExtraFieldsAreIgnored() {
        check(listOf(mapOf("a" to "1", "b" to "2")), rows("a,b\n1,2,\n"))
        check(listOf(mapOf("a" to "1", "b" to "2")), rows("a,b\n1,2,,\n"))
    }

    @Test
    fun nonEmptyExtraFieldsAreAnError() {
        val error = assertFailsWith<SourceError> { rows("a,b\n1,2,3") }
        assertTrue("3 fields" in error.message!!, error.message)
    }

    // endregion

    // region empty and bad input

    @Test
    fun emptyInputHasNoRecords() {
        check(emptyList<Any?>(), rows(""))
        check(emptyList<Any?>(), rows("\n\n"))
    }

    @Test
    fun aHeaderRowAloneHasNoRecords() {
        check(emptyList<Any?>(), rows("a,b\n"))
        check(emptyList<Any?>(), rows("a,b"))
    }

    @Test
    fun malformedQuotingIsASourceError() {
        assertFailsWith<SourceError> { rows("a,b\n\"unterminated,2\n") }
        assertFailsWith<SourceError> { rows("a,b\n\"x\"y,2\n") }
    }

    // endregion

    // region lifecycle

    @Test
    fun isLazy() {
        val reader = CountingReader("a\n" + (1..20000).joinToString("\n") { "row$it" })
        val first = CsvSource(reader).entries().first()
        check("row1", (first.record.raw as Map<*, *>)["a"])
        assertTrue(reader.charsRead < 20000, "read ${reader.charsRead} chars")
    }

    @Test
    fun closesTheReader() {
        val reader = CountingReader("a\n1")
        CsvSource(reader).close()
        assertTrue(reader.closed)
    }

    @Test
    fun canLeaveTheReaderOpen() {
        val reader = CountingReader("a\n1")
        CsvSource(reader, closeReader = false).close()
        check(false, reader.closed)
    }

    @Test
    fun opensAFile() {
        val path = dir.resolve("people.csv")
        path.writeText("name\nAda\nAlan\n")
        CsvSource.open(path).use { check(2, it.entries().count()) }
    }

    @Test
    fun opensAFileWithACharset() {
        val path = dir.resolve("latin.csv")
        path.writeBytes("name\ncafé\n".toByteArray(StandardCharsets.ISO_8859_1))
        CsvSource.open(path, charset = StandardCharsets.ISO_8859_1).use {
            check(listOf(mapOf("name" to "café")), it.entries().map { e -> e.record.raw }.toList())
        }
    }

    @Test
    fun opensAFileWithOtherOptions() {
        val path = dir.resolve("data.tsv")
        path.writeText("1\t2\n3\t4\n")
        CsvSource.open(path, headers = listOf("x", "y"), firstRowIsHeader = false, delimiter = '\t').use {
            check(mapOf("x" to "3", "y" to "4"), it.entries().last().record.raw)
        }
    }

    // endregion

    // region in a migration

    @Test
    fun migratesFromCsv() {
        val text = """
            Name,Birth Date,Deceased?
            Ada Lovelace,12/10/15,No
            Alan Turing,06/23/12,
            "Hopper, Grace",12/09/06,Y
        """.trimIndent()
        val out = mutableListOf<Collector>()
        migrate(CsvSource(StringReader(text)), out.asSink()) {
            take("Name").put("name")
            take("Birth Date") {
                parseDate("MM/dd/yy")
                put("dob")
            }
            take("Deceased?") {
                parseBoolean()
                default(false)
                put("is_deceased")
            }
        }
        check(
            listOf(
                mapOf("name" to "Ada Lovelace", "dob" to LocalDate.of(2015, 12, 10), "is_deceased" to false),
                mapOf("name" to "Alan Turing", "dob" to LocalDate.of(2012, 6, 23), "is_deceased" to false),
                mapOf("name" to "Hopper, Grace", "dob" to LocalDate.of(2006, 12, 9), "is_deceased" to true),
            ),
            out,
        )
    }

    @Test
    fun migratesFromCsvWithoutHeaders() {
        val out = mutableListOf<Collector>()
        migrate(CsvSource(StringReader("Ada,36\nAlan,41"), firstRowIsHeader = false), out.asSink()) {
            take(0).put("name")
            take(1).parseInt().put("age")
        }
        check(listOf(mapOf("name" to "Ada", "age" to 36), mapOf("name" to "Alan", "age" to 41)), out)
    }

    @Test
    fun takingAMissingColumnFails() {
        assertFailsWith<micdrop.PipelineError> {
            migrate(CsvSource(StringReader("a\n1")), mutableListOf<Collector>().asSink()) { take("nope") }
        }
    }

    // endregion
}
