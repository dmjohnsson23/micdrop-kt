package org.example.ext.jsoup

import org.example.ItemContext
import org.example.Pipeline
import org.example.PipelineError
import org.example.Record
import org.example.RecordContext
import org.example.SubRecordContext
import org.example.ValueError
import org.example.Source
import org.example.asSource
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.Elements
import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamException

// HTML and XML support, through jsoup. See `docs/sources-and-sinks.md` for an overview.

/** An HTML or XML element as a record: its attributes are the items, e.g. `take("id")` */
class ElementRecord(override val raw: Element) : Record {
    override fun contains(key: Any) = key is String && raw.hasAttr(key)

    override fun get(key: Any): Any? = if (key is String && raw.hasAttr(key)) raw.attr(key) else null
}

private val registration: Unit by lazy {
    Record.registerAdapter { value -> (value as? Element)?.let { ElementRecord(it) } }
}

/**
 * Make jsoup elements and documents usable as records. Everything in this extension that produces them does this
 * for you, so you only need to call it yourself if you create jsoup nodes some other way and want to `take` from
 * them. Safe to call as often as you like.
 */
fun registerJsoupRecords() = registration

// region Parsing

/**
 * Parse XML text into a document.
 *
 * jsoup is lenient and quietly accepts XML that is not well-formed, which in a migration could mean quietly
 * migrating part of a damaged file. So with [strict] (the default) the text is first checked, and a [ValueError] is
 * thrown if it is not well-formed. Turn it off to parse leniently anyway.
 */
fun parseXmlDocument(xml: String, strict: Boolean = true): Document {
    registerJsoupRecords()
    val text = xml.removePrefix(BOM)
    if (strict) requireWellFormed(text)
    return Jsoup.parse(text, "", Parser.xmlParser())
}

/** Parse HTML text into a document, the way a browser would (so even broken HTML gets an `html` and `body`) */
fun parseHtmlDocument(html: String): Document {
    registerJsoupRecords()
    return Jsoup.parse(html)
}

/** The elements of a query result, or other elements, as a source of records */
fun Elements.asSource(): Source {
    registerJsoupRecords()
    return (this as Iterable<*>).asSource()
}

private const val BOM = 0xFEFF.toChar().toString()

private fun requireWellFormed(xml: String, wrapIn: String? = null) {
    val text = if (wrapIn == null) xml else "<$wrapIn>$xml</$wrapIn>"
    val reader = secureXmlInputFactory().createXMLStreamReader(StringReader(text))
    try {
        while (reader.hasNext()) reader.next()
    } catch (e: XMLStreamException) {
        throw ValueError("Invalid XML: ${e.message?.trim()}", e)
    } finally {
        reader.close()
    }
}

internal fun secureXmlInputFactory(): XMLInputFactory = XMLInputFactory.newFactory().apply {
    setProperty(XMLInputFactory.SUPPORT_DTD, false)
    setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    setProperty(XMLInputFactory.IS_COALESCING, true)
}

private fun ItemContext.parseAndEnter(
    block: (SubRecordContext.() -> Unit)?,
    parse: (String) -> Any,
): ItemContext {
    registerJsoupRecords()
    transform { parse(it.toString()) }
    if (block != null && value != null) enter(block)
    return this
}

/**
 * Parse XML text, making the document the value. If a [block] is given, it runs with the document as a record, so
 * that you can use `css`, `xpath`, `takeContent` and the rest. See [parseXmlDocument] about [strict].
 */
fun ItemContext.parseXml(strict: Boolean = true, block: (SubRecordContext.() -> Unit)? = null) =
    parseAndEnter(block) { parseXmlDocument(it, strict) }

/**
 * Parse a piece of XML which may have several elements at its top level, rather than the single one of a whole
 * document. With [strict], it is checked as the contents of a single element.
 */
fun ItemContext.parseXmlFragment(strict: Boolean = true, block: (SubRecordContext.() -> Unit)? = null) =
    parseAndEnter(block) {
        val text = it.removePrefix(BOM)
        if (strict) requireWellFormed(text, wrapIn = "fragment")
        Jsoup.parse(text, "", Parser.xmlParser())
    }

/** Parse HTML text, making the document the value. The optional [block] runs with it as a record. */
fun ItemContext.parseHtml(block: (SubRecordContext.() -> Unit)? = null) = parseAndEnter(block) { parseHtmlDocument(it) }

/**
 * Parse a piece of HTML, which doesn't need to be a whole page. The value becomes the `body` element which holds
 * it, and the optional [block] runs with that as a record.
 */
fun ItemContext.parseHtmlFragment(block: (SubRecordContext.() -> Unit)? = null) =
    parseAndEnter(block) { Jsoup.parseBodyFragment(it).body() }

// endregion

// region Entities

/** Replace HTML entities such as `&lt;` and `&#169;` with the characters they stand for */
fun ItemContext.decodeHtml(): ItemContext = transform { Parser.unescapeEntities(it.toString(), false) }

/**
 * Replace the entities of XML (`&lt;`, `&gt;`, `&amp;`, `&quot;`, `&apos;`, and numeric ones like `&#169;`) with the
 * characters they stand for. Other names are left as they are, since XML doesn't define them.
 */
fun ItemContext.decodeXml(): ItemContext = transform {
    Jsoup.parse("<r>$it</r>", "", Parser.xmlParser()).selectFirst("r")?.wholeText() ?: it.toString()
}

/**
 * Escape text for use in HTML, replacing `<`, `>` and `&` with entities. With [nl2br], line breaks also become `<br/>`.
 */
fun ItemContext.encodeHtml(nl2br: Boolean = false): ItemContext = transform {
    escapeText(it.toString(), xml = false).let { html -> if (nl2br) html.replace("\n", "<br/>") else html }
}

/** Escape text for use in XML, replacing `<`, `>` and `&` with entities */
fun ItemContext.encodeXml(): ItemContext = transform { escapeText(it.toString(), xml = true) }

/**
 * Escape [text] the way jsoup writes out text nodes. Pretty-printing has to be off, or jsoup tidies the whitespace
 * (turning line breaks into spaces), which would change the text.
 */
private fun escapeText(text: String, xml: Boolean): String {
    val document = Document("")
    val settings = document.outputSettings().prettyPrint(false)
    if (xml) settings.syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml)
    val node = TextNode(text)
    document.appendChild(node)
    return node.outerHtml()
}

// endregion

// region Taking from elements

private fun RecordContext.element(what: String): Element? = when (val raw = record?.raw) {
    null -> null
    is Element -> raw
    else -> throw PipelineError("$what needs an HTML or XML element, but this record is a ${raw::class.simpleName}")
}

private inline fun <T> selecting(kind: String, selector: String, query: () -> T): T = try {
    query()
} catch (e: IllegalStateException) {
    throw PipelineError("Invalid $kind '$selector': ${e.message}", e)
} catch (e: IllegalArgumentException) {
    throw PipelineError("Invalid $kind '$selector': ${e.message}", e)
}

/**
 * Take the text of the element and everything inside it, exactly as written (whitespace and all, so apply `strip`
 * if you don't want it).
 */
fun RecordContext.takeContent(block: Pipeline = {}): ItemContext = static(element("takeContent")?.wholeText(), block)

/** Take the name of the element, e.g. `price` */
fun RecordContext.takeNodeName(block: Pipeline = {}): ItemContext = static(element("takeNodeName")?.nodeName(), block)

/**
 * Find the elements inside this one which match a CSS selector, as a list item. Use `eachSubrecord` to go over
 * them, or `extract(0)` for a particular one. (Note that jsoup also matches the element itself if it fits the
 * selector.)
 */
fun RecordContext.css(selector: String, block: Pipeline = {}): ItemContext =
    static(element("css")?.let { e -> selecting("CSS selector", selector) { e.select(selector) } }, block)

/** Find the first element inside this one which matches a CSS selector, as an item (null if there is none) */
fun RecordContext.atCss(selector: String, block: Pipeline = {}): ItemContext =
    static(element("atCss")?.let { e -> selecting("CSS selector", selector) { e.selectFirst(selector) } }, block)

/**
 * Find the elements inside this one which match an XPath expression, as a list item. Only elements can be found
 * (not attributes or text). XPath is slower than CSS in jsoup, which has to convert the document to run it.
 */
fun RecordContext.xpath(expression: String, block: Pipeline = {}): ItemContext =
    static(element("xpath")?.let { e -> selecting("XPath", expression) { e.selectXpath(expression) } }, block)

/** Find the first element inside this one which matches an XPath expression, as an item (null if there is none) */
fun RecordContext.atXpath(expression: String, block: Pipeline = {}): ItemContext =
    static(
        element("atXpath")?.let { e -> selecting("XPath", expression) { e.selectXpath(expression).firstOrNull() } },
        block,
    )

/** Alias for `scope().enter().takeContent()`: take the text of the element this item holds */
fun ItemContext.takeContent(block: Pipeline = {}) = scope().enter().takeContent(block)

/** Alias for `scope().enter().takeNodeName()` */
fun ItemContext.takeNodeName(block: Pipeline = {}) = scope().enter().takeNodeName(block)

/** Alias for `scope().enter().css(...)` */
fun ItemContext.css(selector: String, block: Pipeline = {}) = scope().enter().css(selector, block)

/** Alias for `scope().enter().atCss(...)` */
fun ItemContext.atCss(selector: String, block: Pipeline = {}) = scope().enter().atCss(selector, block)

/** Alias for `scope().enter().xpath(...)` */
fun ItemContext.xpath(expression: String, block: Pipeline = {}) = scope().enter().xpath(expression, block)

/** Alias for `scope().enter().atXpath(...)` */
fun ItemContext.atXpath(expression: String, block: Pipeline = {}) = scope().enter().atXpath(expression, block)

// endregion
