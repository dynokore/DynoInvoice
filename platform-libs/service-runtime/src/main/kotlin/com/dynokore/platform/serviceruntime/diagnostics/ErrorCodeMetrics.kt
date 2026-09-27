package com.dynokore.platform.serviceruntime.diagnostics

/**
 * Counts a raised catalogue error code as a tracked quality metric (FR-O.8,
 * INV-AC-2172): "raising RUL-001 increments a per-code counter visible in the
 * metrics ... the RUL-001 counter for that entity and period rises by 25 and the
 * daily series shows the increase". Framework-free port (ARC-16); the shipped
 * implementation is Micrometer-backed
 * ([MicrometerErrorCodeMetrics][com.dynokore.platform.serviceruntime.diagnostics.MicrometerErrorCodeMetrics]),
 * exported through the metrics stack (STK-33) — the daily series is Prometheus's
 * own job, scraping this counter repeatedly, not built here.
 *
 * [entity] and [period] are identifiers, safe to log or tag with (CS-34); never
 * the invoice content that caused the error.
 */
fun interface ErrorCodeMetrics {
    fun increment(
        code: String,
        entity: String,
        period: String,
    )
}
