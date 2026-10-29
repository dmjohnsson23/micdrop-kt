package micdrop

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZipSourceTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var zip: Path

    private fun makeZip(vararg entries: Pair<String, String>): Path {
        val path = dir.resolve("test-${System.nanoTime()}.zip")
        ZipOutputStream(path.outputStream()).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return path
    }

    @BeforeTest
    fun createZip() {
        zip = makeZip("a.txt" to "alpha", "sub/" to "", "sub/b.json" to """{"n": 2}""", "sub/c.txt" to "gamma")
    }

    private fun names(source: ZipSource) = source.entries().map { it.index }.toList()

    @Test
    fun iteratesFilesButNotDirectories() {
        ZipSource(zip).use { check(listOf("a.txt", "sub/b.json", "sub/c.txt"), names(it)) }
    }

    @Test
    fun iteratesAGlob() {
        ZipSource(zip, glob = "sub/*.txt").use { check(listOf("sub/c.txt"), names(it)) }
    }

    @Test
    fun readsItems() {
        ZipSource(zip).use { source ->
            val record = source.entries().first { it.index == "sub/b.json" }.record
            check("""{"n": 2}""", record["content"])
            check("sub/b.json", record["filename"])
            check("b.json", record["basename"])
            check("json", record["extension"])
            check(8L, record["size"])
        }
    }

    @Test
    fun worksAsASourceInAMigration() {
        val sink = mutableListOf<Collector>()
        migrate(ZipSource(zip, glob = "**.json"), sink.asSink()) {
            take("basename").put("file")
            take("content").parseJson { take("n").put("n") }
        }
        check(listOf(mapOf("file" to "b.json", "n" to 2L)), sink)
    }

    @Test
    fun extractsAnEntry() {
        val out = dir.resolve("out")
        ZipSource(zip).use { source ->
            val record = source.entries().first { it.index == "sub/c.txt" }.record as ZipEntryRecord
            val path = record.extractTo(out)
            check("gamma", path.readText())
            assertTrue(path.endsWith(Path.of("sub", "c.txt")))
        }
    }

    @Test
    fun refusesToExtractOutsideTheDirectory() {
        val evil = makeZip("../evil.txt" to "x")
        ZipSource(evil).use { source ->
            val record = source.entries().single().record as ZipEntryRecord
            assertFailsWith<SinkError> { record.extractTo(dir.resolve("out")) }
        }
    }
}
