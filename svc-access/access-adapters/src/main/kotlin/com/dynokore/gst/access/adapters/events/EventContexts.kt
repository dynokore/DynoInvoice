package com.dynokore.gst.access.adapters.events

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantId
import java.util.UUID

/**
 * The outbox context of an event raised in the current request: correlation and causation from the request's MDC
 * (§11.6).
 */
object EventContexts {
    fun forAggregate(
        tenant: TenantId,
        entity: EntityRef,
        aggregateKey: String,
    ): EventContext {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        return EventContext(
            tenant = tenant,
            entity = entity,
            docUuid = null,
            transactionDate = null,
            aggregateKey = aggregateKey,
            aggregateVersion = null,
            correlation = CorrelationId(correlation),
            causation = CausationId(causation),
        )
    }
}
