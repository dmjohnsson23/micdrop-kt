package micdrop.ext

import micdrop.Collector
import micdrop.Sink
import micdrop.SinkError
import java.io.Writer
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

/**
 * A sink which writes each record as a row of CSV (or similar delimited) data. The counterpart of [CsvSource]:
 * a null value is written as an empty field, and an empty string as a quoted empty field (`""`), so the two can be
 * told apart when read back.
 *
 * Rows are streamed as they arrive, so the columns have to be fixed up front. They are [headers] if given;
 * otherwise they are the keys of the first record written. A key which isn't one of the columns is a [SinkError],
 * while a column the record doesn't have is written empty.
 *
 * Values are written with `toString`, which suits strings, numbers, booleans and dates. Maps and lists are a
 * [SinkError] rather than being written as something unreadable; convert them first (for example with `formatJson`).
 *
 * The header row is written along with the first record. If [headers] were given explicitly it is also written
 * when nothing else is, so an empty migration still produces a valid file.
 *
 * @param writeHeader whether to write the header row at all
 * @param delimiter the field delimiter; use `'\t'` for tab-separated data
 * @param quote the character used to quote fields. Inside a quoted field, a quote is doubled.
 * @param lineSeparator what ends each row
 * @param byteOrderMark whether to start the output with a UTF-8 byte order mark, which Excel needs in order to
 *   recognize UTF-8 files
 * @param closeWriter whether closing the sink also closes [writer]. (The writer is flushed either way.)
 */
class CsvSink(
    private val writer: Writer,
    private val headers: List<String>? = null,
    private val writeHeader: Boolean = true,
    private val delimiter: Char = ',',
    private val quote: Char = '"',
    private val lineSeparator: String = "\n",
    private val byteOrderMark: Boolean = false,
    private val closeWriter: Boolean = true,
) : Sink {
    private var columns: List<String>? = headers
    private var columnIndexes: Map<String, Int>? = null
    private var started = false

    override fun write(collector: Collector) {
        val columns = columns ?: collector.keys.map { it.toString() }.also { this.columns = it }
        val indexes = columnIndexes ?: columns.withIndex().associate { (i, name) -> name to i }.also {
            columnIndexes = it
        }
        start()

        val row = arrayOfNulls<Any?>(columns.size)
        for ((key, value) in collector) {
            val name = key.toString()
            val index = indexes[name]
                ?: throw SinkError("Column '$name' is not one of the CSV columns: ${columns.joinToString(", ")}")
            if (value is Map<*, *> || value is Iterable<*> || value is Array<*>) {
                throw SinkError("Column '$name' has a ${value::class.simpleName} value, which can't be written to CSV")
            }
            row[index] = value
        }
        writeRow(row.asList())
    }

    override fun close() {
        try {
            if (headers != null) start()
        } finally {
            if (closeWriter) writer.close() else writer.flush()
        }
    }

    /** Write the byte order mark and header row, if they haven't been written yet */
    private fun start() {
        if (started) return
        started = true
        if (byteOrderMark) writer.write(BOM)
        if (writeHeader) columns?.let { writeRow(it) }
    }

    private fun writeRow(fields: List<Any?>) {
        fields.forEachIndexed { i, field ->
            if (i > 0) writer.write(delimiter.code)
            writeField(field)
        }
        writer.write(lineSeparator)
    }

    private fun writeField(field: Any?) {
        if (field == null) return
        val text = field.toString()
        if (needsQuoting(text)) {
            writer.write(quote.code)
            writer.write(text.replace(quote.toString(), "$quote$quote"))
            writer.write(quote.code)
        } else {
            writer.write(text)
        }
    }

    private fun needsQuoting(text: String) =
        text.isEmpty() ||
            text.any { it == delimiter || it == quote || it == '\n' || it == '\r' } ||
            text.first().isWhitespace() || text.last().isWhitespace()

    companion object {
        private const val BOM = 0xFEFF

        /** Write to a CSV file. Takes the same options as the constructor. */
        fun open(
            path: Path,
            charset: Charset = Charsets.UTF_8,
            headers: List<String>? = null,
            writeHeader: Boolean = true,
            delimiter: Char = ',',
            quote: Char = '"',
            lineSeparator: String = "\n",
            byteOrderMark: Boolean = false,
        ) = CsvSink(
            Files.newBufferedWriter(path, charset),
            headers, writeHeader, delimiter, quote, lineSeparator, byteOrderMark,
        )
    }
}
