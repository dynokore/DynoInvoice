package com.dynokore.gst.rules.bootstrap

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** INV-AC-2182 "no operation serves pack artefacts": the pinned contract, and the probes that make the check bite. */
class ServedArtefactScanTest {
    private val pinned = ServedArtefactScan.pinnedFiles()

    @Test
    fun `INV-AC-2182 no schema of the rules openapi document has an artefact property`() {
        pinned.keys.contains(ServedArtefactScan.OPENAPI) shouldBe true
        ServedArtefactScan.findings(pinned).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-2182 the staging event payload is the one file that has the artefact properties`() {
        ServedArtefactScan.ALLOWED shouldBe "rules/schemas/rule-pack-staged-payload.schema.json"
        val holders =
            pinned.filterKeys { it.endsWith(".json") }.filterValues { text ->
                ServedArtefactScan.ARTEFACT_PROPERTIES.any { "\"$it\"" in text }
            }
        holders.keys shouldBe setOf(ServedArtefactScan.ALLOWED)
    }

    @Test
    fun `INV-AC-2182 probe - a property contents added to a copy of RegulatoryPack fails`() {
        val openapi = pinned.getValue(ServedArtefactScan.OPENAPI)
        val anchor = openapi.lines().indexOfFirst { it.trim() == "RegulatoryPack:" }
        (anchor >= 0) shouldBe true
        val lines = openapi.lines().toMutableList()
        val propertiesAt = (anchor until lines.size).first { lines[it].trim() == "properties:" }
        val indent = lines[propertiesAt].takeWhile { it == ' ' } + "  "
        lines.add(propertiesAt + 1, "${indent}contents:\n$indent  type: string")
        ServedArtefactScan
            .findings(pinned + (ServedArtefactScan.OPENAPI to lines.joinToString("\n")))
            .shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-2182 probe - the same name in a copy of another schema file fails`() {
        val other = pinned.keys.first { it.endsWith("rule-pack-activated-payload.schema.json") }
        val member = "\"signature\": {\"type\": \"string\"},"
        val text = pinned.getValue(other).replaceFirst("\"properties\": {", "\"properties\": {$member")
        (text != pinned.getValue(other)) shouldBe true
        ServedArtefactScan.findings(pinned + (other to text)).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-2182 probe - a path with a suffix is not the allowed file`() {
        val copy = pinned.getValue(ServedArtefactScan.ALLOWED)
        ServedArtefactScan
            .findings(pinned + ("${ServedArtefactScan.ALLOWED}.bak" to copy))
            .shouldNotBeEmpty()
        ServedArtefactScan.findings(pinned - ServedArtefactScan.ALLOWED).shouldNotBeEmpty()
    }
}
