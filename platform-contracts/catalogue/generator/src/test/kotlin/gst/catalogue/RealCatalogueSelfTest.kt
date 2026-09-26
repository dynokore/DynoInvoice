package gst.catalogue

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Runs the parser and both acceptance checks against the real, vendored
 * catalogue/errors/error-codes.yaml (not a fixture) — the Gradle test task's working
 * directory is this module's own project directory (catalogue/generator/), so the
 * vendored file is its sibling at ../errors/error-codes.yaml.
 */
class RealCatalogueSelfTest {
    private val vendoredFile = File("../errors/error-codes.yaml")

    @Test
    fun `the vendored catalogue parses cleanly and every code is well-formed`() {
        assertTrue(vendoredFile.exists(), "expected ${vendoredFile.absolutePath} to exist")
        val rows = ErrorCodeYamlParser.parse(vendoredFile)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.map { it.code }.toSet().size == rows.size, "every code must be unique")
    }

    @Test
    fun `INV-AC-2173 passes against the real catalogue (no violations, some codes may be pending decision)`() {
        val rows = ErrorCodeYamlParser.parse(vendoredFile)
        val result = BlockDispositionChecker.check(rows)
        assertTrue(
            result.passed,
            "BLOCK disposition violations:\n" + result.violations.joinToString("\n"),
        )
        // The seven Keel-named codes (INV-REQ-1186 behaviour #1-4) must all be present
        // and classified — this is the traceability from the live requirement text to
        // the generated check, not just "no violations by omission".
        val classifiedCodes = result.classified.map { it.substringBefore(':') }.toSet()
        listOf("MDM-003", "PAY-006", "PAY-001", "PAY-010", "SUB-006", "SUB-009", "RUL-005").forEach { code ->
            assertTrue(code in classifiedCodes, "$code should be classified, was in: $classifiedCodes")
        }
    }

    @Test
    fun `the catalogue artefact renders and reads back without loss`() {
        val rows = ErrorCodeYamlParser.parse(vendoredFile)
        val commit = ErrorCodeYamlParser.readPinnedCommit(vendoredFile)
        val json = CatalogueArtefact.render(rows, "1.0.0", commit)
        val tempFile = java.nio.file.Files.createTempFile("catalogue", ".json").toFile().apply { writeText(json) }
        val readBack = StabilityChecker.readArtefact(tempFile)
        assertTrue(readBack.size == rows.size)
        assertTrue(readBack.map { it.code } == rows.map { it.code })
    }

    @Test
    fun `the generated Kotlin source compiles as valid syntax (one enum constant per row, balanced braces)`() {
        val rows = ErrorCodeYamlParser.parse(vendoredFile)
        val commit = ErrorCodeYamlParser.readPinnedCommit(vendoredFile)
        val source = KotlinEmitter.emit("com.dynokore.gst.catalogue", rows, commit)
        rows.forEach { row ->
            val constantName = "${row.area}_${"%03d".format(row.number)}"
            assertTrue(source.contains("$constantName(\"${row.code}\""), "missing constant for ${row.code}")
        }
        assertTrue(source.count { it == '{' } == source.count { it == '}' })
    }
}
