package com.dynokore.platform.integrationcanon.schematron

import com.dynokore.platform.integrationcanon.xml.HardenedXml
import net.sf.saxon.Configuration
import net.sf.saxon.lib.CollectionFinder
import net.sf.saxon.lib.Feature
import net.sf.saxon.lib.ResourceRequest
import net.sf.saxon.lib.ResourceResolver
import net.sf.saxon.lib.UnparsedTextURIResolver
import net.sf.saxon.s9api.Processor
import net.sf.saxon.s9api.SaxonApiException
import net.sf.saxon.s9api.XdmDestination
import net.sf.saxon.s9api.XdmNode
import net.sf.saxon.s9api.XsltExecutable
import net.sf.saxon.trans.XPathException
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.XMLReader
import java.io.Reader
import java.net.URI
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.Source
import javax.xml.transform.sax.SAXSource
import javax.xml.transform.stream.StreamSource

/** A stylesheet or a document was refused: it asks for an external resource, an extension, or does not parse. */
class SchematronRefusedException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** One failed assert read from the SVRL result. */
data class FailedAssert(
    val id: String?,
    val location: String,
    val test: String,
    val role: String?,
    val text: String,
)

/** The result of one validation: the failed asserts in document order; empty when the document passes. */
data class SchematronOutcome(
    val failedAsserts: List<FailedAssert>,
)

/** Called once per compilation, so a test can count compilations. */
fun interface CompilationListener {
    fun compiled()
}

/**
 * A stylesheet compiled once. It holds one immutable executable, shared by all threads; [validate] creates one
 * transformer per call and keeps the SVRL result in memory.
 */
class CompiledSchematron internal constructor(
    private val executable: XsltExecutable,
) {
    /** Validates [document]; throws [SchematronRefusedException] if it does not parse or asks for a resource. */
    fun validate(document: ByteArray): SchematronOutcome {
        val source = SAXSource(hardenedReader(), InputSource(document.inputStream()))
        val svrl = XdmDestination()
        try {
            val transformer = executable.load30()
            transformer.setResultDocumentHandler { href -> refuse("result document refused: $href") }
            transformer.applyTemplates(source, svrl)
        } catch (e: SaxonApiException) {
            throw SchematronRefusedException("document refused: ${e.message}", e)
        }
        return SchematronOutcome(readFailedAsserts(svrl.xdmNode))
    }

    private fun hardenedReader(): XMLReader =
        try {
            HardenedXml
                .saxParserFactory()
                .also { it.isNamespaceAware = true }
                .newSAXParser()
                .xmlReader
        } catch (e: ParserConfigurationException) {
            refuse("no hardened parser: ${e.message}", e)
        } catch (e: SAXException) {
            refuse("no hardened parser: ${e.message}", e)
        }

    private fun readFailedAsserts(result: XdmNode?): List<FailedAssert> {
        val root = result?.let { schematronOutput(it) } ?: refuse("result is not an SVRL document")
        val found = mutableListOf<FailedAssert>()
        collect(root, found)
        return found
    }

    private fun schematronOutput(result: XdmNode): XdmNode? {
        val children = result.axisIterator(net.sf.saxon.s9api.Axis.CHILD)
        var root: XdmNode? = null
        while (children.hasNext()) {
            val child = children.next()
            if (child.nodeKind != net.sf.saxon.s9api.XdmNodeKind.ELEMENT) continue
            if (root != null) return null
            root = child
        }
        val name = root?.nodeName ?: return null
        return if (name.namespaceUri.toString() == SVRL_NS && name.localName == "schematron-output") root else null
    }

    private fun collect(
        node: XdmNode,
        into: MutableList<FailedAssert>,
    ) {
        val children = node.axisIterator(net.sf.saxon.s9api.Axis.CHILD)
        while (children.hasNext()) {
            val child = children.next()
            if (child.nodeKind != net.sf.saxon.s9api.XdmNodeKind.ELEMENT) continue
            val name = child.nodeName
            if (name.namespaceUri.toString() == SVRL_NS && name.localName == "failed-assert") {
                into += toFailedAssert(child)
            } else {
                collect(child, into)
            }
        }
    }

    private fun toFailedAssert(node: XdmNode): FailedAssert {
        fun attr(n: String): String? =
            node.getAttributeValue(
                net.sf.saxon.s9api
                    .QName(n),
            )
        var text = ""
        val children = node.axisIterator(net.sf.saxon.s9api.Axis.CHILD)
        while (children.hasNext()) {
            val child = children.next()
            val name = child.nodeName
            if (child.nodeKind == net.sf.saxon.s9api.XdmNodeKind.ELEMENT &&
                name.namespaceUri.toString() == SVRL_NS &&
                name.localName == "text"
            ) {
                text = child.stringValue
                break
            }
        }
        return FailedAssert(attr("id"), attr("location").orEmpty(), attr("test").orEmpty(), attr("role"), text)
    }

    private companion object {
        const val SVRL_NS = "http://purl.oclc.org/dsdl/svrl"
    }
}

/**
 * Compiles an already-XSLT Schematron stylesheet once into a [CompiledSchematron] (INV-Q-7068, CS-25). Mechanism
 * only: the rule set content comes from the caller. The processor refuses every external resource (documents,
 * unparsed text, collections, result documents), has no extension functions, and parses input with [HardenedXml].
 */
class SchematronRunner(
    private val listener: CompilationListener = CompilationListener { },
) {
    private val processor: Processor = Processor(false).also { harden(it.underlyingConfiguration) }

    /** Compiles [stylesheet]; throws [SchematronRefusedException] if it does not compile or asks for an extension. */
    fun compile(stylesheet: ByteArray): CompiledSchematron {
        val executable =
            try {
                processor.newXsltCompiler().compile(StreamSource(stylesheet.inputStream()))
            } catch (e: SaxonApiException) {
                throw SchematronRefusedException("stylesheet refused: ${e.message}", e)
            }
        listener.compiled()
        return CompiledSchematron(executable)
    }

    /** The live value of the external-functions switch of this runner's own Saxon configuration. */
    fun externalFunctionsAllowed(): Boolean =
        processor.underlyingConfiguration.getBooleanProperty(Feature.ALLOW_EXTERNAL_FUNCTIONS)

    private fun harden(config: Configuration) {
        config.setBooleanProperty(Feature.ALLOW_EXTERNAL_FUNCTIONS, false)
        config.setBooleanProperty(Feature.XINCLUDE, false)
        config.setBooleanProperty(Feature.DTD_VALIDATION, false)
        config.setResourceResolver(
            ResourceResolver { request ->
                denied("external resource refused: ${request.uri}")
            },
        )
        config.setUnparsedTextURIResolver(
            UnparsedTextURIResolver { uri, _, _ -> denied("external text refused: $uri") },
        )
        config.setCollectionFinder(
            CollectionFinder { _, uri -> denied("collection refused: $uri") },
        )
    }
}

private fun refuse(
    message: String,
    cause: Throwable? = null,
): Nothing = throw SchematronRefusedException(message, cause)

private fun denied(message: String): Nothing = throw XPathException(message)
