package org.example.ext

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.QuoteMode
import org.example.Record
import org.example.Source
import org.example.SourceEntry
import org.example.SourceError
import java.io.IOException
import java.io.PushbackReader
import java.io.Reader
import java.io.UncheckedIOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

/**
 * A source of CSV (or similar delimited) data, with one record per row, indexed by position starting from 0.
 *
 * Each record is a `Map<String, String?>` keyed by the column headers or, if there are no headers, a
 * `List<String?>` which can be taken from by position. An unquoted empty field is null, while a quoted empty field
 * (`""`) is an empty string. Rows shorter than the headers are padded with null; extra fields beyond the headers
 * are an error unless they are empty (as from a trailing delimiter). Blank lines are ignored.
 *
 * The headers are chosen as follows:
 *
 * * By default ([firstRowIsHeader] and no [headers]), the first row names the columns.
 * * With [headers] and [firstRowIsHeader], the first row is skipped and [headers] names the columns instead.
 * * With [headers] and not [firstRowIsHeader], there is no header row in the data, and [headers] names the columns.
 * * With neither, there are no headers, and records are lists.
 *
 * If a header name appears more than once, the first such column wins.
 *
 * The data can only be read once, since it is streamed from [reader].
 *
 * @param delimiter the field delimiter; use `'\t'` for tab-separated data
 * @param quote the character used to quote fields. Inside a quoted field, two quotes make a literal quote.
 * @param trim whether to strip whitespace around unquoted fields
 * @param closeReader whether closing the source also closes [reader]
 */
class CsvSource(
    private val reader: Reader,
    private val headers: List<String>? = null,
    private val firstRowIsHeader: Boolean = true,
    delimiter: Char = ',',
    quote: Char = '"',
    private val trim: Boolean = false,
    private val closeReader: Boolean = true,
) : Source {
    private val format = CSVFormat.DEFAULT.builder()
        .setDelimiter(delimiter)
        .setQuote(quote)
        .setIgnoreEmptyLines(true)
        .setNullString("")
        // Strict quote mode is what makes the parser keep a quoted "" as an empty string instead of the null string
        .setQuoteMode(QuoteMode.ALL_NON_NULL)
        .setTrim(trim)
        .get()

    override fun entries(): Sequence<SourceEntry> = sequence {
        val rows = format.parse(withoutByteOrderMark(reader)).iterator()
        var lineNumber = 0

        fun next(): List<String?>? = try {
            if (rows.hasNext()) rows.next().toList().also { lineNumber++ } else null
        } catch (e: UncheckedIOException) {
            throw SourceError("CSV row ${lineNumber + 1}: ${e.cause?.message ?: e.message}", e)
        } catch (e: IOException) {
            throw SourceError("CSV row ${lineNumber + 1}: ${e.message}", e)
        }

        var names = headers
        if (firstRowIsHeader) {
            val first = next() ?: return@sequence
            if (names == null) names = first.map { it.orEmpty() }
        }

        var index = 0
        while (true) {
            val row = next() ?: break
            val data: Any = if (names == null) row else toMap(names, row, lineNumber)
            yield(SourceEntry(index++, Record.of(data)))
        }
    }

    private fun toMap(names: List<String>, values: List<String?>, lineNumber: Int): Map<String, String?> {
        if (values.size > names.size && values.drop(names.size).any { !it.isNullOrEmpty() }) {
            throw SourceError(
                "CSV row $lineNumber has ${values.size} fields, but there are only ${names.size} headers"
            )
        }
        val map = LinkedHashMap<String, String?>()
        for ((i, name) in names.withIndex()) {
            if (name !in map) map[name] = values.getOrNull(i)
        }
        return map
    }

    override fun close() {
        if (closeReader) reader.close()
    }

    companion object {
        /** Byte order mark, as left at the start of the text by some exporters (Excel, notably) */
        private const val BOM = 0xFEFF

        private fun withoutByteOrderMark(reader: Reader): Reader {
            val pushback = PushbackReader(reader, 1)
            val first = pushback.read()
            if (first != -1 && first != BOM) pushback.unread(first)
            return pushback
        }

        /** Read a CSV file. Takes the same options as the constructor. */
        fun open(
            path: Path,
            charset: Charset = Charsets.UTF_8,
            headers: List<String>? = null,
            firstRowIsHeader: Boolean = true,
            delimiter: Char = ',',
            quote: Char = '"',
            trim: Boolean = false,
        ) = CsvSource(
            Files.newBufferedReader(path, charset),
            headers, firstRowIsHeader, delimiter, quote, trim,
        )
    }
}
