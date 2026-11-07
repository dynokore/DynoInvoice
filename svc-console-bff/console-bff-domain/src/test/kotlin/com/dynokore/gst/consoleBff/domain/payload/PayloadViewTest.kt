package com.dynokore.gst.consoleBff.domain.payload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class PayloadViewTest {
    private val ns =
        "xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\" " +
            "xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\""
    private val oneLine = "<Invoice $ns><cbc:ID>INV-1</cbc:ID><cbc:Note> a  b </cbc:Note></Invoice>"
    private val max = 1000

    private fun view(
        xml: String,
        allowed: Boolean = true,
        mediaType: String = XML_MEDIA_TYPE,
        indent: String = "  ",
        maxBytes: Int = max,
    ) = viewPayload(allowed, mediaType, xml.toByteArray(Charsets.UTF_8), indent, maxBytes)

    private fun shown(
        xml: String,
        indent: String = "  ",
    ): String = (view(xml, indent = indent) as PayloadView.Shown).text

    private fun parse(text: String): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        val doc = f.newDocumentBuilder().parse(InputSource(StringReader(text)))
        drop(doc)
        return doc
    }

    private fun drop(node: Node) {
        var c = node.firstChild
        while (c != null) {
            val next = c.nextSibling
            if (c.nodeType == Node.TEXT_NODE && c.nodeValue.isBlank()) node.removeChild(c) else drop(c)
            c = next
        }
        node.normalize()
    }

    @Test
    fun `INV-AC-15924 withheld is the same object for every media type and bytes`() {
        val a = view(oneLine, allowed = false)
        val b = view("<<<", allowed = false)
        val c = viewPayload(false, XML_MEDIA_TYPE, ByteArray(0), "  ", max)
        val d = view(oneLine, allowed = false, mediaType = "application/json")
        a shouldBeSameInstanceAs PayloadView.Withheld
        b shouldBeSameInstanceAs a
        c shouldBeSameInstanceAs a
        d shouldBeSameInstanceAs a
    }

    @Test
    fun `INV-AC-15924 withheld even when oversize`() {
        viewPayload(false, XML_MEDIA_TYPE, ByteArray(5000), "  ", 10) shouldBe PayloadView.Withheld
    }

    @Test
    fun `INV-AC-15924 one-line document is written one element per line`() {
        val text = shown(oneLine)
        text.lines() shouldBe
            listOf(
                "<Invoice $ns>",
                "  <cbc:ID>INV-1</cbc:ID>",
                "  <cbc:Note> a  b </cbc:Note>",
                "</Invoice>",
            )
        text.contains("<?xml") shouldBe false
        parse(text).isEqualNode(parse(oneLine)) shouldBe true
    }

    @Test
    fun `INV-AC-15924 comment and attribute survive`() {
        val text = shown("<Invoice $ns><!-- note --><cbc:ID schemeID=\"x\">1</cbc:ID></Invoice>")
        text.lines()[1] shouldBe "  <!-- note -->"
        text.lines()[2] shouldBe "  <cbc:ID schemeID=\"x\">1</cbc:ID>"
    }

    @Test
    fun `INV-AC-15924 mixed content stays on one line`() {
        shown("<p>one <b>two</b> three</p>") shouldBe "<p>one <b>two</b> three</p>"
        shown("<r><p>one <b>two</b> three</p></r>") shouldBe "<r>\n  <p>one <b>two</b> three</p>\n</r>"
    }

    @Test
    fun `INV-AC-15924 tab-indented input gives the same text as its one-line form`() {
        val tabbed = "<Invoice $ns>\n\t<cbc:ID>INV-1</cbc:ID>\n\t<cbc:Note> a  b </cbc:Note>\n</Invoice>\n"
        shown(tabbed) shouldBe shown(oneLine)
    }

    @Test
    fun `INV-AC-15924 empty element, tab indent, escapes and cdata are reproduced`() {
        shown("<r><a/><b>&lt;&amp;&gt;</b></r>", "\t") shouldBe "<r>\n\t<a/>\n\t<b>&lt;&amp;&gt;</b>\n</r>"
        val x = "<r><a v=\"1 &lt; 2 &quot;q&quot;&#10;\"><![CDATA[<z>]]></a><?pi data?></r>"
        parse(shown(x)).isEqualNode(parse(x)) shouldBe true
    }

    @Test
    fun `INV-AC-15924 doctype, external entity and unclosed tag are not well-formed`() {
        val doctype = "<!DOCTYPE r><r/>"
        val entity = "<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///nonexistent/inv-15924\">]><r>&x;</r>"
        val bareEntity = "<r>&x;</r>"
        val unclosed = "<r><a></r>"
        for (xml in listOf(doctype, entity, bareEntity, unclosed, "<<<", "")) {
            view(xml) shouldBe PayloadView.Unreadable("not well-formed XML")
        }
    }

    @Test
    fun `INV-AC-15924 only the exact media type is accepted`() {
        for (mt in listOf(
            "application/json",
            "APPLICATION/XML",
            "application/xml ",
            "application/xml; charset=UTF-8",
        )) {
            view(oneLine, mediaType = mt) shouldBe PayloadView.Unreadable("unsupported media type")
        }
    }

    @Test
    fun `INV-AC-15924 size limit is inclusive`() {
        val bytes = oneLine.toByteArray()
        viewPayload(true, XML_MEDIA_TYPE, bytes, "  ", bytes.size) shouldNotBe
            PayloadView.Unreadable("payload too large")
        (viewPayload(true, XML_MEDIA_TYPE, bytes, "  ", bytes.size) is PayloadView.Shown) shouldBe true
        viewPayload(true, XML_MEDIA_TYPE, bytes, "  ", bytes.size - 1) shouldBe
            PayloadView.Unreadable("payload too large")
    }

    @Test
    fun `INV-AC-15924 no reason holds payload content`() {
        val results =
            listOf(
                view("<Invoice><ID>INV-1</ID><<<"),
                view("<!DOCTYPE Invoice><Invoice>INV-1</Invoice>"),
                view("<<<"),
                view(oneLine, mediaType = "application/json"),
                view(oneLine, maxBytes = 5),
            )
        for (r in results) {
            val reason = (r as PayloadView.Unreadable).reason
            listOf("INV-1", "Invoice", "<<<").none { reason.contains(it) } shouldBe true
        }
    }

    @Test
    fun `INV-AC-15924 bad indent or max bytes is refused`() {
        for (i in listOf("", "x", " x")) shouldThrow<IllegalArgumentException> { view(oneLine, indent = i) }
        shouldThrow<IllegalArgumentException> { view(oneLine, maxBytes = 0) }
    }

    @Test
    fun `INV-AC-15924 same input twice gives equal results`() {
        view(oneLine) shouldBe view(oneLine)
    }
}
