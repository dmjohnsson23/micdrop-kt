package org.example

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * Takes the files inside a zip archive as a source, with one record per file, indexed by the entry's full name.
 * Directory entries are skipped. With a [glob] (see [FilesSource]), only entries whose names match are used.
 *
 * See [ZipEntryRecord] for the items available to `take`, and for extracting an entry to disk. Closing the source
 * closes the archive.
 */
class ZipSource(
    private val path: Path,
    private val glob: String? = null,
    private val charset: Charset = Charsets.UTF_8,
) : Source {
    constructor(path: String, glob: String? = null, charset: Charset = Charsets.UTF_8) :
        this(Path.of(path), glob, charset)

    private val zip by lazy { ZipFile(path.toFile(), charset) }

    override fun entries(): Sequence<SourceEntry> {
        val matcher = glob?.let { path.fileSystem.getPathMatcher("glob:$it") }
        return zip.entries().asSequence()
            .filter { !it.isDirectory && (matcher == null || matcher.matches(Path.of(it.name))) }
            .map { SourceEntry(it.name, ZipEntryRecord(zip, it)) }
    }

    override fun close() {
        zip.close()
    }
}

/**
 * A zip entry as a record. Items available to `take`:
 *
 * * `content`: the text of the entry (decoded as UTF-8; only read if you take it)
 * * `bytes`: the raw bytes
 * * `stream`: an open `InputStream` on the entry. The caller is responsible for closing it.
 * * `filename`: the full name within the archive, e.g. `docs/x.pdf`
 * * `basename`: the last part of the name, e.g. `x.pdf`
 * * `extension`: e.g. `pdf`
 * * `size` (uncompressed bytes), and `mtime` (as an `Instant`, or null if the archive doesn't say)
 *
 * [extractTo] writes the entry to disk. A migration block can reach it with `loopItem as ZipEntryRecord`.
 */
class ZipEntryRecord(private val zip: ZipFile, override val raw: ZipEntry) : Record {
    override fun contains(key: Any) = key in KEYS

    override fun get(key: Any): Any? = when (key) {
        "content" -> zip.getInputStream(raw).use { String(it.readBytes(), Charsets.UTF_8) }
        "bytes" -> zip.getInputStream(raw).use { it.readBytes() }
        "stream" -> zip.getInputStream(raw)
        "filename" -> raw.name
        "basename" -> Path.of(raw.name).name
        "extension" -> Path.of(raw.name).extension
        "size" -> raw.size
        "mtime" -> raw.lastModifiedTime?.toInstant()
        else -> null
    }

    /**
     * Write the entry into [dir] under its name in the archive, creating directories as needed and replacing any
     * existing file. Returns the path written. An entry whose name would land outside [dir] is a [SinkError].
     */
    fun extractTo(dir: Path): Path {
        val base = dir.toAbsolutePath().normalize()
        val target = base.resolve(raw.name).normalize()
        if (!target.startsWith(base)) throw SinkError("Zip entry '${raw.name}' would be extracted outside $dir")
        target.parent.createDirectories()
        zip.getInputStream(raw).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        return target
    }

    private companion object {
        val KEYS = setOf("content", "bytes", "stream", "filename", "basename", "extension", "size", "mtime")
    }
}
