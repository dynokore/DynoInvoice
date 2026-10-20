package com.dynokore.platform.integrationcanon.xml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.time.Duration
import javax.xml.stream.XMLStreamConstants
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

/** CS-25 / INV-Q-7068: each hardened factory refuses an XXE payload and a billion-laughs payload. */
class HardenedXmlTest {
    private val secret = "secret-host-content-4711"

    /** Parses or transforms [xml] with the named factory and returns all text seen. */
    private val runners: Map<String, (String) -> String> =
        mapOf(
            "dom" to { xml ->
                val doc = HardenedXml.documentBuilderFactory().newDocumentBuilder().parse(xml.byteInputStream())
                doc.documentElement.textContent + "|" + doc.documentElement.tagName
            },
            "sax" to { xml ->
                val text = StringBuilder()
                val handler =
                    object : DefaultHandler() {
                        override fun characters(
                            ch: CharArray,
                            start: Int,
                            length: Int,
                        ) {
                            text.append(ch, start, length)
                        }

                        override fun startElement(
                            uri: String?,
                            localName: String?,
                            qName: String,
                            attributes: org.xml.sax.Attributes?,
                        ) {
                            text.append("<").append(qName).append(">")
                        }
                    }
                HardenedXml.saxParserFactory().newSAXParser().parse(xml.byteInputStream(), handler)
                text.toString()
            },
            "stax" to { xml ->
                val reader = HardenedXml.xmlInputFactory().createXMLStreamReader(StringReader(xml))
                val text = StringBuilder()
                while (reader.hasNext()) {
                    when (reader.next()) {
                        XMLStreamConstants.CHARACTERS -> text.append(reader.text)
                        XMLStreamConstants.ENTITY_REFERENCE -> text.append(reader.text)
                        XMLStreamConstants.START_ELEMENT -> text.append("<").append(reader.localName).append(">")
                        else -> Unit
                    }
                }
                text.toString()
            },
            "transformer" to { xml ->
                val out = StringWriter()
                HardenedXml.transformerFactory().newTransformer().transform(
                    StreamSource(StringReader(xml)),
                    StreamResult(out),
                )
                out.toString()
            },
        )

    private fun xxe(file: File) = "<!DOCTYPE d [<!ENTITY x SYSTEM \"${file.toURI()}\">]><d>&x;</d>"

    private fun laughs(): String {
        val sb = StringBuilder("<!DOCTYPE d [<!ENTITY lol0 \"lol\">")
        for (i in 1..9) sb.append("<!ENTITY lol$i \"${"&lol${i - 1};".repeat(10)}\">")
        return sb.append("]><d>&lol9;</d>").toString()
    }

    @Test
    fun `an XXE payload is refused and the file content appears in no result`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "host.txt").also { it.writeText(secret) }
        runners.forEach { (name, run) ->
            var result: String? = null
            val failure = runCatching { result = run(xxe(file)) }.exceptionOrNull()
            assertTrue(failure != null, "$name accepted the XXE payload")
            assertFalse(result.orEmpty().contains(secret), name)
            assertFalse(failure?.message.orEmpty().contains(secret), name)
        }
    }

    @Test
    fun `a billion-laughs payload is refused within two seconds without running out of memory`() {
        runners.forEach { (name, run) ->
            val failure =
                assertTimeoutPreemptively<Throwable?>(Duration.ofSeconds(2)) {
                    runCatching { run(laughs()) }.exceptionOrNull()
                }
            assertTrue(failure != null, "$name accepted the billion-laughs payload")
            assertFalse(failure is OutOfMemoryError, name)
        }
    }

    @Test
    fun `a benign document type declaration with an internal subset only is refused by the DOM and SAX parsers`() {
        // Secure processing alone accepts this document, so only disallow-doctype-decl can refuse it (P9).
        val xml = "<!DOCTYPE d [<!ELEMENT d (#PCDATA)>]><d>ok</d>"
        listOf("dom", "sax").forEach { name ->
            val failure = runCatching { runners.getValue(name)(xml) }.exceptionOrNull()
            assertTrue(failure != null, "$name accepted a document type declaration")
        }
    }

    @Test
    fun `a control document without a document type declaration parses to the expected tree`() {
        val xml = "<d><e>ok</e></d>"
        assertEquals("ok|d", runners.getValue("dom")(xml))
        assertEquals("<d><e>ok", runners.getValue("sax")(xml))
        assertEquals("<d><e>ok", runners.getValue("stax")(xml))
        assertTrue(runners.getValue("transformer")(xml).endsWith(xml))
    }

    // The factory functions of HardenedXml.kt, read from its text (no reflection).
    private val expected = setOf("documentBuilderFactory", "saxParserFactory", "xmlInputFactory", "transformerFactory")

    private fun functionNames(text: String): Set<String> =
        Regex("""fun\s+(\w+)\s*\(""").findAll(text).map { it.groupValues[1] }.toSet()

    private fun hardenedXmlText(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val root = requireNotNull(dir) { "repository root not found" }
        return File(
            root,
            "integration-canon/src/impl/kotlin/com/dynokore/platform/integrationcanon/xml/HardenedXml.kt",
        ).readText()
    }

    @Test
    fun `the set of functions equals the four factory functions`() {
        assertEquals(expected, functionNames(hardenedXmlText()))
    }

    @Test
    fun `probe - a fifth function or a missing function fails the comparison`(
        @TempDir dir: File,
    ) {
        val fifth = File(dir, "H.kt").also { it.writeText(hardenedXmlText() + "\nfun fifth() = 1\n") }
        assertNotEquals(expected, functionNames(fifth.readText()))
        val missing = hardenedXmlText().replace("fun xmlInputFactory(", "val xmlInputFactory = (")
        assertNotEquals(expected, functionNames(missing))
    }

    @Test
    fun `each function returns a new factory`() {
        assertTrue(HardenedXml.documentBuilderFactory() !== HardenedXml.documentBuilderFactory())
        assertTrue(HardenedXml.saxParserFactory() !== HardenedXml.saxParserFactory())
        assertTrue(HardenedXml.xmlInputFactory() !== HardenedXml.xmlInputFactory())
        assertTrue(HardenedXml.transformerFactory() !== HardenedXml.transformerFactory())
        assertThrows(Exception::class.java) { runners.getValue("dom")("<d>") }
    }

    @Test
    fun `the transformer factory is the JDK one although Saxon is the JAXP provider`() {
        assertEquals("net.sf.saxon.TransformerFactoryImpl", TransformerFactory.newInstance().javaClass.name)
        assertEquals(
            "com.sun.org.apache.xalan.internal.xsltc.trax.TransformerFactoryImpl",
            HardenedXml.transformerFactory().javaClass.name,
        )
    }
}
