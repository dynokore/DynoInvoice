package com.dynokore.platform.serviceruntime.binding

/**
 * Where this service exports metrics and traces (SDS §12.1, STK-33, STK-34,
 * STK-36): the operator's central collector in SaaS, the bundled per-customer
 * OpenTelemetry Collector shipped in the same umbrella chart in private. Both
 * forms speak OTLP, so one exporter configuration serves either — only the
 * [otlpEndpoint] differs, selected by configuration (ARC-11), not by a second
 * implementation.
 *
 * Declared here as one of the closed set of SDS §12.1 bindings (FR-H.1); no
 * implementation ships with this task (done-when, INV-T-2871 / T27) — one lands
 * with the task that wires Micrometer's OTLP registry to it.
 */
fun interface TelemetryExport : Binding {
    /** The OTLP endpoint metrics and traces export to. */
    fun otlpEndpoint(): String
}
