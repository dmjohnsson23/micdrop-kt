package org.example

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * Takes the files in a directory as a source, with one record per file, indexed by the file's name relative to the
 * directory. Use at most one of [files] and [glob]; with neither, every entry directly in [dir] is used.
 *
 * @param files file names (relative to [dir]) to use
 * @param glob a [java.nio.file.FileSystem.getPathMatcher] glob, matched against paths relative to [dir]. Supports
 *   `**`, `{a,b}` and `[0-9]`. Only regular files match.
 * @param charset how to decode `content`
 *
 * See [FileRecord] for the items available to `take`. Anything that isn't a regular file is skipped with a warning.
 */
class FilesSource(
    private val dir: Path,
    private val files: List<String>? = null,
    private val glob: String? = null,
    private val charset: Charset = Charsets.UTF_8,
) : Source {
    constructor(
        dir: String,
        files: List<String>? = null,
        glob: String? = null,
        charset: Charset = Charsets.UTF_8,
    ) : this(Path.of(dir), files, glob, charset)

    init {
        require(files == null || glob == null) { "Specify either files or glob, not both" }
    }

    override fun entries(): Sequence<SourceEntry> = names().asSequence().mapNotNull { name ->
        val path = dir.resolve(name)
        if (path.isRegularFile()) {
            SourceEntry(name, FileRecord(path, charset))
        } else {
            System.err.println("$path is not a file and will be skipped")
            null
        }
    }

    private fun names(): List<String> = when {
        files != null -> files
        glob != null -> {
            val matcher = dir.fileSystem.getPathMatcher("glob:$glob")
            Files.walk(dir).use { paths ->
                paths.map { dir.relativize(it) }
                    .filter { it.toString().isNotEmpty() && matcher.matches(it) && dir.resolve(it).isRegularFile() }
                    .map { it.invariantSeparatorsPathString }
                    .sorted()
                    .toList()
            }
        }
        else -> dir.listDirectoryEntries().map { it.name }.sorted()
    }
}

/**
 * A file as a record. Items available to `take`:
 *
 * * `content`: the text of the file (only read if you take it)
 * * `bytes`: the raw bytes of the file
 * * `stream`: an open `InputStream` on the file. The caller is responsible for closing it.
 * * `filename`: the path as built from the source directory, e.g. `files/x.json`
 * * `basename`: the last part of the name, e.g. `x.json`
 * * `extension`: e.g. `json`
 * * `path`: the absolute path
 * * `size` (bytes), and `mtime`, `ctime` and `atime` (as `Instant`s)
 */
class FileRecord(override val raw: Path, private val charset: Charset = Charsets.UTF_8) : Record {
    private val attributes by lazy { Files.readAttributes(raw, BasicFileAttributes::class.java) }

    override fun contains(key: Any) = key in KEYS

    override fun get(key: Any): Any? = when (key) {
        "content" -> raw.readText(charset)
        "bytes" -> raw.readBytes()
        "stream" -> raw.inputStream()
        "filename" -> raw.toString()
        "basename" -> raw.name
        "extension" -> raw.extension
        "path" -> raw.toAbsolutePath().toString()
        "size" -> attributes.size()
        "mtime" -> attributes.lastModifiedTime().toInstant()
        "ctime" -> attributes.creationTime().toInstant()
        "atime" -> attributes.lastAccessTime().toInstant()
        else -> null
    }

    private companion object {
        val KEYS = setOf(
            "content", "bytes", "stream", "filename", "basename", "extension", "path", "size", "mtime", "ctime", "atime",
        )
    }
}
