package com.dynokore.platform.serviceruntime.tracing

import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.slf4j.LoggerFactory

/**
 * Wraps the span exporter: a failed export is counted in [FAILED_METER] and logged with [FAILED_CODE] only, never the
 * span content (the pattern of OtlpSecurityEventEmitter).
 */
class CountingSpanExporter(
    private val delegate: SpanExporter,
    private val meters: MeterRegistry?,
) : SpanExporter {
    override fun export(spans: MutableCollection<SpanData>): CompletableResultCode {
        val result =
            try {
                delegate.export(spans)
            } catch (expected: Exception) {
                failed()
                return CompletableResultCode.ofFailure()
            }
        result.whenComplete { if (!result.isSuccess) failed() }
        return result
    }

    override fun flush(): CompletableResultCode = delegate.flush()

    override fun shutdown(): CompletableResultCode = delegate.shutdown()

    private fun failed() {
        meters?.counter(FAILED_METER)?.increment()
        log.error("{}", FAILED_CODE)
    }

    companion object {
        const val FAILED_METER = "gst_trace_export_failed_total"
        const val FAILED_CODE = "TRACE_EXPORT_FAILED"
        private val log = LoggerFactory.getLogger(CountingSpanExporter::class.java)
    }
}
