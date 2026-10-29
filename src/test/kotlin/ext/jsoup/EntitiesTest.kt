package micdrop.ext.jsoup

import micdrop.check
import micdrop.itemOf
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class EntitiesTest {
    // region HTML

    @Test
    fun decodeHtmlHandlesNull() {
        val ctx = itemOf(null)
        assertSame(ctx, ctx.decodeHtml())
        assertNull(ctx.value)
    }

    @Test
    fun decodesHtml() {
        val ctx = itemOf("&lt;&apos;&quot;")
        assertSame(ctx, ctx.decodeHtml())
        check("<'\"", ctx.value)
    }

    @Test
    fun decodesNamedAndNumericHtmlEntities() {
        check("© é & <b>", itemOf("&copy; &eacute; &amp; &lt;b&gt;").decodeHtml().value)
        check("© ©", itemOf("&#169; &#xA9;").decodeHtml().value)
        check("plain text", itemOf("plain text").decodeHtml().value)
    }

    @Test
    fun encodeHtmlHandlesNull() {
        assertNull(itemOf(null).encodeHtml().value)
    }

    @Test
    fun encodesHtml() {
        val ctx = itemOf("<'\"")
        assertSame(ctx, ctx.encodeHtml())
        check("&lt;'\"", ctx.value)
    }

    @Test
    fun encodesAmpersandsAndAngleBrackets() {
        check("a &amp; b &lt;c&gt;", itemOf("a & b <c>").encodeHtml().value)
    }

    @Test
    fun leavesOrdinaryCharactersAlone() {
        check("café ©", itemOf("café ©").encodeHtml().value)
    }

    @Test
    fun convertsNewlinesToBreaks() {
        val ctx = itemOf("Here are some\nlines")
        assertSame(ctx, ctx.encodeHtml(nl2br = true))
        check("Here are some<br/>lines", ctx.value)
        check("Here are some\nlines", itemOf("Here are some\nlines").encodeHtml().value)
    }

    @Test
    fun htmlRoundTrips() {
        val text = "if a < b && b > c then \"ok\"\nnext"
        check(text, itemOf(text).encodeHtml().decodeHtml().value)
    }

    // endregion

    // region XML

    @Test
    fun decodeXmlHandlesNull() {
        assertNull(itemOf(null).decodeXml().value)
    }

    @Test
    fun decodesXml() {
        check("<'\"&>", itemOf("&lt;&apos;&quot;&amp;&gt;").decodeXml().value)
        check("© ©", itemOf("&#169; &#xA9;").decodeXml().value)
        check("plain text", itemOf("plain text").decodeXml().value)
    }

    @Test
    fun encodeXmlHandlesNull() {
        assertNull(itemOf(null).encodeXml().value)
    }

    @Test
    fun encodesXml() {
        check("&lt;'\"&amp;", itemOf("<'\"&").encodeXml().value)
        check("café", itemOf("café").encodeXml().value)
    }

    @Test
    fun xmlRoundTrips() {
        val text = "a < b & c > d \"quoted\""
        check(text, itemOf(text).encodeXml().decodeXml().value)
    }

    @Test
    fun xmlKeepsLineBreaksAndSpacing() {
        check("line one\nline  two\r\n\tindented", itemOf("line one\nline  two\r\n\tindented").encodeXml().value)
    }

    @Test
    fun htmlKeepsSpacing() {
        check("a  b\t c\n d", itemOf("a  b\t c\n d").encodeHtml().value)
    }

    @Test
    fun encodedXmlIsValidXml() {
        val encoded = itemOf("1 < 2 & 3 > 2").encodeXml().value as String
        check("1 < 2 & 3 > 2", itemOf("<a>$encoded</a>").parseXml().atCss("a").takeContent().value)
    }

    // endregion
}
