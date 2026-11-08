package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.consent.ConsentAuthorityRecord
import com.dynokore.gst.access.application.consent.ConsentChangePublisher
import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.RoleGrantChange
import com.dynokore.gst.access.domain.consent.ConsentStatus
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.format.DateTimeFormatter

/**
 * TPL-05: maps a role-grant change to the platform event AuthorityChanged (INV-API-3325, INV-Q-8972 A) and appends it
 * through outbox-and-queue, in the command transaction the caller holds (CS-17). One event per change; the payload is
 * the `authority-changed-payload` schema of service-runtime in platform-contracts (a test per channel validates it
 * against that schema). The envelope is tenant-wide and keyed on the principal (INV-Q-9229 A). The emission guard of
 * outbox-and-queue refuses the type unless the event catalogue lists access-svc as its producer.
 */
class AuthorityChangedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
) : AuthorityChangePublisher,
    ConsentChangePublisher {
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
            outbox.append(
                listOf(OutboundEvent(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, payload(change).toString())),
                context,
            )
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
                OutboundEvent(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, consentPayload(record).toString()),
            ),
            context,
        )
    }

    internal fun consentPayload(record: ConsentAuthorityRecord): ObjectNode {
        val consent = record.consent
        val from =
            consent.terms.validFrom
                .atStartOfDay()
                .atOffset(java.time.ZoneOffset.UTC)
        val root = mapper.createObjectNode()
        root.put("changeKind", "CONSENT")
        root.put("principalRef", consent.agent.value)
        root.put("authorityVersion", record.authorityVersion)
        root.putObject("validity").put("from", ISO.format(from))
        val node = root.putObject("consentRecord")
        node.put("clientEntityRef", consent.client.value)
        node.put("state", if (consent.status == ConsentStatus.LIVE) "LIVE" else "WITHDRAWN")
        val items = node.putArray("scopeItems")
        ScopeItem.entries.filter { it in consent.items }.forEach { items.add(it.wire) }
        node.put("validFrom", ISO.format(from))
        return root
    }

    internal fun payload(change: RoleGrantChange): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("changeKind", "ROLE_GRANT")
        root.put("principalRef", change.identity.value)
        root.put("authorityVersion", change.authorityVersion)
        val node = root.putObject("roleGrantRecord")
        node.put("grantRef", change.grantRef.value)
        node.put("role", change.role.wire)
        node.put("state", change.state.name)
        node.put("scopeLevel", change.scopeLevel.name)
        node.put("scopeRef", change.scopeRef)
        node.put("validFrom", ISO.format(change.validFrom.atOffset(java.time.ZoneOffset.UTC)))
        change.validUntil?.let { node.put("validUntil", ISO.format(it.atOffset(java.time.ZoneOffset.UTC))) }
        change.periodFrom?.let { node.put("periodFrom", it.toString()) }
        change.periodTo?.let { node.put("periodTo", it.toString()) }
        return root
    }

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it). */
        const val CHANNEL = "platformAuthorityChanged"

        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
