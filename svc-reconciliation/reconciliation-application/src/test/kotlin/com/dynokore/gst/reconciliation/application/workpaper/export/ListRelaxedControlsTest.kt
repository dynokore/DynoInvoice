package com.dynokore.gst.reconciliation.application.workpaper.export

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

class ListRelaxedControlsTest {
    private class FakeView(
        var current: Boolean = true,
        var controls: List<RelaxableControl> =
            listOf(
                RelaxableControl("VP-A", "Control A"),
                RelaxableControl("VP-B", "Control B"),
                RelaxableControl("VP-C", "Control C"),
            ),
        var refs: Map<String, String?> = mapOf("VP-C" to "ACK-1", "VP-A" to "ACK-2", "VP-B" to null),
    ) : PolicyView {
        val asked = mutableListOf<List<Any>>()

        override fun current() = current

        override fun controls() = controls

        override fun relaxationAckRef(
            tenant: String,
            entityRef: String,
            code: String,
            on: LocalDate,
        ): String? {
            asked += listOf(tenant, entityRef, code, on)
            return refs[code]
        }
    }

    private val day = LocalDate.of(2026, 4, 30)

    @Test
    fun `INV-AC-24393 lists the controls with a reference sorted by code`() {
        val view = FakeView()
        ListRelaxedControls(view).relaxed("T1", "E1", day) shouldBe
            listOf(ExportRelaxedControl("VP-A", "Control A"), ExportRelaxedControl("VP-C", "Control C"))
    }

    @Test
    fun `INV-AC-24393 a view that is not current throws and no reference is read`() {
        val view = FakeView(current = false)
        assertThrows<RelaxedControlsUnavailable> { ListRelaxedControls(view).relaxed("T1", "E1", day) }
        view.asked shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24393 a blank reference throws and is never treated as not relaxed`() {
        val view = FakeView(refs = mapOf("VP-A" to "ACK-2", "VP-B" to "  ", "VP-C" to "ACK-1"))
        assertThrows<RelaxedControlsUnavailable> { ListRelaxedControls(view).relaxed("T1", "E1", day) }
    }

    @Test
    fun `INV-AC-24393 a view with no control returns an empty list`() {
        val view = FakeView(controls = emptyList())
        ListRelaxedControls(view).relaxed("T1", "E1", day) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24393 another entity returns an empty list and the view is asked with tenant and entity`() {
        val view = FakeView(refs = emptyMap())
        ListRelaxedControls(view).relaxed("T1", "E2", day) shouldBe emptyList()
        view.asked shouldBe
            listOf(
                listOf("T1", "E2", "VP-A", day),
                listOf("T1", "E2", "VP-B", day),
                listOf("T1", "E2", "VP-C", day),
            )
    }
}
