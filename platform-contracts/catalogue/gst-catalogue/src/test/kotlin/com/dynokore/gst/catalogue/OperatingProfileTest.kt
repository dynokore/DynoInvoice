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
    fun `planned variation points are marked, and PRF-7 VP-08 is the typed default pair with a 2 hour window`() {
        val prf7 = OperatingProfile.PRF_7
        assertTrue(VariationPoint.VP_08 in prf7.plannedVariationPoints)
        assertTrue(OperatingProfile.entries.all { it.unresolvedValues.isEmpty() })
        val vp08 = prf7.valueFor(VariationPoint.VP_08)!!
        assertEquals("DEFAULT_PAIR_4H", vp08.option)
        assertNull(vp08.unresolved)
        val window = vp08.typedValue!!.fields.getValue("window")
        assertEquals(ValueType.DURATION, window.type)
        assertEquals("hours", window.unit)
        assertEquals(0, window.value!!.compareTo(java.math.BigDecimal(2)))
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
        // INV-AC-2231: PRF-7 v2 against the previous snapshot's v1 shows VP-08 only; the others are unchanged.
        val prf7 = OperatingProfile.PRF_7
        assertEquals(2, prf7.version)
        assertEquals(1, prf7.previousVersion)
        assertEquals(
            listOf(VariationPoint.VP_08 to ProfileChangeKind.CHANGED),
            prf7.changesFromPrevious.map { it.variationPoint to it.kind },
        )
        val others = OperatingProfile.entries.filter { it != prf7 }
        assertTrue(others.all { it.previousVersion == it.version && it.changesFromPrevious.isEmpty() })
    }

    @Test
    fun `INV-T-3636 PRF-2's VP-01 condition is typed as VP-01's directory fact`() {
        val vp01 = OperatingProfile.PRF_2.values.single { it.variationPoint == VariationPoint.VP_01 }
        val record = VariationPoint.VP_01.constraints.single { it.id == "VP-01.DIRECTORY_CONNECTED" }
        assertEquals(ConditionFact(record.id, record.fact!!), vp01.conditionFact)
        assertEquals("PLATFORM_IDENTITY", vp01.otherwiseOption)
    }
}
