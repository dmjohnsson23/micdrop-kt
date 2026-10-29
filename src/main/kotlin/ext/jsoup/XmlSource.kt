package micdrop.ext.jsoup

import micdrop.Record
import micdrop.Source
import micdrop.SourceEntry
import micdrop.SourceError
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.InputStream
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * A source which streams the elements of an XML document, one record per element, without loading the whole
 * document into memory. It is the way to read XML too big to parse in one piece (jsoup, and DOM parsers generally,
 * need many times the file's size in memory). It also reads strictly: XML which is not well-formed fails the
 * migration with a [SourceError] naming the line.
 *
 * Each record is a jsoup `Element`, so everything in this package works on it: `take("id")` reads an attribute,
 * `atCss("name").takeContent()` reads a child, and so on. Only one record at a time is ever in memory. Records are
 * indexed by position starting from 0.
 *
 * [element] says which elements are the records. It is a path of element names (ignoring any namespace prefix):
 *
 * * `"product"` is every `product` element, at any depth.
 * * `"catalog/product"` is every `product` directly inside a `catalog`, wherever in the document.
 * * `"/catalog/product"` is the `product`s directly inside the root `catalog` element, exactly.
 *
 * An element inside one that is already a record is part of that record, and not a record of its own. Namespace
 * declarations in force where the element is are carried into its record, so its prefixes still mean something.
 *
 * DTDs are not read and external entities are never fetched. The data can only be read once.
 *
 * @param closeInput whether closing the source also closes the input
 */
class XmlSource private constructor(
    private val create: () -> XMLStreamReader,
    private val input: Closeable?,
    element: String,
    private val closeInput: Boolean,
) : Source {
    constructor(input: InputStream, element: String, closeInput: Boolean = true) :
        this({ secureXmlInputFactory().createXMLStreamReader(input) }, input, element, closeInput)

    constructor(reader: Reader, element: String, closeInput: Boolean = true) :
        this({ secureXmlInputFactory().createXMLStreamReader(reader) }, reader, element, closeInput)

    private val anchored = element.startsWith("/")
    private val path = element.trim('/').split("/").also {
        require(it.none(String::isEmpty)) { "'$element' is not a valid element path" }
    }

    override fun entries(): Sequence<SourceEntry> = sequence {
        registerJsoupRecords()
        val reader = try {
            create()
        } catch (e: XMLStreamException) {
            throw failure(e)
        }
        val open = ArrayList<String>()
        val declared = ArrayList<List<Pair<String, String>>>()
        var index = 0
        try {
            while (true) {
                val event = try {
                    if (!reader.hasNext()) break
                    reader.next()
                } catch (e: XMLStreamException) {
                    throw failure(e)
                }
                when (event) {
                    XMLStreamConstants.START_ELEMENT -> {
                        open.add(reader.localName)
                        declared.add(declarations(reader))
                        if (matches(open)) {
                            val xml = try {
                                serializeElement(reader, declared.flatten())
                            } catch (e: XMLStreamException) {
                                throw failure(e)
                            }
                            open.removeLast()
                            declared.removeLast()
                            val document = Jsoup.parse(xml, "", Parser.xmlParser())
                            yield(SourceEntry(index++, Record.of(document.child(0))))
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        open.removeLast()
                        declared.removeLast()
                    }
                }
            }
        } finally {
            reader.close()
        }
    }

    override fun close() {
        if (closeInput) input?.close()
    }

    private fun matches(open: List<String>) = if (anchored) {
        open == path
    } else {
        open.size >= path.size && open.subList(open.size - path.size, open.size) == path
    }

    private fun failure(e: XMLStreamException): SourceError {
        val where = e.location?.let { " (line ${it.lineNumber}, column ${it.columnNumber})" } ?: ""
        return SourceError("Invalid XML$where: ${e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }?.trim()}", e)
    }

    companion object {
        /** Stream the elements of an XML file. The encoding is taken from the file's XML declaration. */
        fun open(path: Path, element: String) = XmlSource(Files.newInputStream(path), element)

        private fun declarations(reader: XMLStreamReader): List<Pair<String, String>> =
            (0 until reader.namespaceCount).map { (reader.getNamespacePrefix(it) ?: "") to reader.getNamespaceURI(it) }

        /**
         * Write the element the [reader] has just started, and everything in it, back out as XML text, leaving the
         * reader on the element's end. [inScope] are all the namespace declarations in force at the element, which
         * are written on it so that its prefixes still resolve once it has been taken out of its document.
         */
        private fun serializeElement(reader: XMLStreamReader, inScope: List<Pair<String, String>>): String {
            val out = StringBuilder()
            writeStart(out, reader, inScope.associate { it }.entries.map { it.key to it.value })
            var depth = 1
            while (depth > 0) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        writeStart(out, reader, declarations(reader))
                        depth++
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        out.append("</").append(qualifiedName(reader.prefix, reader.localName)).append('>')
                        depth--
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE, XMLStreamConstants.CDATA ->
                        escape(out, reader.text, attribute = false)
                    XMLStreamConstants.COMMENT -> out.append("<!--").append(reader.text).append("-->")
                    XMLStreamConstants.PROCESSING_INSTRUCTION -> {
                        out.append("<?").append(reader.piTarget)
                        reader.piData?.takeIf { it.isNotEmpty() }?.let { out.append(' ').append(it) }
                        out.append("?>")
                    }
                }
            }
            return out.toString()
        }

        private fun writeStart(out: StringBuilder, reader: XMLStreamReader, namespaces: List<Pair<String, String>>) {
            out.append('<').append(qualifiedName(reader.prefix, reader.localName))
            for ((prefix, uri) in namespaces) {
                out.append(if (prefix.isEmpty()) " xmlns=\"" else " xmlns:$prefix=\"")
                escape(out, uri, attribute = true)
                out.append('"')
            }
            for (i in 0 until reader.attributeCount) {
                out.append(' ').append(qualifiedName(reader.getAttributePrefix(i), reader.getAttributeLocalName(i)))
                out.append("=\"")
                escape(out, reader.getAttributeValue(i), attribute = true)
                out.append('"')
            }
            out.append('>')
        }

        private fun qualifiedName(prefix: String?, localName: String) =
            if (prefix.isNullOrEmpty()) localName else "$prefix:$localName"

        private fun escape(out: StringBuilder, text: String, attribute: Boolean) {
            for (c in text) {
                when {
                    c == '&' -> out.append("&amp;")
                    c == '<' -> out.append("&lt;")
                    c == '>' -> out.append("&gt;")
                    attribute && c == '"' -> out.append("&quot;")
                    attribute && c == '\n' -> out.append("&#10;")
                    attribute && c == '\r' -> out.append("&#13;")
                    attribute && c == '\t' -> out.append("&#9;")
                    else -> out.append(c)
                }
            }
        }
    }
}
