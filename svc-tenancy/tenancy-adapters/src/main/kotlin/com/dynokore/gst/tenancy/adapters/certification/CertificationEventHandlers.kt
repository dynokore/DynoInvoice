package com.dynokore.gst.tenancy.adapters.certification

import com.dynokore.gst.tenancy.application.certification.CertifiedEntityDirectory
import com.dynokore.gst.tenancy.application.certification.MarkCertificationStale
import com.dynokore.gst.tenancy.application.certification.MarkOutcome
import com.dynokore.gst.tenancy.application.election.ISSUANCE_MODE_ATTRIBUTE
import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.domain.StaleTrigger
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** The inbox subscriptions this service adds for the re-certification triggers (INV-Q-16401 A), by exact name. */
object ConsumedCertificationEvents {
    const val RULE_PACK_ACTIVATED_CONSUMER = "tenancy-svc-certification-rule-pack-activated"
    const val RULE_PACK_ACTIVATED = "com.dynokore.gst.rules.RulePackActivated.v1"
    const val RULE_PACK_ACTIVATED_TOPIC = "gst.rules.rule-pack-activated.v1"
    const val IDENTITY_TRIPLE_CHANGED_CONSUMER = "tenancy-svc-certification-identity-triple-changed"
    const val IDENTITY_TRIPLE_CHANGED = "com.dynokore.gst.masterdata.IdentityTripleChanged.v1"
    const val IDENTITY_TRIPLE_CHANGED_TOPIC = "gst.masterdata.identity-triple-changed.v1"

    /** The topics this task adds, compared as a set by equality. */
    val TOPICS: Set<String> = setOf(RULE_PACK_ACTIVATED_TOPIC, IDENTITY_TRIPLE_CHANGED_TOPIC)
}

private fun parse(
    mapper: ObjectMapper,
    text: String,
): JsonNode =
    try {
        mapper.readTree(text)
    } catch (e: JacksonException) {
        throw ContractViolation("the payload is not JSON", e)
    }

private fun JsonNode.requiredText(name: String): String =
    this[name]?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
        ?: throw ContractViolation("payload member $name is required")

/**
 * RulePackActivated marks every certified entity of every tenant stale with NEW_PACK (INV-Q-16401 A: SCHEME_HANDLER is
 * marked by the same event, so the one mark stands for both). The event carries no pack kind, so a pack of any kind
 * marks alike. Each entity is marked in a transaction bound to its own tenant (ARC-21); an entity already stale or
 * without a PASSED record is left as it is.
 */
class RulePackActivatedCertificationHandler(
    private val directory: CertifiedEntityDirectory,
    private val tenants: TenantContext,
    private val tx: TransactionRunner,
    private val mark: MarkCertificationStale,
    private val mapper: ObjectMapper,
    private val actor: String,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event.type == ConsumedCertificationEvents.RULE_PACK_ACTIVATED) { "unexpected ${event.type}" }
        val data = parse(mapper, event.dataJson)
        data.requiredText("packIdentifier")
        // each entity is marked under its own tenant's binding, so the consumer's transaction (bound to the event's
        // tenant) is set aside: a nested transaction under another binding is refused (INV-Q-3997)
        val marked =
            tx.outsideOpenTransaction {
                directory.certifiedEntities().count { certified ->
                    tenants.bind(certified.tenant, actor) {
                        tx.inTransaction { mark.mark(certified.entityRef, StaleTrigger.NEW_PACK) } == MarkOutcome.Marked
                    }
                }
            }
        return if (marked > 0) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}

/** IdentityTripleChanged marks the certification of the entity the event names stale with IDENTITY_TRIPLE. */
class IdentityTripleChangedHandler(
    private val mark: MarkCertificationStale,
    private val mapper: ObjectMapper,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event.type == ConsumedCertificationEvents.IDENTITY_TRIPLE_CHANGED) { "unexpected ${event.type}" }
        val entityRef = parse(mapper, event.dataJson).requiredText("entityRef")
        return if (mark.mark(entityRef, StaleTrigger.IDENTITY_TRIPLE) == MarkOutcome.Marked) {
            HandleOutcome.APPLIED
        } else {
            HandleOutcome.IGNORED
        }
    }
}

/**
 * Marks ISSUANCE_MODE in the transaction of the election: after the profile change is appended, an entry whose
 * attribute equals `issuanceMode` marks the entity (INV-Q-16393). A rolled-back election rolls the mark back with it.
 */
class IssuanceModeCertificationMark(
    private val delegate: EntityProfileChangePublisher,
    private val mark: MarkCertificationStale,
) : EntityProfileChangePublisher {
    override fun publish(change: EntityProfileChange) {
        delegate.publish(change)
        if (change.changedAttributes.any { it.attribute == ISSUANCE_MODE_ATTRIBUTE }) {
            mark.mark(change.entityRef, StaleTrigger.ISSUANCE_MODE)
        }
    }
}
