package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** INV-T-3528: typed constraint records (INV-CS-3604, INV-Q-3526) are parsed, then checked against the catalogue. */
class VariationPointConstraintsTest {
    private fun cat(extra: Map<String, Any?>): Map<String, Any?> =
        mapOf(
            "scope_levels" to listOf("ENTITY", "CLIENT_ENTITY"),
            "options" to listOf(mapOf("key" to "A", "strictness" to "DEFAULT"), mapOf("key" to "B", "strictness" to "NEUTRAL")),
        ) + extra

    private fun record(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
        mapOf("rule" to "r", "refused" to "electing it (svc)", "source" to "INV-REQ-1") + pairs

    private fun parse(
        id: String,
        extra: Map<String, Any?>,
    ) = VariationPointFieldsParser.parse(id, cat(extra), File("fixture.yaml"))

    private fun row(
        id: String,
        fields: VpCatalogueFields,
    ) = ParsedVariationPoint(
        id = id, requirement = "INV-REQ-$id", revision = "r", title = "t", vpClass = VpClass.POLICY, classModifiers = emptyList(),
        scope = listOf("entity"), owner = "svc", setPermission = "vp-01.set", mustBeElected = false, default = "a",
        options = listOf("a", "b"), bounds = null, status = VpStatus.AVAILABLE, statusNote = null, governs = emptyList(),
        formDependence = null, fields = fields,
    )

    private fun violations(vararg rows: Pair<String, Map<String, Any?>>): List<String> =
        VariationPointFieldsChecker.checkConstraints(rows.map { (id, extra) -> row(id, parse(id, extra)) }).map { it.toString() }

    @Test
    fun `parses each kind with its placement`() {
        val f =
            parse(
                "VP-01",
                mapOf(
                    "constraints" to
                        listOf(
                            record("id" to "VP-01.FORM", "kind" to "FORM", "options" to listOf("B"), "forms" to listOf("PRIVATE")),
                            record(
                                "id" to "VP-01.FACT", "kind" to "FACT", "options" to listOf("B"),
                                "fact" to mapOf("holder" to "INSTALLATION", "name" to "gateway"),
                            ),
                        ),
                    "cross_row" to
                        listOf(
                            record(
                                "id" to "VP-01.REQ", "kind" to "REQUIRES", "options" to listOf("B"), "row" to "VP-02",
                                "row_options" to listOf("A"), "at" to "ENTITY",
                            ),
                            record(
                                "id" to "VP-01.CHK", "kind" to "CHECK", "options" to listOf("B"), "check" to "fits",
                                "inputs" to listOf(mapOf("row" to "VP-02"), mapOf("fact" to mapOf("holder" to "ENTITY", "name" to "freq"))),
                            ),
                        ),
                ),
            )
        assertEquals(listOf(ConstraintKind.FORM, ConstraintKind.FACT), f.constraints.map { it.kind })
        assertEquals(listOf(DeploymentForm.PRIVATE), f.constraints[0].forms)
        assertEquals(ConstraintFact(ScopeLevel.INSTALLATION, "gateway"), f.constraints[1].fact)
        assertEquals(ConstraintPlacement.CROSS_ROW, f.crossRow[0].placement)
        assertEquals(ScopeLevel.ENTITY, f.crossRow[0].at)
        assertEquals("VP-02", f.crossRow[1].inputs[0].row)
    }

    @Test
    fun `an unknown form, kind, holder or field fails the parse`() {
        listOf(
            record("id" to "VP-01.X", "kind" to "FORM", "options" to listOf("B"), "forms" to listOf("CLOUD")),
            record("id" to "VP-01.X", "kind" to "MAYBE", "options" to listOf("B")),
            record("id" to "VP-01.X", "kind" to "FACT", "options" to listOf("B"), "fact" to mapOf("holder" to "PLANET", "name" to "n")),
            record("id" to "VP-01.X", "kind" to "FACT", "options" to listOf("B"), "colour" to "red"),
            mapOf("id" to "VP-01.X", "kind" to "FORM", "options" to listOf("B"), "forms" to listOf("SAAS")),
        ).forEach { bad -> assertThrows(IllegalStateException::class.java) { parse("VP-01", mapOf("constraints" to listOf(bad))) } }
    }

    @Test
    fun `the published shape of a valid catalogue raises nothing`() {
        val ok =
            violations(
                "VP-01" to
                    mapOf(
                        "constraints" to listOf(record("id" to "VP-01.FORM", "kind" to "FORM", "options" to listOf("B"), "forms" to listOf("SAAS"))),
                        "cross_row" to
                            listOf(
                                record(
                                    "id" to "VP-01.OFF", "kind" to "EXCLUDES", "options" to listOf("A"), "rows" to listOf("VP-02"),
                                    "scope_level" to "CLIENT_ENTITY",
                                ),
                            ),
                    ),
                "VP-02" to mapOf("multi_select" to true, "cross_option" to listOf(record("id" to "VP-02.SEL", "kind" to "CHECK", "selection" to "WHOLE", "check" to "c", "inputs" to listOf(mapOf("value" to "n", "min" to 2))))),
            )
        assertEquals(emptyList<String>(), ok)
    }

    @Test
    fun `an unknown row or option key fails the check`() {
        fun one(record: Map<String, Any?>) = violations("VP-01" to mapOf("cross_row" to listOf(record)), "VP-02" to emptyMap())
        assertTrue(one(record("id" to "VP-01.R", "kind" to "REQUIRES", "options" to listOf("Z"), "row" to "VP-02", "row_options" to listOf("A"))).any { "'Z'" in it })
        assertTrue(one(record("id" to "VP-01.R", "kind" to "REQUIRES", "options" to listOf("B"), "row" to "VP-03", "row_options" to listOf("A"))).any { "VP-03" in it })
        assertTrue(one(record("id" to "VP-01.R", "kind" to "REQUIRES", "options" to listOf("B"), "row" to "VP-02", "row_options" to listOf("Q"))).any { "'Q'" in it })
        assertTrue(one(record("id" to "VP-01.C", "kind" to "CHECK", "options" to listOf("B"), "check" to "c", "inputs" to listOf(mapOf("row" to "VP-09")))).any { "VP-09" in it })
        assertTrue(one(record("id" to "VP-01.E", "kind" to "EXCLUDES", "options" to listOf("B"), "rows" to listOf("VP-02"), "scope_level" to "GROUP")).any { "GROUP" in it })
    }

    @Test
    fun `each kind needs its fields and each placement its shape`() {
        fun own(record: Map<String, Any?>) = violations("VP-01" to mapOf("constraints" to listOf(record)))
        assertTrue(own(record("id" to "VP-01.F", "kind" to "FORM", "options" to listOf("B"))).isNotEmpty())
        assertTrue(own(record("id" to "VP-01.F", "kind" to "FACT", "options" to listOf("B"))).isNotEmpty())
        assertTrue(own(record("id" to "VP-01.F", "kind" to "FORM", "forms" to listOf("SAAS"))).isNotEmpty())
        assertTrue(own(record("id" to "OTHER.F", "kind" to "FORM", "options" to listOf("B"), "forms" to listOf("SAAS"))).isNotEmpty())
        assertTrue(own(record("id" to "VP-01.F", "kind" to "FORM", "options" to listOf("B"), "forms" to listOf("SAAS", "PRIVATE"))).isNotEmpty())
        // cross_option judges the whole selection of a multi-select row
        assertTrue(violations("VP-01" to mapOf("cross_option" to listOf(record("id" to "VP-01.S", "kind" to "CHECK", "selection" to "WHOLE", "check" to "c", "inputs" to listOf(mapOf("value" to "n")))))).isNotEmpty())
        // cross_row must name another row
        assertTrue(violations("VP-01" to mapOf("cross_row" to listOf(record("id" to "VP-01.C", "kind" to "CHECK", "options" to listOf("B"), "check" to "c", "inputs" to listOf(mapOf("row" to "VP-01")))))).isNotEmpty())
    }
}
