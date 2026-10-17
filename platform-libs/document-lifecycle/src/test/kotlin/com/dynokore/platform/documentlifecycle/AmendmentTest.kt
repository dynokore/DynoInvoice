package com.dynokore.platform.documentlifecycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AmendmentTest {
    private val t = AmendmentFixtures.taxonomy

    private fun run(
        old: Map<String, String>,
        new: Map<String, String>,
        transmitted: Boolean = true,
    ) = classify(old, new, transmitted, t)

    @Test
    fun `a field absent from both maps gives no change, no class, no outcome`() {
        val d = run(mapOf("lineAmount" to "10"), mapOf("lineAmount" to "10"))
        assertEquals(false, d.changed)
        assertNull(d.decidingClass)
        assertNull(d.outcome)
        assertTrue(d.fields.isEmpty() && d.trace.isEmpty())
    }

    @Test
    fun `a fixed non-monetary change after transmission resubmits under the same id`() {
        val d = run(mapOf("deliveryAddress" to "A"), mapOf("deliveryAddress" to "B"))
        assertEquals("NON_MONETARY", d.decidingClass)
        assertEquals(Outcome("RESUBMIT_SAME_ID"), d.outcome)
    }

    @Test
    fun `a category change is monetary when the rate differs and non-monetary when it does not`() {
        val rateChanged =
            run(mapOf("categoryCode" to "SR", "rate" to "9"), mapOf("categoryCode" to "ZR", "rate" to "0"))
        assertEquals("MONETARY", rateChanged.decidingClass)
        assertEquals(listOf("SUB-015"), rateChanged.outcome!!.raised)
        val rateSame = run(mapOf("categoryCode" to "ZR", "rate" to "0"), mapOf("categoryCode" to "ES33", "rate" to "0"))
        assertEquals("NON_MONETARY", rateSame.decidingClass)
    }

    @Test
    fun `the most restrictive class decides and the trace lists both fields and the deciding class`() {
        val d =
            run(
                mapOf("invoiceNote" to "a", "lineAmount" to "1"),
                mapOf("invoiceNote" to "b", "lineAmount" to "2"),
            )
        assertEquals("MONETARY", d.decidingClass)
        assertEquals(
            listOf(
                TraceEntry("invoiceNote", "a", "b", "NON_MONETARY"),
                TraceEntry("lineAmount", "1", "2", "MONETARY"),
                TraceEntry(null, null, null, "MONETARY"),
            ),
            d.trace,
        )
        assertEquals(listOf("invoiceNote", "lineAmount"), d.fields.map { it.field })
    }

    @Test
    fun `a period shift with a non-monetary change raises the period code`() {
        val d =
            run(
                mapOf("documentDate" to "2027-01-31", "invoiceNote" to "a"),
                mapOf("documentDate" to "2027-02-01", "invoiceNote" to "b"),
            )
        assertEquals("PERIOD_SHIFTING", d.decidingClass)
        assertEquals(listOf("PER-006"), d.outcome!!.raised)
    }

    @Test
    fun `a structural change raises its code before and after transmission`() {
        for (transmitted in listOf(true, false)) {
            val d = run(mapOf("documentTypeCode" to "380"), mapOf("documentTypeCode" to "381"), transmitted)
            assertEquals(listOf("ING-012"), d.outcome!!.raised, "transmitted=$transmitted")
        }
    }

    @Test
    fun `a monetary change before transmission takes the before outcome`() {
        val d = run(mapOf("lineAmount" to "1"), mapOf("lineAmount" to "2"), transmitted = false)
        assertEquals(Outcome("SUPERSEDE"), d.outcome)
    }

    @Test
    fun `a changed field without a classifier takes the unlisted class`() {
        val d = run(mapOf("other" to "a"), mapOf("other" to "b"))
        assertEquals("MONETARY", d.decidingClass)
        assertEquals("MONETARY", d.fields.single().classCode)
    }

    @Test
    fun `a field present on one side only is changed and an empty text is not absent`() {
        val added = run(emptyMap(), mapOf("deliveryAddress" to ""))
        assertEquals(ChangedField("deliveryAddress", null, "", "NON_MONETARY"), added.fields.single())
        val removed = run(mapOf("deliveryAddress" to "A"), emptyMap())
        assertEquals(ChangedField("deliveryAddress", "A", null, "NON_MONETARY"), removed.fields.single())
        assertEquals(false, run(mapOf("deliveryAddress" to ""), mapOf("deliveryAddress" to "")).changed)
    }

    @Test
    fun `a classifier returning an unknown code throws naming the field and the code`() {
        val bad = AmendmentFixtures.with(classifiers = mapOf("f" to FieldClassifier { _, _, _, _, _ -> "NOPE" }))
        val e =
            assertThrows(
                IllegalStateException::class.java,
            ) { classify(mapOf("f" to "a"), mapOf("f" to "b"), true, bad) }
        assertTrue(e.message!!.contains("'f'") && e.message!!.contains("'NOPE'"), e.message)
    }

    @Test
    fun `construction refusals`() {
        fun refused(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java, block)
        val c = t.classes
        refused {
            AmendmentFixtures.with(
                classes = emptyList(),
                after = emptyMap(),
                before = emptyMap(),
                classifiers = emptyMap(),
            )
        }
        refused { AmendmentFixtures.with(classes = c + "IDENTITY") }
        refused {
            AmendmentFixtures.with(
                classes = c + " ",
                after = t.afterTransmission + (" " to Outcome("X")),
                before =
                    t.beforeTransmission + (" " to Outcome("X")),
            )
        }
        refused { AmendmentFixtures.with(after = t.afterTransmission - "IDENTITY") }
        refused { AmendmentFixtures.with(before = t.beforeTransmission - "MONETARY") }
        refused { AmendmentFixtures.with(classifiers = mapOf("f" to FieldClassifier.fixed("NOPE"))) }
        refused { AmendmentFixtures.with(classifiers = mapOf(" " to FieldClassifier.fixed("MONETARY"))) }
        refused { AmendmentFixtures.with(unlisted = "NOPE") }
        refused { AmendmentFixtures.with(unlisted = null) }
        refused { Outcome(" ") }
    }

    @Test
    fun `the same inputs give the same result and the inputs are not modified`() {
        val old = mutableMapOf("invoiceNote" to "a", "lineAmount" to "1")
        val new = mutableMapOf("invoiceNote" to "b", "lineAmount" to "2")
        val before = old.toMap() to new.toMap()
        assertEquals(run(old, new), run(old, new))
        assertEquals(before, old to new)
    }
}
