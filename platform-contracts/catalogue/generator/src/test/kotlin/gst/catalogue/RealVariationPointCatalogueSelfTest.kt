package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
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
    private val pendingFile = File("../variation-points/pending-fields.yaml")

    @Test
    fun `INV-T-3000 every row carries Keel's catalogue fields, is retired, or names its open question`() {
        val rows = VariationPointYamlParser.parse(vendoredFile, pendingFile)
        val violations = VariationPointFieldsChecker.check(rows)
        assertTrue(violations.isEmpty(), "catalogue-field violations:\n" + violations.joinToString("\n"))
    }

    @Test
    fun `INV-T-3000 the vendored fields carry Keel's rulings for VP-13 and VP-08`() {
        val rows = VariationPointYamlParser.parse(vendoredFile, pendingFile).associateBy { it.id }
        val vp13 = rows.getValue("VP-13").fields!!
        assertEquals(listOf("EARLIER_DATE", "FILER_OF_RECORD"), vp13.options.map { it.key }) // TPL-10's own example
        assertEquals(listOf(Strictness.DEFAULT, Strictness.LOOSER), vp13.options.map { it.strictness })
        val vp08 = rows.getValue("VP-08").fields!!
        assertEquals(listOf(ScopeLevel.INSTALLATION, ScopeLevel.TENANT), vp08.scopeLevels)
        val window = vp08.options[1].typedValue!!.fields.getValue("window")
        assertEquals(Direction.DOWN_STRICTER, window.direction)
        assertEquals(0, window.max!!.number!!.compareTo(java.math.BigDecimal(8)))
    }

    @Test
    fun `INV-T-3148 every LOOSER option carries the ruled acknowledgers`() {
        val rows = VariationPointYamlParser.parse(vendoredFile, pendingFile).associateBy { it.id }
        val looser =
            rows.values.flatMap { r -> r.fields?.options.orEmpty().filter { it.strictness == Strictness.LOOSER }.map { r.id to it } }
        assertEquals(10, looser.size)
        looser.forEach { (id, o) -> assertTrue(o.acknowledgedBy.isNotEmpty(), "$id ${o.key} has no acknowledgers") }

        fun roles(id: String) = rows.getValue(id).fields!!.options.single { it.strictness == Strictness.LOOSER }.acknowledgedBy
        assertEquals(listOf("client.signatory"), roles("VP-13").single().roles)
        assertEquals(listOf("tax.approver"), roles("VP-50").single().roles)
        listOf("VP-59", "VP-62").forEach {
            assertEquals(
                listOf(listOf("tax.approver") to AckCondition.ALWAYS, listOf("client.signatory") to AckCondition.UNDER_DELEGATION),
                roles(it).map { a -> a.roles to a.condition },
                it,
            )
        }
        assertEquals(listOf("entity.admin"), roles("VP-61").single().roles)
        listOf("VP-03", "VP-07", "VP-40", "VP-44").forEach { assertTrue(roles(it).any { a -> a.roles == listOf("tenant.admin") }, it) }
        val vp66 = roles("VP-66").single()
        assertEquals(mapOf(DeploymentForm.SAAS to listOf("security.officer"), DeploymentForm.PRIVATE to listOf("tax.approver")), vp66.rolesByForm)
        assertTrue(vp66.distinctFromSetter)
    }

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
