package com.dynokore.gst.payload.adapters.projection

import com.dynokore.platform.integrationcanon.xml.HardenedXml
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import tools.jackson.databind.JsonNode
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.namespace.NamespaceContext
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/** One row of the MDE pack that applies to a document: the Business Term, its document type codes and the path. */
data class GateRow(
    val businessTermId: String,
    val syntaxPath: String,
    val documentTypeCodes: List<String>,
)

/**
 * The table conformance gate (INV-Q-7106 A item 4, INV-Q-18099 A), fails closed. The rows that apply to a document of
 * type code T and specification version S are those whose documentTypeCodes contain T by equality and that have a
 * specifications entry with specificationVersion equal to S. It returns the failures by name; an empty list is a pass.
 */
object TableConformanceGate {
    private const val CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"
    private const val CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
    private const val INVOICE_NS = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
    private const val CREDIT_NOTE_NS = "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2"

    private fun rootNamespace(typeCode: String): String? =
        when (typeCode) {
            "380" -> INVOICE_NS
            "381" -> CREDIT_NOTE_NS
            else -> null
        }

    private fun rootName(typeCode: String): String? =
        when (typeCode) {
            "380" -> "Invoice"
            "381" -> "CreditNote"
            else -> null
        }

    /** Exactly three prefixes: cbc, cac and root (bound from T, never read from the document); others are null. */
    private fun namespaces(typeCode: String) =
        object : NamespaceContext {
            override fun getNamespaceURI(prefix: String?): String =
                when (prefix) {
                    "cbc" -> CBC
                    "cac" -> CAC
                    "root" -> rootNamespace(typeCode) ?: XMLConstants.NULL_NS_URI
                    else -> XMLConstants.NULL_NS_URI
                }

            override fun getPrefix(namespaceURI: String?): String? = null

            override fun getPrefixes(namespaceURI: String?): Iterator<String> = emptyList<String>().iterator()
        }

    private fun text(node: JsonNode): String? = if (node.isString) node.stringValue() else null

    private fun firstStep(path: String) = path.removePrefix("/").substringBefore('/')

    /** The first step a row's path must have: the Invoice form for a row that lists 380 (alone or with 381). */
    private fun expectedFirstStep(codes: List<String>): String? =
        when {
            codes == listOf("380") || codes.toSet() == setOf("380", "381") -> "Invoice"
            codes == listOf("381") -> "CreditNote"
            else -> null
        }

    /**
     * Evaluates a path for the type code T: the first step `Invoice` is replaced by `CreditNote` for 381, and the first
     * step is rewritten to `root:<step>`; nothing else is rewritten.
     */
    fun select(
        document: Document,
        path: String,
        typeCode: String,
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
        xpath.namespaceContext = namespaces(typeCode)
        val step = firstStep(path)
        val rest = path.removePrefix("/").removePrefix(step)
        val mapped = if (typeCode == "381" && step == "Invoice") "CreditNote" else step
        val rewritten = if (mapped == "Invoice" || mapped == "CreditNote") "/root:$mapped$rest" else path
        return xpath.evaluate(rewritten, document, XPathConstants.NODESET) as NodeList
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
            row.path("specifications").filter { text(it.path("specificationVersion")) == specificationVersion }.map {
                GateRow(text(row.path("businessTermId")).orEmpty(), text(it.path("syntaxPath")).orEmpty(), types)
            }
        }

    fun check(
        xml: String,
        typeCode: String,
        rows: List<GateRow>,
    ): List<String> {
        val failures = mutableListOf<String>()
        if (rows.isEmpty()) failures += "(d) no row applies to the document"
        val document = parse(xml)
        val root = document.documentElement
        if (root.namespaceURI != rootNamespace(typeCode) || root.localName != rootName(typeCode)) {
            failures += "(e) the root is {${root.namespaceURI}}${root.localName}, type $typeCode needs " +
                "{${rootNamespace(typeCode)}}${rootName(typeCode)}"
        }
        val selectedBy = linkedMapOf<Node, MutableList<GateRow>>()
        for (row in rows) {
            val expected = expectedFirstStep(row.documentTypeCodes)
            if (firstStep(row.syntaxPath) != expected) {
                failures +=
                    "(c) row ${row.businessTermId} path ${row.syntaxPath} has first step ${firstStep(
                        row.syntaxPath,
                    )}, " +
                    "expected $expected for ${row.documentTypeCodes}"
                continue
            }
            val nodes = select(document, row.syntaxPath, typeCode)
            if (nodes.length == 0) failures += "(a) row ${row.businessTermId} path ${row.syntaxPath} selects no node"
            for (i in 0 until nodes.length) selectedBy.getOrPut(nodes.item(i)) { mutableListOf() }.add(row)
        }
        failures += leafFailures(leaves(root), selectedBy)
        return failures
    }

    private fun leafFailures(
        leaves: Set<Node>,
        selectedBy: Map<Node, List<GateRow>>,
    ): List<String> {
        val out = mutableListOf<String>()
        for (leaf in leaves) {
            val by = selectedBy[leaf].orEmpty()
            if (by.size !=
                1
            ) {
                out += "(b) leaf ${describe(leaf)} is selected by ${by.size} rows ${by.map { it.businessTermId }}"
            }
        }
        for (node in selectedBy.keys) {
            if (node !in leaves) out += "(b) a row selects ${describe(node)}, which is no leaf the builder wrote"
        }
        return out
    }

    private fun leaves(element: Element): Set<Node> {
        val out = linkedSetOf<Node>()
        val attributes = element.attributes
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i)
            if (attribute.namespaceURI != XMLConstants.XMLNS_ATTRIBUTE_NS_URI) out += attribute
        }
        var childElements = 0
        var child = element.firstChild
        while (child != null) {
            if (child is Element) {
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
            names += if (n is Attr) "@${n.name}" else n.nodeName
            n = if (n is Attr) n.ownerElement else n.parentNode
        }
        return "/" + names.reversed().joinToString("/") + " = '" + node.textContent + "'"
    }
}
