package com.dynokore.gst.rules.domain.reprocessing

import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReprocessingSelectionTest {
    private val stored = DocumentOutcome("SR")
    private val other = DocumentOutcome("ZR")
    private val uuid = "0b9c3a52-6f1e-4c1a-9a55-2f3c1d7e8a90"

    private fun item(acknowledged: Boolean) = PopulationItem(uuid, "2026.3", acknowledged, stored)

    @Test
    fun `INV-AC-24013 an acknowledged and reclassified item is listed with its Doc UUID and version unchanged`() {
        ReprocessingSelection.select(item(true), other) shouldBe ResubmissionEntry(uuid, "2026.3")
    }

    @Test
    fun `INV-AC-24013 a non-acknowledged item is not listed even when reclassified`() {
        ReprocessingSelection.select(item(false), other) shouldBe null
    }

    @Test
    fun `INV-AC-24013 an unchanged item is not listed even when acknowledged`() {
        ReprocessingSelection.select(item(true), stored) shouldBe null
    }

    @Test
    fun `INV-AC-24013 a verdict that turns into a refusal counts as changed`() {
        ReprocessingSelection.changed(stored, DocumentOutcome("E100", "BLOCK")) shouldBe true
        ReprocessingSelection.changed(stored, stored.copy()) shouldBe false
    }
}
