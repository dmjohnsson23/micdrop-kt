package micdrop.ext.jsoup

import micdrop.Collector
import micdrop.asSink
import micdrop.check
import micdrop.migrate
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test

/** Runs the HTML and XML examples from the README and docs */
class MarkupReadmeExamplesTest {
    @TempDir
    lateinit var dir: Path

    private fun catalogFile(): Path = dir.resolve("catalog.xml").also { it.writeText(CATALOG) }

    @Test
    fun streamingAnXmlFile() {
        val sink = mutableListOf<Collector>()
        migrate(XmlSource.open(catalogFile(), "catalog/product"), sink.asSink()) {
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
    fun specificationsPerProduct() {
        val sink = mutableListOf<Collector>()
        migrate(XmlSource.open(catalogFile(), "product"), sink.asSink()) {
            val code = take("id")
            css("specifications > *").eachSubrecord(flush = true, reset = true) {
                code.put("code")
                takeNodeName().put("spec")
                takeContent().put("value")
            }
        }
        check(9, sink.size)
        check(mapOf("code" to "P001", "spec" to "ram", "value" to "16GB DDR5"), sink[1])
        check(mapOf("code" to "P002", "spec" to "buttons", "value" to "6"), sink.last())
    }

    @Test
    fun markupInAValue() {
        val rows = listOf(
            mapOf(
                "notes_html" to "<div><p class='summary'>Great <b>value</b></p><a href='/more'>more</a></div>",
                "order_xml" to "<order><item sku='A'>2</item><item sku='B'>5</item></order>",
            ),
        )
        val sink = mutableListOf<Collector>()
        migrate(rows, sink) {
            take("notes_html").parseHtml {
                atCss(".summary").takeContent().put("summary")
                atCss("a").take("href").put("link")
            }
            take("order_xml").parseXml {
                css("item").eachSubrecord(flush = true, reset = true) {
                    take("sku").put("sku")
                    takeContent().parseInt().put("quantity")
                }
            }
        }
        // The first two values were put into the first record, which the sub-record flushes carry out with the first item
        check(
            listOf(
                mapOf("summary" to "Great value", "link" to "/more", "sku" to "A", "quantity" to 2),
                mapOf("sku" to "B", "quantity" to 5),
            ),
            sink,
        )
    }

    @Test
    fun aDocumentLoadedWholeAsASource() {
        val document = parseXmlDocument(catalogFile().readText())
        val sink = mutableListOf<Collector>()
        migrate(document.select("product").asSource(), sink.asSink()) { take("id").put("code") }
        check(listOf(mapOf("code" to "P001"), mapOf("code" to "P002")), sink)
    }
}
