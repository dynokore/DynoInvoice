package com.dynokore.gst.submission.acceptance

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * `acKeyCoverage` (INV-T-5964): run by the Gradle task of that name after every test task of the build, over the
 * JUnit XML those tasks wrote. A skipped test does not count.
 */
class AcKeyCoverageGate {
    @Test
    fun `acKeyCoverage`() {
        val root = File(System.getProperty("gst.root.dir"))
        val table = File(root, "ac-keys.tsv").takeIf { it.isFile }?.readText()
        val modules =
            root.listFiles { f ->
                f.isDirectory &&
                    (f.name.startsWith("submission-") || f.name == "acceptance")
            }
        val names =
            modules
                .orEmpty()
                .flatMap { File(it, "build/test-results").walkTopDown().toList() }
                .filter { it.name.startsWith("TEST-") && it.extension == "xml" }
                .flatMap(::executedTestNames)
        val findings = AcKeyCoverage.findings(table, names)
        assertTrue(findings.isEmpty(), "acKeyCoverage:\n" + findings.joinToString("\n"))
    }

    private fun executedTestNames(xml: File): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val cases = doc.getElementsByTagName("testcase")
        return (0 until cases.length)
            .map { cases.item(it) }
            .filter { node ->
                (0 until node.childNodes.length).none { node.childNodes.item(it).nodeName == "skipped" }
            }.map { it.attributes.getNamedItem("name").nodeValue }
    }
}
