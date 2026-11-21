package com.dynokore.gst.submission.application.readiness

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import java.util.Locale
import java.util.UUID

/** The `ReadinessChanged` event: its type and its data (INV-REQ-570, INV-AC-26601). */
object ReadinessChangedPublication {
    const val EVENT_TYPE = "com.dynokore.gst.submission.ReadinessChanged.v1"

    /** The data: `entityRef`, `period`, `verdict`, `countsByState` (in lifecycle order) and `blockedCount`. */
    fun data(facts: ReadinessChangedFacts): String =
        buildString {
            val snapshot = facts.snapshot
            append("{\"entityRef\":").append(quote(facts.entityRef))
            append(",\"period\":").append(quote(facts.periodRef))
            append(",\"verdict\":").append(quote(snapshot.verdict.name))
            append(",\"countsByState\":{")
            LifecycleState.entries
                .mapNotNull { state -> snapshot.countsByState[state]?.let { state to it } }
                .joinTo(this, ",") { (state, count) -> "${quote(state.name)}:$count" }
            append("},\"blockedCount\":").append(snapshot.blockedCount)
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

/** Appends one `ReadinessChanged` event in the caller's open transaction; it opens none. */
class OutboxReadinessSignals(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
) : ReadinessSignals {
    override fun stage(facts: ReadinessChangedFacts) {
        outbox.append(
            listOf(OutboundEvent(ReadinessChangedPublication.EVENT_TYPE, ReadinessChangedPublication.data(facts))),
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(facts.entityRef),
                docUuid = null,
                transactionDate = null,
                aggregateKey = "${facts.entityRef}/${facts.periodRef}",
                aggregateVersion = null,
                correlation = CorrelationId(UUID.randomUUID().toString()),
                causation = CausationId("readiness-check:${facts.checkId}"),
            ),
        )
    }
}
