package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.installation.DepCode
import com.dynokore.gst.tenancy.application.installation.InstallationStatusPublisher
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.ObjectMapper

/**
 * TPL-05: a raise or a clearing of DEP-004 or DEP-005 as the platform event InstallationStatusChanged (coordinator
 * ruling INV-Q-14289 A), written under the platform tenant with the platform entity in the transaction the caller holds
 * (bound to the platform tenant, ARC-21). The payload is `depCode`, `status`, `cause` and, when the property
 * `gst.installation.ref` holds a reference, `installationRef`; never `entityRef`.
 */
class InstallationStatusChangedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    private val installationRef: () -> String?,
) : InstallationStatusPublisher {
    override fun changed(
        depCode: DepCode,
        raised: Boolean,
        cause: String,
    ) {
        outbox.append(
            listOf(OutboundEvent(TYPE, payload(depCode, raised, cause).toString())),
            EventContexts.forAggregate(TenantId.PLATFORM, EntityRef.PLATFORM, "installation"),
        )
    }

    internal fun payload(
        depCode: DepCode,
        raised: Boolean,
        cause: String,
    ) = mapper.createObjectNode().apply {
        put("depCode", depCode.code)
        put("status", if (raised) "RAISED" else "CLEARED")
        put("cause", cause)
        installationRef()?.let { put("installationRef", it) }
    }

    companion object {
        const val TYPE = "com.dynokore.gst.platform.InstallationStatusChanged.v1"
    }
}
