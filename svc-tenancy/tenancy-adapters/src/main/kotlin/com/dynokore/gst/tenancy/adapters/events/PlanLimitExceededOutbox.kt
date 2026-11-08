package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.metering.LimitKind
import com.dynokore.gst.tenancy.application.metering.PlanLimitPublisher
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.ObjectMapper

/**
 * TPL-05: PlanLimitExceeded (INV-REQ-879) as a domain event written with the real tenant of the comparison and
 * `EntityRef.TENANT_WIDE` in the transaction the caller holds (INV-Q-15474 A, the closed exact-type set of
 * outbox-and-queue). The payload is `tenantRef`, `limitKind`, `limit`, `observed` and, for DOCUMENTS_PER_MONTH only,
 * `period`; never `entityRef`. Publishing never refuses or delays the submission that caused it.
 */
class PlanLimitExceededOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    private val tenants: TenantContext,
    private val tenantRef: (TenantId) -> String,
) : PlanLimitPublisher {
    override fun exceeded(
        kind: LimitKind,
        period: String?,
        limit: Int,
        observed: Int,
    ) {
        val tenant = tenants.current()
        outbox.append(
            listOf(
                OutboundEvent(
                    TenancyEventCatalogue.PLAN_LIMIT_EXCEEDED_TYPE,
                    payload(tenant, kind, period, limit, observed),
                ),
            ),
            EventContexts.forAggregate(tenant, EntityRef.TENANT_WIDE, "tenant"),
        )
    }

    internal fun payload(
        tenant: TenantId,
        kind: LimitKind,
        period: String?,
        limit: Int,
        observed: Int,
    ): String =
        mapper
            .createObjectNode()
            .apply {
                put("tenantRef", tenantRef(tenant))
                put("limitKind", kind.name)
                put("limit", limit)
                put("observed", observed)
                if (kind == LimitKind.DOCUMENTS_PER_MONTH) put("period", checkNotNull(period) { "period is missing" })
            }.toString()
}
