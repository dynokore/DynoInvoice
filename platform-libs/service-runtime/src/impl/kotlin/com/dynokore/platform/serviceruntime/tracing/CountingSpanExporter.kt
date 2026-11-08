package com.dynokore.platform.serviceruntime.tracing

import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Wraps the span exporter: a failed export is counted in [FAILED_METER] and logged with [FAILED_CODE] only, never the
 * span content (the pattern of OtlpSecurityEventEmitter). Once [markClosing] is called (the application context is
 * closing), a failed export is still counted, and its per-failure ERROR log is replaced by one WARN line at [shutdown]
 * carrying the suppressed count: the final flush of spans ended during shutdown is expected collateral, but no failure
 * is silent (rate-limited, not suppressed; NIST SP 800-92, OWASP ASVS V7).
 */
class CountingSpanExporter(
    private val delegate: SpanExporter,
    private val meters: MeterRegistry?,
) : SpanExporter {
    @Volatile
    private var closing = false
    private val suppressed = AtomicLong()
    private val summarised = AtomicBoolean()

    fun markClosing() {
        closing = true
    }

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

    override fun shutdown(): CompletableResultCode {
        val result = delegate.shutdown()
        val count = suppressed.get()
        if (count > 0 &&
            summarised.compareAndSet(false, true)
        ) {
            log.warn("{} suppressed={} during shutdown", FAILED_CODE, count)
        }
        return result
    }

    private fun failed() {
        meters?.counter(FAILED_METER)?.increment()
        if (closing) suppressed.incrementAndGet() else log.error("{}", FAILED_CODE)
    }

    companion object {
        const val FAILED_METER = "gst_trace_export_failed_total"
        const val FAILED_CODE = "TRACE_EXPORT_FAILED"
        private val log = LoggerFactory.getLogger(CountingSpanExporter::class.java)
    }
}
