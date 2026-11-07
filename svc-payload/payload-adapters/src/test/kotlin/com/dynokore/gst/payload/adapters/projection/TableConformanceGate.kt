package com.dynokore.gst.payload.adapters.projection

import com.dynokore.platform.integrationcanon.xml.HardenedXml
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import tools.jackson.databind.JsonNode
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.namespace.NamespaceContext
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/** One row of the MDE pack that applies to a document: the Business Term and the path. */
data class GateRow(
    val businessTermId: String,
    val syntaxPath: String,
)

/**
 * The table conformance gate (INV-Q-7106 A item 4), fails closed. The rows that apply to a document of type code T and
 * specification version S are those whose documentTypeCodes contain T by equality and that have a specifications entry
 * with specificationVersion equal to S. It returns the failures by name; an empty list is a pass.
 */
object TableConformanceGate {
    private const val CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"
    private const val CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"

    /** A namespace context for exactly the prefixes cbc and cac. */
    private val namespaces =
        object : NamespaceContext {
            override fun getNamespaceURI(prefix: String?): String =
                when (prefix) {
                    "cbc" -> CBC
                    "cac" -> CAC
                    else -> XMLConstants.NULL_NS_URI
                }

            override fun getPrefix(namespaceURI: String?): String? = null

            override fun getPrefixes(namespaceURI: String?): Iterator<String> = emptyList<String>().iterator()
        }

    private fun text(node: JsonNode): String? = if (node.isString) node.stringValue() else null

    /**
     * GAP (reported): the root element of a UBL document is in the Invoice-2 or CreditNote-2 namespace, and an
     * unprefixed step of an XPath 1.0 path selects no-namespace elements only, so `/Invoice/...` with a context of
     * exactly cbc and cac selects nothing. Provisional until the owner rules: the root step is matched by local-name().
     */
    fun select(
        document: Document,
        path: String,
    ): NodeList {
        val xpath =
            XPathFactory
                .newInstance()
                .also {
                    it.setFeature(
                        XMLConstants.FEATURE_SECURE_PROCESSING,
                        true,
                    )
                }.newXPath()
        xpath.namespaceContext = namespaces
        val rooted =
            Regex(
                "^/([A-Za-z]+)(/|$)",
            ).replace(path) { "/*[local-name()='${it.groupValues[1]}']${it.groupValues[2]}" }
        return xpath.evaluate(rooted, document, XPathConstants.NODESET) as NodeList
    }

    fun parse(xml: String): Document =
        HardenedXml.documentBuilderFactory().also { it.isNamespaceAware = true }.newDocumentBuilder().parse(
            ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)),
        )

    fun applyingRows(
        fixture: JsonNode,
        documentTypeCode: String,
        specificationVersion: String,
    ): List<GateRow> =
        fixture.path("rows").flatMap { row ->
            val types = row.path("documentTypeCodes").mapNotNull { text(it) }
            if (documentTypeCode !in types) return@flatMap emptyList()
            row
                .path(
                    "specifications",
                ).filter { it.path("specificationVersion").asString() == specificationVersion }
                .map {
                    GateRow(row.path("businessTermId").asString(), it.path("syntaxPath").asString())
                }
        }

    fun check(
        xml: String,
        rows: List<GateRow>,
    ): List<String> {
        val failures = mutableListOf<String>()
        if (rows.isEmpty()) failures += "(d) no row applies to the document"
        val document = parse(xml)
        val root = document.documentElement.localName
        val selectedBy = linkedMapOf<Node, MutableList<GateRow>>()
        for (row in rows) {
            val first = row.syntaxPath.removePrefix("/").substringBefore('/')
            if (first !=
                root
            ) {
                failures +=
                    "(c) row ${row.businessTermId} path ${row.syntaxPath} starts with $first, " +
                    "the document root is $root"
            }
            val nodes = select(document, row.syntaxPath)
            if (nodes.length == 0) failures += "(a) row ${row.businessTermId} path ${row.syntaxPath} selects no node"
            for (i in 0 until nodes.length) selectedBy.getOrPut(nodes.item(i)) { mutableListOf() }.add(row)
        }
        val leaves = leaves(document.documentElement)
        for (leaf in leaves) {
            val by = selectedBy[leaf].orEmpty()
            if (by.size != 1) {
                failures += "(b) leaf ${describe(leaf)} is selected by ${by.size} rows ${by.map { it.businessTermId }}"
            }
        }
        for (node in selectedBy.keys) {
            if (node !in leaves) failures += "(b) a row selects ${describe(node)}, which is no leaf the builder wrote"
        }
        return failures
    }

    private fun leaves(element: org.w3c.dom.Element): Set<Node> {
        val out = linkedSetOf<Node>()
        val attributes = element.attributes
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i)
            if (attribute.namespaceURI != XMLConstants.XMLNS_ATTRIBUTE_NS_URI) out += attribute
        }
        var childElements = 0
        var child = element.firstChild
        while (child != null) {
            if (child is org.w3c.dom.Element) {
                childElements++
                out += leaves(child)
            }
            child = child.nextSibling
        }
        if (childElements == 0) out += element
        return out
    }

    private fun describe(node: Node): String {
        val names = mutableListOf<String>()
        var n: Node? = node
        while (n != null && n !is Document) {
            names += if (n is org.w3c.dom.Attr) "@${n.name}" else n.nodeName
            n = if (n is org.w3c.dom.Attr) n.ownerElement else n.parentNode
        }
        return "/" + names.reversed().joinToString("/") + " = '" + node.textContent + "'"
    }
}
