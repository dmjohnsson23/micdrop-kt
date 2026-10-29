package micdrop.ext.jsoup

import micdrop.Collector
import micdrop.PipelineError
import micdrop.Record
import micdrop.ValueError
import micdrop.asSink
import micdrop.check
import micdrop.itemOf
import micdrop.migrate
import micdrop.testRoot
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal val CATALOG = """<?xml version="1.0" encoding="UTF-8"?>
<catalog>
  <product id="P001">
    <name>Laptop Pro 15</name>
    <category>Electronics</category>
    <price currency="USD">1299.99</price>
    <inStock>true</inStock>
    <quantity>45</quantity>
    <specifications>
      <processor>Intel Core i7-13700H</processor>
      <ram>16GB DDR5</ram>
      <storage>512GB NVMe SSD</storage>
      <display>15.6" FHD IPS</display>
      <graphics>NVIDIA RTX 4060</graphics>
    </specifications>
    <images>
      <image type="thumbnail">laptop-thumb.jpg</image>
      <image type="main">laptop-main.jpg</image>
    </images>
  </product>
  <product id="P002">
    <name>Wireless Mouse</name>
    <category>Accessories</category>
    <price currency="USD">29.99</price>
    <inStock>true</inStock>
    <quantity>230</quantity>
    <specifications>
      <connectivity>Bluetooth 5.0</connectivity>
      <battery>2x AA</battery>
      <dpi>1600</dpi>
      <buttons>6</buttons>
    </specifications>
    <images>
      <image type="thumbnail">mouse-thumb.jpg</image>
      <image type="main">mouse-main.jpg</image>
    </images>
  </product>
</catalog>"""

private const val HTML_PAGE = "<!doctype html><html><head></head><body>" +
    "<article id='beans' class='product-listing'><h1>Dried Beans</h1><p class='price'>${'$'}999.99</p></article>" +
    "</body></html>"

class ElementRecordTest {
    private val product = parseXmlDocument(CATALOG).selectFirst("product")!!

    @Test
    fun attributesAreItems() {
        val record = Record.of(product)
        check("P001", record["id"])
        check("P001", record.fetch("id"))
        assertTrue("id" in record)
    }

    @Test
    fun missingAttributesAreMissing() {
        val record = Record.of(product)
        assertTrue("nope" !in record)
        assertNull(record["nope"])
        assertFailsWith<PipelineError> { record.fetch("nope") }
    }

    @Test
    fun onlyNamesAreAttributes() {
        assertTrue(0 !in Record.of(product))
    }

    @Test
    fun anEmptyAttributeIsStillThere() {
        val record = Record.of(parseXmlDocument("<a flag=''/>").child(0))
        assertTrue("flag" in record)
        check("", record["flag"])
    }

    @Test
    fun documentsAreRecords() {
        check(product.ownerDocument(), Record.of(product.ownerDocument()!!).raw)
    }

    @Test
    fun registeringTwiceIsHarmless() {
        registerJsoupRecords()
        registerJsoupRecords()
        check("P001", Record.of(product)["id"])
    }
}

class ParseMarkupTest {
    @Test
    fun parseXmlHandlesNull() {
        val ctx = itemOf(null)
        assertSame(ctx, ctx.parseXml())
        assertNull(ctx.value)
    }

    @Test
    fun parseXmlMakesADocument() {
        val ctx = itemOf(CATALOG).parseXml()
        assertTrue(ctx.value is Document)
    }

    @Test
    fun parseXmlEntersTheDocument() {
        val root = testRoot()
        itemOf(CATALOG, root).parseXml {
            atCss("product").take("id").put("first")
        }
        check(mapOf("first" to "P001"), root.collector)
    }

    @Test
    fun parseXmlKeepsTheCaseOfNames() {
        val ctx = itemOf("<Root><MixedCase>x</MixedCase></Root>").parseXml()
        check("MixedCase", ctx.atCss("MixedCase").takeNodeName().value)
        // (CSS selectors for tag names don't care about case, though)
        check("MixedCase", ctx.atCss("mixedcase").takeNodeName().value)
    }

    @Test
    fun parseXmlRejectsMalformedXmlByDefault() {
        val error = assertFailsWith<ValueError> { itemOf("<a><b></a>").parseXml() }
        assertTrue("Invalid XML" in error.message!!, error.message)
    }

    @Test
    fun parseXmlRejectsOtherDefects() {
        assertFailsWith<ValueError> { itemOf("<a>").parseXml() }
        assertFailsWith<ValueError> { itemOf("<a></a><b></b>").parseXml() }
        assertFailsWith<ValueError> { itemOf("<a attr=1/>").parseXml() }
        assertFailsWith<ValueError> { itemOf("<a>&undefined;</a>").parseXml() }
        assertFailsWith<ValueError> { itemOf("plain text").parseXml() }
    }

    @Test
    fun parseXmlCanBeLenient() {
        val document = itemOf("<a><b></a>").parseXml(strict = false).value as Document
        check(true, document.selectFirst("b") != null)
    }

    @Test
    fun parseXmlFailuresCanBeRecovered() {
        assertNull(itemOf("<a><b></a>").pipeOrSetNull { parseXml() }.value)
    }

    @Test
    fun parseXmlIgnoresAByteOrderMark() {
        val bom = 0xFEFF.toChar()
        check("x", ((itemOf("$bom<a>x</a>").parseXml().value) as Document).selectFirst("a")?.text())
    }

    @Test
    fun parseXmlUnderstandsCdataAndEntities() {
        val ctx = itemOf("<a><![CDATA[1 < 2]]> &amp; &#169;</a>").parseXml().atCss("a").takeContent()
        check("1 < 2 & ©", ctx.value)
    }

    @Test
    fun parseXmlUnderstandsNamespaces() {
        val document = itemOf("<r xmlns:a='urn:x'><a:item>1</a:item><item>2</item></r>").parseXml().value as Document
        check(listOf("1"), document.select("a|item").map { it.text() })
        check(listOf("2"), document.select("item").map { it.text() })
    }

    @Test
    fun parseXmlFragmentAllowsSeveralRoots() {
        val ctx = itemOf("<a>1</a><b>2</b>").parseXmlFragment()
        check(listOf("1"), (ctx.value as Document).select("a").map { it.text() })
        check("2", ctx.atCss("b").takeContent().value)
    }

    @Test
    fun parseXmlFragmentHandlesNull() {
        assertNull(itemOf(null).parseXmlFragment().value)
    }

    @Test
    fun parseXmlFragmentChecksWellFormedness() {
        assertFailsWith<ValueError> { itemOf("<a>1</b>").parseXmlFragment() }
        itemOf("<a>1</b>").parseXmlFragment(strict = false)
    }

    @Test
    fun parseHtmlHandlesNull() {
        val ctx = itemOf(null)
        assertSame(ctx, ctx.parseHtml())
        assertNull(ctx.value)
    }

    @Test
    fun parseHtmlMakesADocument() {
        check(true, itemOf(HTML_PAGE).parseHtml().value is Document)
        check("Dried Beans", itemOf(HTML_PAGE).parseHtml().atCss("h1").takeContent().value)
    }

    @Test
    fun parseHtmlRepairsBrokenHtml() {
        val document = itemOf("<p>one<p>two<table><td>x").parseHtml().value as Document
        check(listOf("one", "two x"), document.select("p").map { it.text() }.let { listOf(it[0], it[1]) })
        check(true, document.selectFirst("html > body") != null)
    }

    @Test
    fun parseHtmlEntersTheDocument() {
        val root = testRoot()
        itemOf(HTML_PAGE, root).parseHtml {
            atCss("article").take("id").put("id")
            atCss(".price").takeContent().put("price")
        }
        check(mapOf("id" to "beans", "price" to "${'$'}999.99"), root.collector)
    }

    @Test
    fun parseHtmlFragmentHandlesNull() {
        val ctx = itemOf(null)
        assertSame(ctx, ctx.parseHtmlFragment())
        assertNull(ctx.value)
    }

    @Test
    fun parseHtmlFragmentMakesAnElement() {
        val ctx = itemOf("<p class='fire'>This is on fire!</p>").parseHtmlFragment()
        check(true, ctx.value is Element)
        check("This is on fire!", ctx.takeContent().value)
        check("fire", ctx.atCss("p").take("class").value)
    }
}
