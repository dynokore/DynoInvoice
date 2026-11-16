package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.offboarding.ExportOrder
import com.dynokore.gst.tenancy.application.offboarding.TenantExport
import com.dynokore.gst.tenancy.application.offboarding.UnavailableInput
import com.dynokore.gst.tenancy.domain.ExportManifest
import com.dynokore.gst.tenancy.domain.ExportState
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * The production [TenantExport] (INV-REQ-877, INV-AC-22960): the export is asynchronous, so the port appends the event
 * [TYPE] through the service's own outbox in the transaction that stores the offboarding record (transactional
 * outbox, at-least-once delivery) and answers a manifest in state REQUESTED with no contents. A failed append is
 * [UnavailableInput] `tenant-export`: the request is refused and nothing is stored. The result arrives as
 * `OffboardingExportFinished` ([OffboardingExportFinishedHandler]).
 */
class OffboardingExportRequestedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    private val tenants: TenantContext,
    private val clock: Clock = Clock.systemUTC(),
) : TenantExport {
    override fun produce(order: ExportOrder): ExportManifest {
        val exportRef = exportRefOf(order.offboardingRef)
        val payload = mapper.createObjectNode()
        payload.put("tenantRef", order.tenantRef)
        payload.put("offboardingRef", order.offboardingRef)
        payload.put("exportRef", exportRef)
        payload.put("scope", order.scope.name)
        if (order.scope == OffboardingScope.ENTITY) order.entityRef?.let { payload.put("entityRef", it) }
        payload.put("requestedAt", clock.instant().toString())
        // an entity export is written with its registered entity, a tenant export with the real tenant and the
        // tenant-wide entity (no entityref on the wire); the tenant ref is never written as an entity
        val entity =
            if (order.scope == OffboardingScope.ENTITY) EntityRef(checkNotNull(order.entityRef)) else EntityRef.TENANT_WIDE
        try {
            outbox.append(
                listOf(OutboundEvent(TYPE, payload.toString())),
                EventContexts.forAggregate(tenants.current(), entity, order.offboardingRef),
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            throw UnavailableInput(INPUT).apply { initCause(e) }
        }
        return ExportManifest(exportRef, ExportState.REQUESTED, emptyList())
    }

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it by its message name). */
        const val CHANNEL = "OffboardingExportRequested"
        const val TYPE = "com.dynokore.gst.tenancy.OffboardingExportRequested.v1"
        const val INPUT = "tenant-export"

        fun exportRefOf(offboardingRef: String): String = "EXP-$offboardingRef"
    }
}
