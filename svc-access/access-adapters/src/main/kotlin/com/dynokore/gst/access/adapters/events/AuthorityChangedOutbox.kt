package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.consent.ConsentAuthorityRecord
import com.dynokore.gst.access.application.consent.ConsentChangePublisher
import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.AuthorityGrantRecord
import com.dynokore.gst.access.domain.consent.ConsentStatus
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.format.DateTimeFormatter

/**
 * TPL-05: maps a grant record to the contract event AuthorityChanged (INV-API-3325) and appends it through
 * outbox-and-queue, in the command transaction the caller holds (CS-17). One event per record; the payload is the
 * `authority-changed-payload` schema of platform-contracts (a test per channel validates it against that schema). The
 * emission guard of outbox-and-queue refuses the type unless the event catalogue lists access-svc as its producer.
 */
class AuthorityChangedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
) : AuthorityChangePublisher,
    ConsentChangePublisher {
    override fun publish(records: List<AuthorityGrantRecord>) {
        records.forEach { record ->
            val context =
                EventContexts.forAggregate(
                    TenantId.of(record.tenant.value),
                    EntityRef(record.entity.value),
                    record.identity.value,
                )
            outbox.append(
                listOf(OutboundEvent(ProductEventTypes.AUTHORITY_CHANGED, payload(record).toString())),
                context,
            )
        }
    }

    /** One event with a `consentRecord`: the whole state of the consent after the change . */
    override fun publish(record: ConsentAuthorityRecord) {
        val context =
            EventContexts.forAggregate(
                TenantId.of(record.tenant.value),
                EntityRef(record.consent.client.value),
                record.consent.agent.value,
            )
        outbox.append(
            listOf(OutboundEvent(ProductEventTypes.AUTHORITY_CHANGED, consentPayload(record).toString())),
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

    internal fun payload(record: AuthorityGrantRecord): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("changeKind", "GRANT")
        root.put("principalRef", record.identity.value)
        root.put("authorityVersion", record.authorityVersion)
        val validity = root.putObject("validity")
        validity.put("from", ISO.format(record.windowFrom.atOffset(java.time.ZoneOffset.UTC)))
        record.windowTo?.let { validity.put("to", ISO.format(it.atOffset(java.time.ZoneOffset.UTC))) }
        val grantRecord = root.putObject("grantRecord")
        grantRecord.put("entityRef", record.entity.value)
        val grants = grantRecord.putArray("grants")
        record.actions.forEach { action ->
            val node = grants.addObject()
            node.put("action", action.action)
            node.put("scopeLevel", action.scopeLevel.name)
            node.put("scopeRef", action.scopeRef)
            action.periodFrom?.let { node.put("periodFrom", it.toString()) }
            action.periodTo?.let { node.put("periodTo", it.toString()) }
            node.put("validFrom", ISO.format(action.validFrom.atOffset(java.time.ZoneOffset.UTC)))
            action.validUntil?.let { node.put("validUntil", ISO.format(it.atOffset(java.time.ZoneOffset.UTC))) }
        }
        return root
    }

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it). */
        const val CHANNEL = "accessAuthorityChanged"

        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
