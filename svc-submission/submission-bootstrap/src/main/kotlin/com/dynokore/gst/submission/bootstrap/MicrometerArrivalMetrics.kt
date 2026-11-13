package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.arrival.ArrivalMetrics
import com.dynokore.gst.submission.domain.routing.UnresolvedInput
import io.micrometer.core.instrument.MeterRegistry

/**
 * [ArrivalMetrics] over Micrometer (INV-AC-21095). The tags are bounded: the unresolved-input counter has one tag
 * (the enum name), the unknown-register-entry counter none; no entity, period or Doc UUID is ever a tag.
 */
class MicrometerArrivalMetrics(
    private val registry: MeterRegistry,
) : ArrivalMetrics {
    override fun unresolvedInput(input: UnresolvedInput) {
        registry.counter(UNRESOLVED_INPUT, "input", input.name).increment()
    }

    override fun unknownRegisterEntry() {
        registry.counter(UNKNOWN_REGISTER_ENTRY).increment()
    }

    companion object {
        const val UNRESOLVED_INPUT = "submission_arrival_unresolved_input_total"
        const val UNKNOWN_REGISTER_ENTRY = "submission_arrival_unknown_register_entry_total"
    }
}
