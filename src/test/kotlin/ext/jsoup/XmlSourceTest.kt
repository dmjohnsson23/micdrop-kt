package micdrop.ext.jsoup

import micdrop.Collector
import micdrop.SourceError
import micdrop.asSink
import micdrop.check
import micdrop.migrate
import org.jsoup.nodes.Element
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class TrackingReader(text: String) : Reader() {
    private val inner = StringReader(text)
    var closed = false
    var charsRead = 0
    override fun read(cbuf: CharArray, off: Int, len: Int) = inner.read(cbuf, off, len).also { if (it > 0) charsRead += it }
    override fun close() { closed = true; inner.close() }
}

private class TrackingStream(bytes: ByteArray) : InputStream() {
    private val inner = ByteArrayInputStream(bytes)
    var closed = false
    override fun read() = inner.read()
    override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, len)
    override fun close() { closed = true }
}

private val FEED = """<?xml version="1.0"?>
<feed xmlns="urn:feed" xmlns:m="urn:media">
  <entry id="1"><title>One &amp; Only</title><m:thumb url="a.jpg"/></entry>
  <!-- a note -->
  <entry id="2"><title><![CDATA[Two < Three]]></title></entry>
  <group><entry id="3"><title>Nested</title></entry></group>
</feed>"""

class XmlSourceTest {
    @TempDir
    lateinit var dir: Path

    private fun records(xml: String, element: String): List<Element> =
        XmlSource(StringReader(xml), element).entries().map { it.record.raw as Element }.toList()

    private fun ids(xml: String, element: String) = records(xml, element).map { it.attr("id") }

    // region choosing the elements

    @Test
    fun anElementNameMatchesAtAnyDepth() {
        check(listOf("1", "2", "3"), ids(FEED, "entry"))
    }

    @Test
    fun aPathMatchesAtItsEnd() {
        check(listOf("1", "2"), ids(FEED, "feed/entry"))
        check(listOf("3"), ids(FEED, "group/entry"))
        check(listOf("3"), ids(FEED, "feed/group/entry"))
    }

    @Test
    fun aLeadingSlashAnchorsThePathAtTheRoot() {
        check(listOf("1", "2"), ids(FEED, "/feed/entry"))
        check(listOf("3"), ids(FEED, "/feed/group/entry"))
        check(emptyList<String>(), ids(FEED, "/entry"))
        check(emptyList<String>(), ids(FEED, "/group/entry"))
    }

    @Test
    fun theRootElementCanBeTheRecord() {
        val records = records(FEED, "/feed")
        check(1, records.size)
        check(3, records.single().select("entry").size)
    }

    @Test
    fun elementsInsideARecordAreNotRecordsOfTheirOwn() {
        val xml = """<r><a id="1"><a id="2"/><a id="3"/></a><a id="4"/></r>"""
        check(listOf("1", "4"), ids(xml, "a"))
        check(2, records(xml, "a").first().select("a").size - 1) // the record, plus its two nested ones, minus itself
    }

    @Test
    fun noMatchesMeansNoRecords() {
        check(0, records(FEED, "nothing").size)
        check(0, records("<r/>", "r/x").size)
    }

    @Test
    fun namespacePrefixesAreIgnoredInNames() {
        check(listOf("a.jpg"), records(FEED, "thumb").map { it.attr("url") })
    }

    @Test
    fun entriesAreIndexedFromZero() {
        check(listOf(0, 1, 2), XmlSource(StringReader(FEED), "entry").entries().map { it.index }.toList())
    }

    @Test
    fun invalidPathsAreRejected() {
        assertFailsWith<IllegalArgumentException> { XmlSource(StringReader("<r/>"), "") }
        assertFailsWith<IllegalArgumentException> { XmlSource(StringReader("<r/>"), "a//b") }
        assertFailsWith<IllegalArgumentException> { XmlSource(StringReader("<r/>"), "/") }
    }

    // endregion

    // region what records contain

    @Test
    fun attributesAreItems() {
        val entry = XmlSource(StringReader(FEED), "entry").entries().first().record
        check("1", entry["id"])
        assertTrue("id" in entry)
    }

    @Test
    fun childrenAreFoundWithSelectors() {
        val entry = records(FEED, "entry").first()
        check("One & Only", entry.selectFirst("title")?.wholeText())
    }

    @Test
    fun cdataBecomesText() {
        check("Two < Three", records(FEED, "entry")[1].selectFirst("title")?.wholeText())
    }

    @Test
    fun namespaceDeclarationsInForceAreCarriedIntoTheRecord() {
        val entry = records(FEED, "entry").first()
        check("a.jpg", entry.selectFirst("m|thumb")?.attr("url"))
        val html = entry.outerHtml()
        assertTrue("xmlns:m=\"urn:media\"" in html, html)
        assertTrue("xmlns=\"urn:feed\"" in html, html)
    }

    @Test
    fun namespaceDeclarationsFromAnyAncestorAreCarried() {
        val xml = """<r xmlns:a="urn:a"><mid xmlns:b="urn:b"><e><a:x/><b:y/></e></mid></r>"""
        val html = records(xml, "e").single().outerHtml()
        assertTrue("xmlns:a=\"urn:a\"" in html && "xmlns:b=\"urn:b\"" in html, html)
    }

    @Test
    fun aNearerDeclarationOverridesAFurtherOne() {
        val xml = """<r xmlns:a="urn:outer"><e xmlns:a="urn:inner"><a:x/></e></r>"""
        val html = records(xml, "e").single().outerHtml()
        assertTrue("urn:inner" in html && "urn:outer" !in html, html)
    }

    @Test
    fun attributeValuesSurviveAwkwardCharacters() {
        val xml = "<r><e v=\"a &quot;b&quot; &lt;c&gt; &amp; d&#10;e\"/></r>"
        check("a \"b\" <c> & d\ne", records(xml, "e").single().attr("v"))
    }

    @Test
    fun textSurvivesAwkwardCharacters() {
        val xml = "<r><e>1 &lt; 2 &amp;&amp; 3 &gt; 2 &#169; é</e></r>"
        check("1 < 2 && 3 > 2 © é", records(xml, "e").single().wholeText())
    }

    @Test
    fun commentsAndProcessingInstructionsDoNotGetInTheWay() {
        val record = records("<r><e><!--c--><?pi data?>text</e></r>", "e").single()
        check("text", record.wholeText())
    }

    @Test
    fun whitespaceIsKept() {
        check("\n  a \n", records("<r><e>\n  a \n</e></r>", "e").single().wholeText())
    }

    @Test
    fun emptyElementsAreRecords() {
        check(listOf("1", "2"), ids("""<r><e id="1"/><e id="2"></e></r>""", "e"))
    }

    // endregion

    // region bad input

    @Test
    fun malformedXmlIsASourceErrorNamingTheLine() {
        val error = assertFailsWith<SourceError> { records("<root>\n<item>\n</root>", "item") }
        assertTrue("line 3" in error.message!!, error.message)
        assertTrue("Invalid XML" in error.message!!, error.message)
    }

    @Test
    fun recordsBeforeTheErrorAreStillDelivered() {
        val source = XmlSource(StringReader("<r><e id='1'/><e id='2'/><e id='3'></r>"), "e")
        val seen = mutableListOf<String>()
        assertFailsWith<SourceError> { source.entries().forEach { seen.add((it.record.raw as Element).attr("id")) } }
        check(listOf("1", "2"), seen)
    }

    @Test
    fun anUnfinishedDocumentIsAnError() {
        assertFailsWith<SourceError> { records("<r><e/>", "e") }
        assertFailsWith<SourceError> { records("", "e") }
        assertFailsWith<SourceError> { records("not xml at all", "e") }
    }

    @Test
    fun undeclaredEntitiesAreAnError() {
        assertFailsWith<SourceError> { records("<r><e>&undefined;</e></r>", "e") }
    }

    @Test
    fun externalEntitiesAreNeverRead() {
        val secret = dir.resolve("secret.txt")
        secret.writeText("TOPSECRET")
        val xml = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "${secret.toUri()}">]><r><e>&x;</e></r>"""
        val outcome = runCatching { records(xml, "e").map { it.wholeText() } }
        outcome.onSuccess { texts -> assertTrue(texts.none { "TOPSECRET" in it }, texts.toString()) }
        // Either it was refused outright, or the entity was left out; what must not happen is for the file to be read
        outcome.onFailure { assertTrue(it is SourceError, it.toString()) }
    }

    // endregion

    // region input and lifecycle

    @Test
    fun readsFromAStreamUsingItsDeclaredEncoding() {
        val bytes = """<?xml version="1.0" encoding="ISO-8859-1"?><r><e>café</e></r>""".toByteArray(StandardCharsets.ISO_8859_1)
        val texts = XmlSource(ByteArrayInputStream(bytes), "e").entries().map { (it.record.raw as Element).wholeText() }.toList()
        check(listOf("café"), texts)
    }

    @Test
    fun readsUtf8ByDefaultAndIgnoresAByteOrderMark() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + "<r><e>café €</e></r>".toByteArray(StandardCharsets.UTF_8)
        val texts = XmlSource(ByteArrayInputStream(bytes), "e").entries().map { (it.record.raw as Element).wholeText() }.toList()
        check(listOf("café €"), texts)
    }

    @Test
    fun opensAFile() {
        val path = dir.resolve("feed.xml")
        path.writeText(FEED)
        XmlSource.open(path, "entry").use { source ->
            check(listOf("1", "2", "3"), source.entries().map { (it.record.raw as Element).attr("id") }.toList())
        }
    }

    @Test
    fun opensAFileInItsOwnEncoding() {
        val path = dir.resolve("latin.xml")
        path.writeBytes("""<?xml version="1.0" encoding="ISO-8859-1"?><r><e>naïve</e></r>""".toByteArray(StandardCharsets.ISO_8859_1))
        XmlSource.open(path, "e").use { source ->
            check("naïve", (source.entries().single().record.raw as Element).wholeText())
        }
    }

    @Test
    fun closesTheInput() {
        val stream = TrackingStream("<r><e/></r>".toByteArray())
        val source = XmlSource(stream, "e")
        source.entries().toList()
        source.close()
        assertTrue(stream.closed)
    }

    @Test
    fun canLeaveTheInputOpen() {
        val reader = TrackingReader("<r><e/></r>")
        XmlSource(reader, "e", closeInput = false).close()
        check(false, reader.closed)
    }

    @Test
    fun isLazy() {
        val reader = TrackingReader("<r>" + (1..20000).joinToString("") { "<e id='$it'><v>some text in $it</v></e>" } + "</r>")
        val first = XmlSource(reader, "e").entries().first()
        check("1", (first.record.raw as Element).attr("id"))
        assertTrue(reader.charsRead < 40000, "read ${reader.charsRead} characters")
    }

    @Test
    fun streamsAFileLargerThanItNeedsToHold() {
        val path = dir.resolve("big.xml")
        path.toFile().bufferedWriter().use { out ->
            out.write("<catalog>\n")
            repeat(30_000) { i ->
                out.write("<product id=\"P$i\"><name>Item $i</name><specs><ram>16GB</ram></specs></product>\n")
            }
            out.write("</catalog>\n")
        }
        var count = 0
        var last = ""
        XmlSource.open(path, "catalog/product").use { source ->
            for (entry in source.entries()) {
                val product = entry.record.raw as Element
                check("Item $count", product.selectFirst("name")?.text())
                last = product.attr("id")
                count++
            }
        }
        check(30_000, count)
        check("P29999", last)
    }

    // endregion

    // region in a migration

    @Test
    fun migratesFromAStream() {
        val path = dir.resolve("catalog.xml")
        path.writeText(CATALOG)
        val sink = mutableListOf<Collector>()
        migrate(XmlSource.open(path, "catalog/product"), sink.asSink()) {
            take("id").put("code")
            atCss("name").takeContent().put("name")
            atCss("price") {
                take("currency").put("currency")
                takeContent().parseDouble().put("price")
            }
        }
        check(
            listOf(
                mapOf("code" to "P001", "name" to "Laptop Pro 15", "currency" to "USD", "price" to 1299.99),
                mapOf("code" to "P002", "name" to "Wireless Mouse", "currency" to "USD", "price" to 29.99),
            ),
            sink,
        )
    }

    @Test
    fun stoppingEarlyClosesTheInput() {
        val stream = TrackingStream(CATALOG.toByteArray())
        val sink = mutableListOf<Collector>()
        migrate(XmlSource(stream, "product"), sink.asSink()) {
            take("id") { put("id"); stopIf("P002") }
        }
        check(1, sink.size)
        assertTrue(stream.closed)
    }

    @Test
    fun badXmlFailsTheMigration() {
        val sink = mutableListOf<Collector>()
        assertFailsWith<SourceError> {
            migrate(XmlSource(StringReader("<r><e id='1'/><e id='2'></r>"), "e"), sink.asSink()) { take("id").put("id") }
        }
    }

    // endregion
}
