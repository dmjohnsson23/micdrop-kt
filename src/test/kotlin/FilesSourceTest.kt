package org.example

import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilesSourceTest {
    @TempDir
    lateinit var dir: Path

    @BeforeTest
    fun createFiles() {
        for (n in 1..9) dir.resolve("$n.json").writeText("""{"n": $n}""")
        dir.resolve("a.json").writeText("""{"name": "Lauren"}""")
        dir.resolve("notes.txt").writeText("hello")
        dir.resolve("sub/deeper").createDirectories()
        dir.resolve("sub/x.txt").writeText("x")
        dir.resolve("sub/deeper/y.txt").writeText("y")
    }

    private fun names(source: FilesSource) = source.entries().map { it.index }.toList()

    @Test
    fun iteratesAPresetListOfFiles() {
        val source = FilesSource(dir, files = listOf("a.json", "1.json"))
        check(listOf("a.json", "1.json"), names(source))
    }

    @Test
    fun iteratesAllFilesInADirectory() {
        // The subdirectory is skipped
        val source = FilesSource(dir)
        check((1..9).map { "$it.json" } + listOf("a.json", "notes.txt"), names(source))
    }

    @Test
    fun iteratesAGlob() {
        check((1..9).map { "$it.json" }, names(FilesSource(dir, glob = "[0-9].json")))
    }

    @Test
    fun globSupportsAlternatives() {
        check(listOf("a.json", "notes.txt"), names(FilesSource(dir, glob = "{a.json,notes.txt}")))
    }

    @Test
    fun globDoesNotRecurseWithoutDoubleStar() {
        check(listOf("notes.txt"), names(FilesSource(dir, glob = "*.txt")))
    }

    @Test
    fun globCanRecurse() {
        check(listOf("notes.txt", "sub/deeper/y.txt", "sub/x.txt"), names(FilesSource(dir, glob = "**.txt")))
        check(listOf("sub/deeper/y.txt", "sub/x.txt"), names(FilesSource(dir, glob = "sub/**.txt")))
    }

    @Test
    fun globSkipsDirectories() {
        check(emptyList<String>(), names(FilesSource(dir, glob = "sub")))
    }

    @Test
    fun acceptsADirectoryAsAString() {
        check(10 + 1, FilesSource(dir.toString()).entries().count())
    }

    @Test
    fun filesAndGlobAreMutuallyExclusive() {
        assertFailsWith<IllegalArgumentException> { FilesSource(dir, files = listOf("a.json"), glob = "*") }
    }

    @Test
    fun skipsMissingFiles() {
        val source = FilesSource(dir, files = listOf("a.json", "nope.json", "sub"))
        check(listOf("a.json"), names(source))
    }

    @Test
    fun canBeIteratedMoreThanOnce() {
        val source = FilesSource(dir, glob = "[0-9].json")
        check(9, source.entries().count())
        check(9, source.entries().count())
    }

    @Test
    fun seesFilesAddedBetweenIterations() {
        val source = FilesSource(dir, glob = "*.csv")
        check(0, source.entries().count())
        dir.resolve("new.csv").writeText("a,b")
        check(1, source.entries().count())
    }

    @Test
    fun worksAsASourceInAMigration() {
        val sink = mutableListOf<Collector>()
        migrate(FilesSource(dir, glob = "[0-9].json"), sink.asSink()) {
            index().put("file")
            take("content").parseJson { take("n").put("n") }
        }
        check(9, sink.size)
        check(mapOf("file" to "3.json", "n" to 3L), sink[2])
    }
}

class FileRecordTest {
    @TempDir
    lateinit var dir: Path

    private val json = """{"name": "Lauren Velasquez", "residency": "566 Cameron Ranch\nWest Kimberly, IA"}"""

    private fun record(name: String = "a.json", content: String = json): FileRecord {
        val path = dir.resolve(name)
        path.writeText(content)
        return FileRecord(path)
    }

    @Test
    fun readsContent() {
        check(json, record()["content"])
    }

    @Test
    fun readsBytes() {
        check(json.toByteArray().toList(), (record()["bytes"] as ByteArray).toList())
    }

    @Test
    fun opensAStream() {
        (record()["stream"] as java.io.InputStream).use { check(json, it.readBytes().decodeToString()) }
    }

    @Test
    fun decodesWithTheGivenCharset() {
        val path = dir.resolve("latin.txt")
        path.writeBytes("café".toByteArray(StandardCharsets.ISO_8859_1))
        check("café", FileRecord(path, StandardCharsets.ISO_8859_1)["content"])
    }

    @Test
    fun knowsThePath() {
        val record = record()
        check(dir.resolve("a.json").toString(), record["filename"])
        check("a.json", record["basename"])
        check("json", record["extension"])
        check(dir.resolve("a.json").toAbsolutePath().toString(), record["path"])
        check(dir.resolve("a.json"), record.raw)
    }

    @Test
    fun getsStats() {
        val record = record(content = "12345")
        check(5L, record["size"])
        assertTrue(record["mtime"] is Instant)
        assertTrue(record["ctime"] is Instant)
        assertTrue(record["atime"] is Instant)
    }

    @Test
    fun contentIsOnlyReadWhenTaken() {
        val record = record()
        dir.resolve("a.json").toFile().delete()
        assertTrue("content" in record)
        assertFailsWith<java.io.IOException> { record["content"] }
    }

    @Test
    fun unknownItemsDoNotExist() {
        val record = record()
        assertTrue("nope" !in record)
        assertNull(record["nope"])
        assertFailsWith<PipelineError> { record.fetch("nope") }
    }
}
