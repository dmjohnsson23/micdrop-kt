package micdrop.ext

import micdrop.Collector
import micdrop.Record
import micdrop.Sink
import micdrop.Source
import micdrop.SourceEntry
import micdrop.SourceError
import micdrop.ValueError
import micdrop.formatJsonValue
import micdrop.parseJsonValue
import java.io.Reader
import java.io.Writer
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

/**
 * A source of JSON-Lines (ND-JSON) data: one JSON object or array per line. Blank lines are ignored. Records are
 * indexed by their position, starting from 0.
 *
 * The data can only be read once, since it is streamed from [reader].
 *
 * @param closeReader whether closing the source also closes [reader]
 * @param onClose runs when the source is closed, and is told whether all the data was read
 */
class JsonLinesSource(
    private val reader: Reader,
    private val closeReader: Boolean = true,
    private val onClose: (completed: Boolean) -> Unit = {},
) : Source {
    private var completed = false

    override fun entries(): Sequence<SourceEntry> = sequence {
        var index = 0
        var lineNumber = 0
        for (line in reader.buffered().lineSequence()) {
            lineNumber++
            if (line.isBlank()) continue
            val parsed = try {
                parseJsonValue(line)
            } catch (e: ValueError) {
                throw SourceError("Line $lineNumber: ${e.message}", e)
            }
            val record = parsed?.let { Record.adapt(it) }
                ?: throw SourceError("Line $lineNumber is not a JSON object or array")
            yield(SourceEntry(index++, record))
        }
        completed = true
    }

    override fun close() {
        try {
            if (closeReader) reader.close()
        } finally {
            onClose(completed)
        }
    }

    companion object {
        fun open(path: Path, charset: Charset = Charsets.UTF_8) = JsonLinesSource(Files.newBufferedReader(path, charset))

        /**
         * Run a command and read its standard output as JSON-Lines. Closing the source waits for the command to
         * exit (stopping it first if the output wasn't read to the end), and fails if it exited abnormally after
         * all its output had been read.
         */
        fun fromCommand(vararg command: String): JsonLinesSource {
            val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            process.outputStream.close()
            return JsonLinesSource(process.inputStream.reader()) { completed ->
                if (!completed) process.destroy()
                val status = process.waitFor()
                if (completed && status != 0) {
                    throw SourceError("Command '${command.joinToString(" ")}' exited with status $status")
                }
            }
        }
    }
}

/**
 * A sink which writes each record as a line of JSON-Lines (ND-JSON).
 *
 * @param closeWriter whether closing the sink also closes [writer]. (The writer is flushed either way.)
 */
class JsonLinesSink(
    private val writer: Writer,
    private val closeWriter: Boolean = true,
) : Sink {
    override fun write(collector: Collector) {
        writer.write(formatJsonValue(collector))
        writer.write("\n")
    }

    override fun close() {
        if (closeWriter) writer.close() else writer.flush()
    }

    companion object {
        fun open(path: Path, charset: Charset = Charsets.UTF_8) = JsonLinesSink(Files.newBufferedWriter(path, charset))
    }
}
