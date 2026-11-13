package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.adapters.persistence.AuthorityHeadStore
import com.dynokore.gst.access.application.consent.ConsentAuthorityRecord
import com.dynokore.gst.access.application.consent.ConsentChangePublisher
import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.RoleGrantChange
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * TPL-05: maps a role-grant change to the platform event AuthorityChanged (INV-API-3325, INV-Q-8972 A) and appends it
 * through outbox-and-queue, in the command transaction the caller holds (CS-17). One event per change; the payload is
 * the `authority-changed-payload` schema of service-runtime in platform-contracts (a test per channel validates it
 * against that schema). The envelope is tenant-wide and keyed on the principal (INV-Q-9229 A). The emission guard of
 * outbox-and-queue refuses the type unless the event catalogue lists access-svc as its producer.
 */
class AuthorityChangedOutbox(
    private val outbox: OutboxWriter,
    mapper: ObjectMapper,
    private val head: AuthorityHeadStore,
) : AuthorityChangePublisher,
    ConsentChangePublisher {
    private val payloads = AuthorityChangedPayloads(mapper)

    override fun publish(changes: List<RoleGrantChange>) {
        changes.forEach { change ->
            // INV-Q-9229 A: a platform event keyed on the principal; the grant's own entity or period travels in the
            // record (scopeRef, span) and not in the envelope
            val context =
                EventContexts.forAggregate(
                    TenantId.of(change.tenant.value),
                    EntityRef.TENANT_WIDE,
                    change.identity.value,
                )
            val event = payloads.roleGrant(change).toString()
            outbox.append(listOf(OutboundEvent(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, event)), context)
            head.advance(change.authorityVersion)
        }
    }

    /** One event with a `consentRecord`: the whole state of the consent after the change . */
    override fun publish(record: ConsentAuthorityRecord) {
        val context =
            EventContexts.forAggregate(
                TenantId.of(record.tenant.value),
                EntityRef.TENANT_WIDE,
                record.consent.agent.value,
            )
        outbox.append(
            listOf(
                OutboundEvent(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, payloads.consent(record).toString()),
            ),
            context,
        )
        head.advance(record.authorityVersion)
    }

    internal fun consentPayload(record: ConsentAuthorityRecord): ObjectNode = payloads.consent(record)

    internal fun payload(change: RoleGrantChange): ObjectNode = payloads.roleGrant(change)

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it). */
        const val CHANNEL = "platformAuthorityChanged"
    }
}
