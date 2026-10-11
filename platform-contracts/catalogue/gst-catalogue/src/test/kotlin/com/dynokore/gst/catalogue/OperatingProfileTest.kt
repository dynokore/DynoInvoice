package com.dynokore.gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** INV-T-3277: the published library exposes the operating profiles with typed access. */
class OperatingProfileTest {
    @Test
    fun `profiles reference variation points and their option keys`() {
        OperatingProfile.entries.forEach { p ->
            p.values.filter { it.unresolved == null }.forEach { v ->
                val keys = Catalogue.optionKeys(v.variationPoint)
                (listOfNotNull(v.option, v.otherwiseOption) + v.options + v.alternatives).forEach {
                    assertTrue(it in keys, "${p.id} ${v.variationPoint.id} $it")
                }
            }
        }
        val prf1 = OperatingProfile.fromId("PRF-1")!!
        assertEquals("EXTERNAL_ADVISOR", prf1.valueFor(VariationPoint.VP_03)!!.option)
        assertEquals(1, prf1.version)
        assertEquals(ProfileStatus.AVAILABLE, prf1.status)
        assertNull(prf1.valueFor(VariationPoint.VP_78))
    }

    @Test
    fun `planned variation points and unresolved values are marked`() {
        val prf7 = OperatingProfile.PRF_7
        assertTrue(VariationPoint.VP_08 in prf7.plannedVariationPoints)
        val gap = prf7.unresolvedValues.single()
        assertEquals(VariationPoint.VP_08, gap.variationPoint)
        assertEquals("INV-Q-3320", gap.unresolved!!.pendingQuestion)
        assertNull(gap.option)
    }

    @Test
    fun `differences show what a new version changes`() {
        val v1 = OperatingProfile.PRF_6.values
        val vp10 = VariationPoint.VP_10
        val submitOnly = listOf("SUBMIT_INVOICE_DATA")
        val narrowed = v1.map { if (it.variationPoint == vp10) it.copy(options = submitOnly) else it }
        val vp20 = VariationPoint.VP_20
        val daily = ProfileValue(vp20, vp20.requirement, "daily batch", option = "DAILY_WINDOW")
        val v2 = narrowed + daily
        val changes = OperatingProfiles.differences(v1, v2)
        assertEquals(
            listOf(VariationPoint.VP_10 to ProfileChangeKind.CHANGED, VariationPoint.VP_20 to ProfileChangeKind.ADDED),
            changes.map { it.variationPoint to it.kind },
        )
        assertTrue(OperatingProfiles.differences(v1, v1.map { it.copy(text = "reworded") }).isEmpty())
        assertTrue(OperatingProfile.entries.all { it.previousVersion == null && it.changesFromPrevious.isEmpty() })
    }
}
