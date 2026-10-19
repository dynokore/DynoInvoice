package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The field-by-field reading of a parsed CloudEvents node into an [Envelope] ([EnvelopeJson.read]). */
internal object EnvelopeFields {
    const val SPEC_VERSION = "1.0"

    fun build(
        node: ObjectNode,
        mapper: ObjectMapper,
    ): Envelope =
        Envelope(
            id = EventId(text(node, "id")),
            source = text(node, "source"),
            type = text(node, "type"),
            subject = text(node, "subject"),
            time = Instant.parse(text(node, "time")),
            tenant = tenantOf(node),
            entity = if (node.has("tenantid")) realTenantEntityOf(node) else platformEntityOf(node),
            docUuid = docUuidOf(text(node, "subject")),
            transactionDate = optional(node, "transactiondate")?.let { LocalDate.parse(it) },
            docSequence = docSequence(node),
            instanceId = text(node, "instanceid"),
            correlation = CorrelationId(text(node, "correlationid")),
            causation = CausationId(text(node, "causationid")),
            dataJson = mapper.writeValueAsString(node.get("data") ?: throw ContractViolation("data is missing")),
        )

    // INV-Q-2927 option D, INV-Q-3018: an absent `tenantid` maps back to the reserved platform tenant, for a
    // platform event type only; a domain event without one is a contract violation (FR-K.3). The reserved id
    // itself never travels on the wire, so a record naming it is refused.
    private fun tenantOf(node: ObjectNode): TenantId {
        if (node.has("tenantid")) {
            val tenant = TenantId.of(text(node, "tenantid"))
            if (tenant.isPlatform) throw ContractViolation("tenantid must not name the reserved platform tenant")
            return tenant
        }
        if (!Envelope.isPlatformType(text(node, "type"))) throw ContractViolation("tenantid is required")
        return TenantId.PLATFORM
    }

    // INV-Q-3165: "tenantid present, entityref absent" maps back to the tenant-wide entity, for a platform
    // event type only; a domain event without entityref is a contract violation (FR-K.3). The reserved id
    // itself never travels on the wire.
    private fun realTenantEntityOf(node: ObjectNode): EntityRef {
        if (!node.has("entityref")) {
            if (!Envelope.isPlatformType(text(node, "type"))) throw ContractViolation("entityref is required")
            return EntityRef.TENANT_WIDE
        }
        val entity = EntityRef(text(node, "entityref"))
        if (entity.isTenantWide) throw ContractViolation("entityref must not name the reserved tenant-wide entity")
        return entity
    }

    private fun platformEntityOf(node: ObjectNode): EntityRef {
        if (node.has("entityref")) throw ContractViolation("a tenantless platform event carries no entityref")
        return EntityRef.PLATFORM
    }

    // The contract schema has no Doc UUID attribute: `subject` is the Doc UUID where the event concerns a document
    // (FR-K.3), and EventContext forces that, so a UUID subject is the Doc UUID.
    private fun docUuidOf(subject: String): DocUuid? =
        try {
            DocUuid(UUID.fromString(subject))
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun docSequence(node: ObjectNode): Long? {
        val value = node.get("docsequence") ?: return null
        if (!value.isIntegralNumber) throw ContractViolation("docsequence must be an integer")
        return value.asLong()
    }

    private fun optional(
        node: JsonNode,
        field: String,
    ): String? = node.get(field)?.takeUnless { it.isNull }?.asString()

    fun text(
        node: JsonNode,
        field: String,
    ): String {
        val value = node.get(field)
        if (value == null || !value.isString || value.asString().isBlank()) {
            throw ContractViolation("$field is required")
        }
        return value.asString()
    }
}
