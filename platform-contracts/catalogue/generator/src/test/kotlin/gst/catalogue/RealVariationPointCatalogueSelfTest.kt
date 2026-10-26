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
        // INV-Q-3320 A': the default pair's window is 1 to 8 h, default 4, like any pair's; the key is kept.
        assertEquals("DEFAULT_PAIR_4H", vp08.options[0].key)
        val defaultWindow = vp08.options[0].typedValue!!.fields.getValue("window")
        assertEquals(listOf(1, 8, 4), listOf(defaultWindow.min, defaultWindow.max, defaultWindow.default).map { it!!.number!!.toInt() })
        assertEquals(Direction.DOWN_STRICTER, defaultWindow.direction)
    }

    @Test
    fun `INV-Q-3202 VP-57 and VP-67 take their maximum from their own installation rows`() {
        val rows = VariationPointYamlParser.parse(vendoredFile, pendingFile).associateBy { it.id }
        val vp57 = rows.getValue("VP-57").fields!!
        assertEquals(BoundValue(row = "VP-79"), vp57.options.single { it.key == "SHORTER" }.typedValue!!.max)
        assertEquals("VP-79", vp57.installationParameterRef!!.row)
        assertEquals("INV-REQ-3210", vp57.installationParameterRef!!.requirement)
        val vp67 = rows.getValue("VP-67").fields!!
        assertEquals(BoundValue(row = "VP-80"), vp67.options.mapNotNull { it.typedValue?.max }.single { it.row != null })
        assertEquals("INV-REQ-3211", vp67.installationParameterRef!!.requirement)
        assertTrue(VariationPointFieldsChecker.checkRowReferences(rows.values.toList()).isEmpty())
    }

    @Test
    fun `INV-T-9275 VP-82 offers exactly NO_EXTRA_PAIRS and CONFLICTING_PAIRS, planned, none acknowledged`() {
        val vp82 = VariationPointYamlParser.parse(vendoredFile, pendingFile).single { it.id == "VP-82" }
        assertEquals("INV-REQ-9271", vp82.requirement)
        assertEquals(VpStatus.PLANNED, vp82.status)
        assertEquals("vp-82.set", vp82.setPermission)
        val fields = vp82.fields!!
        assertEquals(listOf(ScopeLevel.TENANT), fields.scopeLevels)
        assertEquals(listOf("NO_EXTRA_PAIRS", "CONFLICTING_PAIRS"), fields.options.map { it.key })
        assertEquals(listOf(Strictness.DEFAULT, Strictness.STRICTER), fields.options.map { it.strictness })
        assertTrue(fields.options.all { it.acknowledgedBy.isEmpty() })
        assertTrue(fields.options[0].typedValue == null)
        assertTrue(fields.options[1].typedValue != null)
    }

    @Test
    fun `INV-T-8291 VP-81 offers exactly the three ruled options, planned, set and acknowledged by tenant admin`() {
        val vp81 = VariationPointYamlParser.parse(vendoredFile, pendingFile).single { it.id == "VP-81" }
        assertEquals("INV-REQ-8287", vp81.requirement)
        assertEquals(VpStatus.PLANNED, vp81.status)
        assertEquals("vp-81.set", vp81.setPermission)
        val fields = vp81.fields!!
        assertEquals(listOf(ScopeLevel.TENANT), fields.scopeLevels)
        assertEquals(listOf("ONE_HOLDER_WITHDRAW_BLOCKED", "ONE_HOLDER", "ANY_NUMBER"), fields.options.map { it.key })
        assertEquals(listOf(Strictness.DEFAULT, Strictness.LOOSER, Strictness.LOOSER), fields.options.map { it.strictness })
        assertTrue(fields.options[0].acknowledgedBy.isEmpty())
        fields.options.drop(1).forEach {
            val ack = it.acknowledgedBy.single()
            assertEquals(listOf("tenant.admin"), ack.roles, it.key)
            assertEquals(AckCondition.ALWAYS, ack.condition, it.key)
            assertTrue(!ack.distinctFromSetter, it.key)
        }
        assertTrue(fields.options.all { it.typedValue == null })
    }

    @Test
    fun `INV-T-8828 VP-08 is available with the options DEFAULT_PAIR_4H then OTHER_NAMED_PAIR`() {
        val vp08 = VariationPointYamlParser.parse(vendoredFile, pendingFile).single { it.id == "VP-08" }
        assertEquals("INV-REQ-1269", vp08.requirement)
        assertEquals(VpStatus.AVAILABLE, vp08.status)
        assertEquals(listOf("DEFAULT_PAIR_4H", "OTHER_NAMED_PAIR"), vp08.fields!!.options.map { it.key })
    }

    @Test
    fun `INV-T-10183 VP-29, VP-38 and VP-65 are available with the ruled option keys in order`() {
        val rows = VariationPointYamlParser.parse(vendoredFile, pendingFile).associateBy { it.id }
        val expected =
            mapOf(
                "VP-29" to ("INV-REQ-1290" to listOf("SUGGEST_CONFIRM", "AUTO_APPLY", "OFF")),
                "VP-38" to ("INV-REQ-1299" to listOf("DEFAULT_24H_1H", "SHORTER")),
                "VP-65" to ("INV-REQ-1548" to listOf("DEFAULT_24H_1H", "SHORTER")),
            )
        expected.forEach { (id, want) ->
            val row = rows.getValue(id)
            assertEquals(want.first, row.requirement, id)
            assertEquals(VpStatus.AVAILABLE, row.status, id)
            assertEquals(want.second, row.fields!!.options.map { it.key }, id)
        }
    }

    @Test
    fun `INV-T-3148 every LOOSER option carries the ruled acknowledgers`() {
        val rows = VariationPointYamlParser.parse(vendoredFile, pendingFile).associateBy { it.id }
        val looser =
            rows.values.flatMap { r -> r.fields?.options.orEmpty().filter { it.strictness == Strictness.LOOSER }.map { r.id to it } }
        assertEquals(12, looser.size)
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
    fun `INV-T-3528 the artefact keeps crossRow and crossOption as text and lists the typed records under constraints`() {
        val json = VariationPointCatalogueArtefact.render(VariationPointYamlParser.parse(vendoredFile), "1.1.0")
        assertTrue(Regex("\"crossRow\": \"").containsMatchIn(json), "crossRow is no longer text")
        assertTrue(Regex("\"crossOption\": \"").containsMatchIn(json), "crossOption is no longer text")
        assertTrue(!Regex("\"cross(Row|Option)\": \\[").containsMatchIn(json), "crossRow or crossOption became a list")
        assertTrue(json.contains("\"constraints\": [{ \"id\": "), "no constraints list")
        listOf("constraints", "cross_row", "cross_option").forEach {
            assertTrue(json.contains("\"placement\": \"$it\""), "no record placed in $it")
        }
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

    @Test
    fun `INV-T-10133 every row sets through its own vp-NN set key, the first row of each of the ten groups of INV-T-10132, VP-81, VP-82 and the retired VP-27`() {
        val rows = VariationPointYamlParser.parse(vendoredFile, pendingFile).associateBy { it.id }
        assertEquals(82, rows.size)
        rows.values.forEach { assertEquals(it.id.lowercase() + ".set", it.setPermission) }
        assertEquals("vp-82.set", rows.getValue("VP-82").setPermission)
        listOf("VP-14", "VP-01", "VP-26", "VP-21", "VP-66", "VP-04", "VP-05", "VP-30", "VP-45", "VP-52").forEach {
            assertEquals(it.lowercase() + ".set", rows.getValue(it).setPermission)
        }
        assertEquals("vp-27.set", rows.getValue("VP-27").setPermission) // retired row, key supplied by check-drift.py
        assertTrue(!VariationPointCatalogueArtefact.render(rows.values.toList(), "1.1.0").contains("setBy"))
        assertTrue(!VariationPointKotlinEmitter.emit("com.dynokore.gst.catalogue", rows.values.toList()).contains("setBy"))
    }
}
