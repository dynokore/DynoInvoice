package com.dynokore.gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** INV-T-3000: the published library exposes option keys (TPL-10) and the other catalogue fields. */
class CatalogueOptionKeysTest {
    @Test
    fun `VP-13 option keys match TPL-10's own example`() {
        assertEquals(listOf("EARLIER_DATE", "FILER_OF_RECORD"), Catalogue.optionKeys("VP-13"))
    }

    @Test
    fun `every row with fields has one key per option, in catalogue order`() {
        VariationPoint.entries.filter { it.optionSpecs != null }.forEach { vp ->
            val specs = vp.optionSpecs.orEmpty()
            assertEquals(vp.options, specs.map { it.label }, vp.id)
            assertEquals(specs.map { it.key }, Catalogue.optionKeys(vp))
            assertTrue(vp.scopeLevels.orEmpty().isNotEmpty(), vp.id)
            assertTrue(vp.pendingQuestions.isEmpty(), vp.id)
        }
    }

    @Test
    fun `a pending row names its question instead of returning keys`() {
        VariationPoint.entries.filter { it.optionSpecs == null && it.status != VpStatus.RETIRED }.forEach { vp ->
            assertTrue(vp.pendingQuestions.isNotEmpty(), vp.id)
            val error = assertThrows(IllegalStateException::class.java) { Catalogue.optionKeys(vp) }
            assertTrue(error.message.orEmpty().contains(vp.pendingQuestions.first()), vp.id)
        }
    }

    @Test
    fun `strictness and typed values are exposed`() {
        assertEquals(Strictness.LOOSER, VariationPoint.VP_13.optionSpecs!![1].strictness)
        val window =
            VariationPoint.VP_08.optionSpecs!![1]
                .typedValue!!
                .fields
                .getValue("window")
        assertEquals(Direction.DOWN_STRICTER, window.direction)
        assertEquals(listOf(ScopeLevel.INSTALLATION, ScopeLevel.TENANT), VariationPoint.VP_08.scopeLevels)
        assertTrue(VariationPoint.VP_10.multiSelect)
        assertThrows(IllegalArgumentException::class.java) { Catalogue.optionKeys("VP-00") }
    }
}

/** INV-T-3528: the typed constraint records of INV-CS-3604 are exposed beside the other catalogue fields. */
class CatalogueConstraintsTest {
    @Test
    fun `the typed constraints of INV-CS-3604 and its fix are exposed`() {
        val byId = VariationPoint.allConstraints().associate { (vp, c) -> c.id to (vp to c) }
        val off = byId.getValue("VP-74.OFF_NO_CLIENT_ENTITY_SCOPE").second
        assertEquals(ConstraintKind.EXCLUDES, off.kind)
        assertEquals(ConstraintPlacement.CROSS_ROW, off.placement)
        assertEquals(ScopeLevel.CLIENT_ENTITY, off.scopeLevel)
        assertEquals(listOf("VP-04", "VP-10", "VP-11", "VP-12", "VP-13", "VP-62"), off.rows)
        assertEquals(listOf(DeploymentForm.SAAS), byId.getValue("VP-45.FORM").second.forms)
        assertEquals(listOf(DeploymentForm.PRIVATE), byId.getValue("VP-52.FORM").second.forms)
        assertEquals("VP-49", byId.getValue("VP-25.OBSERVE_ROUTE").second.row)
        assertEquals(ConstraintSelection.WHOLE, byId.getValue("VP-46.RECIPIENTS").second.selection)
        assertEquals("weekly_window_fits", byId.getValue("VP-20.WEEKLY_WINDOW_FITS").second.check)
        assertEquals(
            ConstraintFact(ScopeLevel.ENTITY, "filing_workflow_principal_named"),
            byId.getValue("VP-14.FILING_WORKFLOW_PRINCIPAL").second.fact,
        )
        assertTrue(
            byId
                .getValue("VP-71.CONNECTION_TEST")
                .second.refused
                .startsWith("activating the connection"),
        )
        // VP-64 is deliberately untyped (INV-Q-3616)
        assertTrue(VariationPoint.VP_64.constraints.isEmpty())
        // the rule text stays readable for 1.x readers of crossRow / crossOption
        assertTrue(
            VariationPoint.VP_20.crossRow
                .orEmpty()
                .contains("VP-35"),
        )
        assertTrue(
            VariationPoint.VP_46.crossOption
                .orEmpty()
                .contains("sole-operator"),
        )
        // row-referenced bounds, exclusive keys and per-form defaults are unchanged beside them
        assertEquals(mapOf("SAAS" to "ON", "PRIVATE" to "OFF"), VariationPoint.VP_74.defaultByForm)
        assertEquals(listOf("NONE"), VariationPoint.VP_10.exclusiveKeys)
    }
}
