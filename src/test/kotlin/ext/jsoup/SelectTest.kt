package micdrop.ext.jsoup

import micdrop.Collector
import micdrop.PipelineError
import micdrop.asSink
import micdrop.check
import micdrop.itemOf
import micdrop.migrate
import micdrop.testRoot
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectTest {
    private fun catalog() = itemOf(CATALOG).parseXml()

    // region takeContent and takeNodeName

    @Test
    fun takesTheContentOfAnElement() {
        check("This is on fire!", itemOf("<p class='fire'>This is on fire!</p>").parseHtmlFragment().takeContent().value)
        check("Laptop Pro 15", catalog().atCss("name").takeContent().value)
    }

    @Test
    fun contentIsTakenExactlyAsWritten() {
        check(" x\n y ", itemOf("<a> x\n y </a>").parseXml().atCss("a").takeContent().value)
        check("x y", itemOf("<a> x y </a>").parseXml().atCss("a").takeContent().strip().value)
    }

    @Test
    fun contentIncludesEverythingInsideTheElement() {
        check("onetwo", itemOf("<a>one<b>two</b></a>").parseXml().atCss("a").takeContent().value)
    }

    @Test
    fun takesTheNameOfAnElement() {
        check("price", catalog().atCss("price").takeNodeName().value)
    }

    @Test
    fun takingFromNothingGivesNull() {
        assertNull(catalog().atCss("nope").takeContent().value)
        assertNull(catalog().atCss("nope").takeNodeName().value)
    }

    @Test
    fun contentPipelinesCanBeUsed() {
        val root = testRoot()
        itemOf(CATALOG, root).parseXml {
            atCss("price").takeContent {
                parseDouble()
                put("price")
            }
        }
        check(mapOf("price" to 1299.99), root.collector)
        check(1299.99, catalog().atCss("price").takeContent().parseDouble().value)
    }

    @Test
    fun takingContentFromSomethingElseIsAnError() {
        assertFailsWith<PipelineError> { itemOf(mapOf("a" to 1)).takeContent() }
        assertFailsWith<PipelineError> { itemOf(listOf("a")).takeNodeName() }
    }

    // endregion

    // region attributes

    @Test
    fun takesAttributesFromAnElement() {
        check("P001", catalog().atCss("product").take("id").value)
        check("USD", catalog().atCss("price").take("currency").value)
    }

    @Test
    fun anAttributeThatIsNotThereIsAnError() {
        assertFailsWith<PipelineError> { catalog().atCss("product").take("nope") }
        assertNull(catalog().atCss("product").tryTake("nope").value)
    }

    // endregion

    // region css

    @Test
    fun cssFindsAllMatches() {
        val products = catalog().css("product").value
        assertTrue(products is Elements)
        check(listOf("P001", "P002"), (products as Elements).map { it.attr("id") })
    }

    @Test
    fun cssFindsNothingWithoutAMatch() {
        check(0, (catalog().css("nope").value as Elements).size)
    }

    @Test
    fun cssSupportsTheUsualSelectors() {
        val doc = catalog()
        check(listOf("processor", "ram", "storage", "display", "graphics", "connectivity", "battery", "dpi", "buttons"),
            (doc.css("specifications > *").value as Elements).map { it.nodeName() })
        check(listOf("laptop-main.jpg", "mouse-main.jpg"),
            (doc.css("image[type=main]").value as Elements).map { it.text() })
        check(listOf("Wireless Mouse"), (doc.css("product[id=P002] > name").value as Elements).map { it.text() })
    }

    @Test
    fun atCssFindsTheFirstMatch() {
        check("Laptop Pro 15", catalog().atCss("name").takeContent().value)
        assertNull(catalog().atCss("nope").value)
    }

    @Test
    fun cssWorksInsideAnElementBlock() {
        val root = testRoot()
        itemOf(CATALOG, root).parseXml().atCss("product").enter {
            atCss("name").takeContent().put("name")
            atCss("price").takeContent().put("price")
        }
        check(mapOf("name" to "Laptop Pro 15", "price" to "1299.99"), root.collector)
    }

    @Test
    fun anInvalidSelectorIsAPipelineError() {
        val error = assertFailsWith<PipelineError> { catalog().css("product[") }
        assertTrue("CSS selector" in error.message!!, error.message)
        assertFailsWith<PipelineError> { catalog().atCss(">>") }
    }

    @Test
    fun cssOfNullIsNull() {
        assertNull(itemOf(null).css("a").value)
    }

    @Test
    fun cssOfSomethingElseIsAnError() {
        assertFailsWith<PipelineError> { itemOf(mapOf("a" to 1)).css("a") }
    }

    // endregion

    // region xpath

    @Test
    fun xpathFindsAllMatches() {
        val names = catalog().xpath("//product/name").value as Elements
        check(listOf("Laptop Pro 15", "Wireless Mouse"), names.map { it.text() })
    }

    @Test
    fun xpathCanFilter() {
        check("Wireless Mouse", catalog().atXpath("//product[@id='P002']/name").takeContent().value)
        check(listOf("mouse-main.jpg"), (catalog().xpath("//product[@id='P002']/images/image[@type='main']").value as Elements).map { it.text() })
    }

    @Test
    fun xpathFindsNothingWithoutAMatch() {
        check(0, (catalog().xpath("//nope").value as Elements).size)
        assertNull(catalog().atXpath("//nope").value)
    }

    @Test
    fun anInvalidXPathIsAPipelineError() {
        val error = assertFailsWith<PipelineError> { catalog().xpath("//product[") }
        assertTrue("XPath" in error.message!!, error.message)
    }

    @Test
    fun xpathOfNullIsNull() {
        assertNull(itemOf(null).atXpath("//a").value)
    }

    // endregion

    // region in a migration

    private fun products(): List<Collector> {
        val document = parseXmlDocument(CATALOG)
        val sink = mutableListOf<Collector>()
        migrate(document.select("product").asSource(), sink.asSink()) {
            take("id").put("code")
            atCss("name").takeContent().put("name")
            atCss("category").takeContent().put("category")
            atCss("price").takeContent {
                parseDouble()
                put("price")
            }
            atCss("quantity").takeContent().parseInt().put("stock")
        }
        return sink
    }

    @Test
    fun theRubyProductsExample() {
        check(
            listOf(
                mapOf("code" to "P001", "name" to "Laptop Pro 15", "category" to "Electronics", "price" to 1299.99, "stock" to 45),
                mapOf("code" to "P002", "name" to "Wireless Mouse", "category" to "Accessories", "price" to 29.99, "stock" to 230),
            ),
            products(),
        )
    }

    @Test
    fun theRubySpecsExample() {
        val document = parseXmlDocument(CATALOG)
        val names = mapOf("processor" to "CPU", "ram" to "Memory", "storage" to "Storage", "display" to "Screen",
            "graphics" to "GPU", "connectivity" to "Connectivity", "battery" to "Battery", "dpi" to "Screen DPI",
            "buttons" to "Button Count")
        val sink = mutableListOf<Collector>()
        migrate(document.select("product").asSource(), sink.asSink()) {
            val code = take("id")
            css("specifications > *").eachSubrecord(flush = true, reset = true) {
                code.put("code")
                takeNodeName {
                    lookup(names)
                    put("key")
                }
                takeContent().put("value")
            }
        }
        check(9, sink.size)
        check(mapOf("code" to "P001", "key" to "CPU", "value" to "Intel Core i7-13700H"), sink.first())
        check(mapOf("code" to "P002", "key" to "Button Count", "value" to "6"), sink.last())
        check(5, sink.count { it["code"] == "P001" })
    }

    @Test
    fun xmlInsideAnotherSource() {
        val rows = listOf(mapOf("id" to 1, "payload" to "<order><item sku='A'>2</item><item sku='B'>5</item></order>"))
        val sink = mutableListOf<Collector>()
        migrate(rows, sink) {
            val id = take("id")
            take("payload").parseXml().css("item").eachSubrecord(flush = true, reset = true) {
                id.put("order")
                take("sku").put("sku")
                takeContent().parseInt().put("qty")
            }
        }
        check(
            listOf(mapOf("order" to 1, "sku" to "A", "qty" to 2), mapOf("order" to 1, "sku" to "B", "qty" to 5)),
            sink,
        )
    }

    @Test
    fun anElementsListIsASource() {
        val document = parseXmlDocument(CATALOG)
        val ids = document.select("product").asSource().entries().map { (it.record.raw as Element).attr("id") }.toList()
        check(listOf("P001", "P002"), ids)
    }

    // endregion
}
