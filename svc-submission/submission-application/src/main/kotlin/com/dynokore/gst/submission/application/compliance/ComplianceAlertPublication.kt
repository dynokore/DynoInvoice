package com.dynokore.gst.submission.application.compliance

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import java.util.Locale
import java.util.UUID

/**
 * Writes one `ComplianceAlertValuesMeasured` outbox event per alert value (INV-REQ-1069, INV-AC-12837; owner answer
 * INV-Q-12767 A). Whether a value is written is decided by the [AlertEmissionLedger] (INV-Q-16751 A): the writer stamps
 * its own UUIDv7 event id, so the id carries no business key.
 */
object ComplianceAlertPublication {
    const val EVENT_TYPE = "com.dynokore.gst.submission.ComplianceAlertValuesMeasured.v1"

    /**
     * For each value claims (tenant, entity, period, measuredOn) in [ledger] and appends the event only when the claim
     * answers true. It opens no transaction: the caller runs it inside the open tenant transaction of [tenant], so the
     * claims and the events of one tenant commit or roll back together.
     *
     * @return the number of events written.
     */
    fun publish(
        values: List<AlertValue>,
        tenant: TenantId,
        ledger: AlertEmissionLedger,
        outbox: OutboxWriter,
    ): Int =
        values.count { value ->
            val claimed = ledger.claim(tenant, value.entityRef, value.periodRef, value.measuredOn)
            if (claimed) {
                outbox.append(listOf(OutboundEvent(EVENT_TYPE, data(value))), context(tenant, value))
            }
            claimed
        }

    private fun context(
        tenant: TenantId,
        value: AlertValue,
    ) = EventContext(
        tenant = tenant,
        entity = EntityRef(value.entityRef),
        docUuid = null,
        transactionDate = value.measuredOn,
        aggregateKey = "${value.entityRef}/${value.periodRef}",
        aggregateVersion = null,
        correlation = CorrelationId(UUID.randomUUID().toString()),
        causation = CausationId("compliance-evaluation:${value.measuredOn}"),
    )

    /** The payload: the five properties and `daysToNearestDeadline` only when it is not null. */
    fun data(value: AlertValue): String =
        buildString {
            append("{\"entityRef\":").append(quote(value.entityRef))
            append(",\"periodRef\":").append(quote(value.periodRef))
            append(",\"measuredOn\":").append(quote(value.measuredOn.toString()))
            append(",\"documentsWithoutSubmission\":").append(value.documentsWithoutSubmission)
            append(",\"documentsPastDue\":").append(value.documentsPastDue)
            value.daysToNearestDeadline?.let { append(",\"daysToNearestDeadline\":").append(it) }
            append('}')
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
