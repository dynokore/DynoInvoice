package com.dynokore.gst.consoleBff.domain.payload

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** The answer of [viewPayload] (INV-REQ-1039). Only [Shown] carries payload content. */
sealed interface PayloadView {
    data class Shown(
        val text: String,
    ) : PayloadView

    /** The caller has no content permission; nothing about the bytes is known to the answer. */
    object Withheld : PayloadView

    /** [reason] is one of four fixed strings and holds no part of the payload. */
    data class Unreadable(
        val reason: String,
    ) : PayloadView
}

const val XML_MEDIA_TYPE: String = "application/xml"

private const val REASON_MEDIA_TYPE = "unsupported media type"
private const val REASON_TOO_LARGE = "payload too large"
private const val REASON_MALFORMED = "not well-formed XML"
private const val REASON_NOT_REPRODUCIBLE = "not reproducible"

/**
 * Pretty-prints a transmitted PINT-SG payload for a caller whose content permission was already decided.
 *
 * [contentAllowed] is the per-request decision on `doc.view.content`; false gives [PayloadView.Withheld] before
 * anything else (NIST SP 800-53 AC-6) and the bytes are not read. The XML is parsed with document type declarations
 * refused (OWASP XXE Prevention, CWE-611) and the output is checked for equivalence with the input; any failure is
 * a fixed reason with no payload content (OWASP ASVS V7.1.1).
 */
fun viewPayload(
    contentAllowed: Boolean,
    mediaType: String,
    bytes: ByteArray,
    indent: String,
    maxBytes: Int,
): PayloadView {
    require(indent.isNotEmpty() && indent.all { it == ' ' || it == '\t' }) { "indent must be spaces or tabs" }
    require(maxBytes >= 1) { "maxBytes must be at least 1" }
    return when {
        !contentAllowed -> PayloadView.Withheld
        mediaType != XML_MEDIA_TYPE -> PayloadView.Unreadable(REASON_MEDIA_TYPE)
        bytes.size > maxBytes -> PayloadView.Unreadable(REASON_TOO_LARGE)
        else -> render(bytes, indent)
    }
}

private fun render(
    bytes: ByteArray,
    indent: String,
): PayloadView {
    val input = parse(bytes) ?: return PayloadView.Unreadable(REASON_MALFORMED)
    return try {
        val lines = ArrayList<String>()
        children(input).forEach { writeNode(it, 0, indent, lines) }
        val text = lines.joinToString("\n")
        val output = parse(text.toByteArray(Charsets.UTF_8))
        dropBlankText(input)
        output?.let { dropBlankText(it) }
        if (output != null && input.isEqualNode(output)) {
            PayloadView.Shown(text)
        } else {
            PayloadView.Unreadable(REASON_NOT_REPRODUCIBLE)
        }
    } catch (_: Exception) {
        PayloadView.Unreadable(REASON_NOT_REPRODUCIBLE)
    } catch (_: StackOverflowError) {
        PayloadView.Unreadable(REASON_NOT_REPRODUCIBLE)
    }
}

private object SilentErrors : ErrorHandler {
    override fun warning(e: SAXParseException) = Unit

    override fun error(e: SAXParseException) = throw e

    override fun fatalError(e: SAXParseException) = throw e
}

private fun parse(bytes: ByteArray): Document? =
    try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(SilentErrors)
        builder.setEntityResolver { _, _ -> throw SAXParseException("external resource refused", null) }
        builder.parse(InputSource(ByteArrayInputStream(bytes)))
    } catch (_: Exception) {
        null
    }

private fun isBlank(node: Node): Boolean =
    node.nodeType == Node.TEXT_NODE && node.nodeValue.all { " \t\n\r".indexOf(it) >= 0 }

private fun children(node: Node): List<Node> {
    val result = ArrayList<Node>()
    var child = node.firstChild
    while (child != null) {
        result.add(child)
        child = child.nextSibling
    }
    return result
}

private fun writeNode(
    node: Node,
    depth: Int,
    indent: String,
    lines: MutableList<String>,
) {
    val pad = indent.repeat(depth)
    when (node.nodeType) {
        Node.ELEMENT_NODE -> writeElement(node as Element, depth, indent, lines)
        Node.COMMENT_NODE -> lines.add(pad + inline(node))
        Node.PROCESSING_INSTRUCTION_NODE -> lines.add(pad + inline(node))
        Node.DOCUMENT_TYPE_NODE -> error("document type declaration")
        else -> if (!isBlank(node)) lines.add(pad + inline(node))
    }
}

private fun writeElement(
    element: Element,
    depth: Int,
    indent: String,
    lines: MutableList<String>,
) {
    val pad = indent.repeat(depth)
    val kids = children(element)
    val hasMarkup =
        kids.any {
            it.nodeType == Node.ELEMENT_NODE || it.nodeType == Node.COMMENT_NODE ||
                it.nodeType == Node.PROCESSING_INSTRUCTION_NODE
        }
    val hasContentText =
        kids.any {
            (it.nodeType == Node.TEXT_NODE && !isBlank(it)) ||
                it.nodeType == Node.CDATA_SECTION_NODE
        }
    if (hasMarkup && !hasContentText) {
        lines.add(pad + startTag(element, false))
        for (kid in kids) {
            if (!isBlank(kid)) writeNode(kid, depth + 1, indent, lines)
        }
        lines.add(pad + "</" + element.nodeName + ">")
    } else {
        lines.add(pad + inline(element))
    }
}

private fun startTag(
    element: Element,
    selfClose: Boolean,
): String {
    val sb = StringBuilder("<").append(element.nodeName)
    val attrs = element.attributes
    for (i in 0 until attrs.length) {
        val attr = attrs.item(i)
        sb
            .append(' ')
            .append(attr.nodeName)
            .append("=\"")
            .append(escape(attr.nodeValue, true))
            .append('"')
    }
    return sb.append(if (selfClose) "/>" else ">").toString()
}

private fun inline(node: Node): String =
    when (node.nodeType) {
        Node.ELEMENT_NODE -> {
            val element = node as Element
            val kids = children(element)
            if (kids.isEmpty()) {
                startTag(element, true)
            } else {
                startTag(element, false) + kids.joinToString("") { inline(it) } + "</" + element.nodeName + ">"
            }
        }

        Node.TEXT_NODE -> {
            escape(node.nodeValue, false)
        }

        Node.CDATA_SECTION_NODE -> {
            "<![CDATA[" + node.nodeValue + "]]>"
        }

        Node.COMMENT_NODE -> {
            "<!--" + node.nodeValue + "-->"
        }

        Node.PROCESSING_INSTRUCTION_NODE -> {
            val data = node.nodeValue
            "<?" + node.nodeName + (if (data.isNullOrEmpty()) "" else " $data") + "?>"
        }

        else -> {
            error("unsupported node type")
        }
    }

private val TEXT_ESCAPES = mapOf('&' to "&amp;", '<' to "&lt;", '>' to "&gt;", '\r' to "&#13;")
private val ATTRIBUTE_ESCAPES = TEXT_ESCAPES + mapOf('"' to "&quot;", '\t' to "&#9;", '\n' to "&#10;")

private fun escape(
    value: String,
    attribute: Boolean,
): String {
    val table = if (attribute) ATTRIBUTE_ESCAPES else TEXT_ESCAPES
    val sb = StringBuilder()
    for (c in value) {
        sb.append(table[c] ?: c)
    }
    return sb.toString()
}

private fun dropBlankText(node: Node) {
    for (kid in children(node)) {
        if (isBlank(kid)) node.removeChild(kid) else dropBlankText(kid)
    }
    node.normalize()
}
