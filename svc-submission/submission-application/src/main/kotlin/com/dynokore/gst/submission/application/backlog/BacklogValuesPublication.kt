package com.dynokore.gst.submission.application.backlog

import com.dynokore.gst.submission.domain.metrics.BacklogMetrics
import com.dynokore.gst.submission.domain.metrics.BacklogValues
import com.dynokore.gst.submission.domain.metrics.BacklogVersion
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * Writes one `BacklogValuesMeasured` outbox event per entity and period (INV-REQ-1370, INV-AC-20581). No ledger: the
 * evaluator deduplicates. No meter is registered and no value is a metric label.
 */
object BacklogValuesPublication {
    const val EVENT_TYPE = "com.dynokore.gst.submission.BacklogValuesMeasured.v1"
    private const val RATE_SCALE = 6

    /**
     * Computes the values with [BacklogMetrics.compute] and appends one event each. It opens no transaction: the
     * caller runs it inside the open tenant transaction of [tenant].
     *
     * @return the number of events written.
     */
    fun publish(
        versions: List<BacklogVersion>,
        now: Instant,
        today: LocalDate,
        tenant: TenantId,
        outbox: OutboxWriter,
    ): Int {
        val values = BacklogMetrics.compute(versions, now, today)
        values.forEach { value ->
            outbox.append(listOf(OutboundEvent(EVENT_TYPE, data(value, now))), context(tenant, value, now, today))
        }
        return values.size
    }

    private fun context(
        tenant: TenantId,
        value: BacklogValues,
        now: Instant,
        today: LocalDate,
    ) = EventContext(
        tenant = tenant,
        entity = EntityRef(value.entityRef),
        docUuid = null,
        transactionDate = today,
        aggregateKey = "${value.entityRef}/${value.periodRef}",
        aggregateVersion = null,
        correlation = CorrelationId(UUID.randomUUID().toString()),
        causation = CausationId("backlog-evaluation:$now"),
    )

    /** The payload; `oldestUnsentAgeSeconds` only when the age is not null. */
    fun data(
        value: BacklogValues,
        measuredAt: Instant,
    ): String =
        buildString {
            append("{\"entityRef\":").append(quote(value.entityRef))
            append(",\"periodRef\":").append(quote(value.periodRef))
            append(",\"queueDepth\":").append(value.queueDepth)
            value.oldestUnsentAge?.let { append(",\"oldestUnsentAgeSeconds\":").append(it.seconds) }
            append(",\"failureRate\":").append(rate(value.failureRate.numerator, value.failureRate.denominator))
            append(",\"unacknowledgedCount\":").append(value.unacknowledgedCount)
            append(",\"measuredAt\":").append(quote(measuredAt.toString()))
            append('}')
        }

    /** The decimal of the ratio (0 when nothing completed), at most 6 fraction digits, no exponent. */
    private fun rate(
        numerator: Int,
        denominator: Int,
    ): String =
        if (denominator == 0) {
            "0"
        } else {
            BigDecimal(numerator)
                .divide(BigDecimal(denominator), RATE_SCALE, RoundingMode.HALF_EVEN)
                .stripTrailingZeros()
                .toPlainString()
        }

    private fun quote(text: String): String =
        buildString {
            append('"')
            text.forEach { c ->
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
}
