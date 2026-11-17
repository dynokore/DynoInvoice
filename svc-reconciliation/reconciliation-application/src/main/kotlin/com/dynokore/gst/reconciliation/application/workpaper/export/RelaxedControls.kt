package com.dynokore.gst.reconciliation.application.workpaper.export

import java.time.LocalDate

data class RelaxableControl(
    val code: String,
    val description: String,
)

interface PolicyView {
    fun current(): Boolean

    fun controls(): List<RelaxableControl>

    fun relaxationAckRef(
        tenant: String,
        entityRef: String,
        code: String,
        on: LocalDate,
    ): String?
}

class RelaxedControlsUnavailable(
    message: String,
) : RuntimeException(message)

fun interface RelaxedControlsSource {
    fun relaxed(
        tenant: String,
        entityRef: String,
        on: LocalDate,
    ): List<ExportRelaxedControl>
}

class ListRelaxedControls(
    private val view: PolicyView,
) : RelaxedControlsSource {
    override fun relaxed(
        tenant: String,
        entityRef: String,
        on: LocalDate,
    ): List<ExportRelaxedControl> {
        if (!view.current()) {
            throw RelaxedControlsUnavailable("the policy view is not current; the relaxed controls cannot be listed")
        }
        val relaxed = mutableListOf<ExportRelaxedControl>()
        for (control in view.controls()) {
            val ref = view.relaxationAckRef(tenant, entityRef, control.code, on) ?: continue
            if (ref.isBlank()) {
                throw RelaxedControlsUnavailable(
                    "blank relaxation acknowledgement reference for control ${control.code}",
                )
            }
            relaxed += ExportRelaxedControl(control.code, control.description)
        }
        return relaxed.sortedBy { it.code }
    }
}
