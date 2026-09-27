package gst.catalogue

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Runs the parser and both acceptance checks against the real, vendored
 * catalogue/variation-points/variation-points.yaml (not a fixture) — mirrors
 * [RealCatalogueSelfTest]'s own precedent for error-codes.yaml.
 */
class RealVariationPointCatalogueSelfTest {
    private val vendoredFile = File("../variation-points/variation-points.yaml")

    @Test
    fun `the vendored catalogue parses cleanly and every id is well-formed and unique`() {
        assertTrue(vendoredFile.exists(), "expected ${vendoredFile.absolutePath} to exist")
        val rows = VariationPointYamlParser.parse(vendoredFile)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.map { it.id }.toSet().size == rows.size, "every id must be unique")
        assertTrue(rows.map { it.requirement }.toSet().size == rows.size, "every requirement key must be unique")
    }

    @Test
    fun `INV-AC-2236 passes against the real catalogue`() {
        val rows = VariationPointYamlParser.parse(vendoredFile)
        val violations = MustBeElectedAvailabilityChecker.check(rows)
        assertTrue(violations.isEmpty(), "must-be-elected/question-closing availability violations:\n" + violations.joinToString("\n"))
        // Every requirement the AC names must actually be present in the vendored
        // catalogue — otherwise the check above would pass by omission, not by
        // genuinely finding each one available.
        val presentRequirements = rows.map { it.requirement }.toSet()
        MustBeElectedAvailabilityChecker.namedQuestionClosingRequirements.forEach { req ->
            assertTrue(req in presentRequirements, "$req (named by INV-AC-2236) is missing from the vendored catalogue")
        }
    }

    @Test
    fun `INV-AC-1786's checkable half passes against the real catalogue`() {
        val rows = VariationPointYamlParser.parse(vendoredFile)
        val result = NonWaivableFloorChecker.check(rows)
        assertTrue(result.passed, "non-waivable-floor violations:\n" + result.violations.joinToString("\n"))
    }

    @Test
    fun `the catalogue artefact renders and every row round-trips`() {
        val rows = VariationPointYamlParser.parse(vendoredFile)
        val json = VariationPointCatalogueArtefact.render(rows, "1.1.0")
        assertTrue(json.contains("\"catalogue\": \"gst-catalogue-variation-points\""))
        rows.forEach { row -> assertTrue(json.contains("\"id\": \"${row.id}\""), "missing ${row.id} in the artefact") }
    }

    @Test
    fun `the generated Kotlin source compiles as valid syntax (one enum constant per row, balanced braces)`() {
        val rows = VariationPointYamlParser.parse(vendoredFile)
        val source = VariationPointKotlinEmitter.emit("com.dynokore.gst.catalogue", rows)
        rows.forEach { row ->
            val constantName = row.id.replace("-", "_")
            assertTrue(source.contains("$constantName(\"${row.id}\""), "missing constant for ${row.id}")
        }
        assertTrue(source.count { it == '{' } == source.count { it == '}' })
        assertTrue(source.count { it == '(' } == source.count { it == ')' })
    }
}
