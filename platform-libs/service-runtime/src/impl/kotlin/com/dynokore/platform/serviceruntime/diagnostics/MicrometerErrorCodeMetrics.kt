package com.dynokore.platform.serviceruntime.diagnostics

import io.micrometer.core.instrument.MeterRegistry

/**
 * Exports the per-code error counter through the metrics stack (FR-O.8, STK-33)
 * and feeds [recorder] for the uniform diagnostic endpoint's "recent error
 * codes" (FR-AE.82). One Micrometer counter, `gst_errors_total`, tagged by
 * `code`, `entity` and `period` — proved against INV-AC-2172: 25 raises of
 * `RUL-001` for one entity and period increment that tag combination's counter
 * by exactly 25 (`MicrometerErrorCodeMetricsTest`).
 */
class MicrometerErrorCodeMetrics(
    private val meterRegistry: MeterRegistry,
    private val recorder: RecentErrorCodesRecorder,
) : ErrorCodeMetrics {
    override fun increment(
        code: String,
        entity: String,
        period: String,
    ) {
        meterRegistry
            .counter(METER_NAME, "code", code, "entity", entity, "period", period)
            .increment()
        recorder.record(code)
    }

    companion object {
        const val METER_NAME = "gst_errors_total"
    }
}
