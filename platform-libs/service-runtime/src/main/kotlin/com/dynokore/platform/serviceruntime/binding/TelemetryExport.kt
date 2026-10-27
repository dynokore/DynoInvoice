package com.dynokore.platform.serviceruntime.binding

/**
 * Where this service exports metrics and traces (SDS §12.1, STK-33, STK-34,
 * STK-36): the operator's central collector in SaaS, the bundled per-customer
 * OpenTelemetry Collector shipped in the same umbrella chart in private. Both
 * forms speak OTLP, so one exporter configuration serves either — only the
 * [otlpEndpoint] differs, selected by configuration (ARC-11), not by a second
 * implementation.
 *
 * Declared here as one of the closed set of SDS §12.1 bindings (FR-H.1). The one implementation,
 * `ConfiguredTelemetryExport`, is created by service-runtime's auto-configuration when
 * `gst.bindings.telemetry-export` (`bundled-otel` or `managed-observability`) and
 * `gst.bindings.telemetry-export-otlp-endpoint` are set and the service supplies no bean of its own
 * (INV-T-11067, INV-Q-11058).
 */
fun interface TelemetryExport : Binding {
    /** The OTLP endpoint metrics and traces export to. */
    fun otlpEndpoint(): String
}
