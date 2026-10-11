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
