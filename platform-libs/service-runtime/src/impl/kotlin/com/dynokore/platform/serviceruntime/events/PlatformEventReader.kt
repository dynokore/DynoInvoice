package com.dynokore.platform.serviceruntime.events

import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.serviceruntime.connection.ConnectionFailure
import com.dynokore.platform.serviceruntime.replica.PolicyChanged
import com.dynokore.platform.serviceruntime.replica.PolicySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The tolerant reader of the platform events `service-runtime` gives consumers (INV-REQ-1163, INV-AC-2148;
 * INV-API-3306, INV-API-3308, INV-API-3310): it reads the fields it knows and ignores every other field of the
 * envelope and the payload, so an event of a later minor version is read exactly as one of the version it was
 * built against. The major version is the type's `.vN` (API-40): another major, or another event type, is a
 * [ContractViolation], as is a missing required field or a value of the wrong type.
 */
class PlatformEventReader(
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    private val envelopes = EnvelopeJson(mapper)

    /**
     * A `PolicyChanged` record read straight off its producer topic (INV-API-3306: no inbox). [topic] and the
     * record's offset order it in the policy replica: a later offset of the same topic wins per (scope level,
     * VP id, effective date), so a redelivered offset changes nothing (INV-Q-2968).
     */
    fun policyChanged(
        topic: String,
        record: LogRecord,
    ): PolicyChanged {
        val envelope = envelope(record.envelopeJson, PlatformEventTypes.POLICY_CHANGED)
        val data = data(envelope)
        return wrapped {
            PolicyChanged(
                scope = policyScope(envelope, text(data, "scope")),
                variationPointId = text(data, "vpId"),
                value = data.get("value")?.let { mapper.writeValueAsString(it) } ?: "null",
                effectiveDate = LocalDate.parse(text(data, "effectiveDate")),
                source = enumValue<PolicySource>(text(data, "source")),
                relaxationAckRef = optionalText(data, "relaxationAcknowledgementReference"),
                topic = topic,
                offset = record.offset,
            )
        }
    }

    /** A `ConnectionStatusChanged` (INV-API-3308) from its envelope JSON, as the consumer's inbox hands it over. */
    fun connectionStatusChanged(envelopeJson: String): ConnectionStatusReport {
        val envelope = envelope(envelopeJson, PlatformEventTypes.CONNECTION_STATUS_CHANGED)
        val data = data(envelope)
        return wrapped {
            ConnectionStatusReport(
                eventId = envelope.id,
                service = envelope.service,
                instance = envelope.instanceId,
                tenant = envelope.tenant,
                entity = envelope.entity,
                connection = text(data, "connection"),
                stateText = text(data, "state"),
                lastFailure = data.get("lastFailure")?.takeUnless { it.isNull }?.let(::failure),
                time = envelope.time,
            )
        }
    }

    /** An `EvidenceArtefactRecorded` (INV-API-3310) from its envelope JSON, its artefact type kept as received. */
    fun evidenceArtefactRecorded(envelopeJson: String): ReceivedEvidenceArtefact {
        val envelope = envelope(envelopeJson, PlatformEventTypes.EVIDENCE_ARTEFACT_RECORDED)
        val data = data(envelope)
        return wrapped {
            ReceivedEvidenceArtefact(
                eventId = envelope.id,
                service = envelope.service,
                tenant = envelope.tenant,
                entity = envelope.entity,
                artefact =
                    EvidenceArtefact(
                        EvidenceArtefactType(text(data, "artefactType")),
                        text(data, "reference"),
                        text(data, "hash"),
                    ),
                time = envelope.time,
            )
        }
    }

    private fun envelope(
        json: String,
        expectedType: String,
    ): Envelope {
        val envelope = envelopes.read(json)
        if (envelope.type != expectedType) {
            throw ContractViolation("expected $expectedType, was ${envelope.type}")
        }
        return envelope
    }

    private fun data(envelope: Envelope): JsonNode {
        val node = mapper.readTree(envelope.dataJson)
        if (!node.isObject) throw ContractViolation("data must be a JSON object")
        return node
    }

    /**
     * The payload's scope (`INSTALLATION`, or `LEVEL:id`, config-resolver's `SettingTarget.scopeText`) as the
     * [PolicyScope] whose most specific level is that one. The tenant and, for an entity-scoped level, the entity
     * are the envelope's: an installation value arrives without `tenantid` (INV-Q-2927 option D).
     */
    private fun policyScope(
        envelope: Envelope,
        text: String,
    ): PolicyScope {
        if (text == "INSTALLATION") {
            if (!envelope.tenant.isPlatform) throw ContractViolation("an installation value carries no tenant")
            return PolicyScope.installation()
        }
        val level = enumValue<ScopeLevel>(text.substringBefore(':', ""))
        val id = text.substringAfter(':', "")
        if (id.isBlank() ||
            level == ScopeLevel.INSTALLATION
        ) {
            throw ContractViolation("scope must be INSTALLATION or LEVEL:id")
        }
        if (envelope.tenant.isPlatform) throw ContractViolation("a $level value carries its tenant")
        val tenant = envelope.tenant
        val entity = envelope.entity.takeUnless { it == EntityRef.TENANT_WIDE }?.value
        if (level.entityScoped && entity == null) throw ContractViolation("a $level value carries its entity")
        return when (level) {
            ScopeLevel.TENANT -> PolicyScope.tenant(tenant)
            ScopeLevel.GROUP -> PolicyScope.tenant(tenant, group = id)
            ScopeLevel.ENTITY -> PolicyScope.entity(tenant, id)
            ScopeLevel.FEED -> PolicyScope.entity(tenant, entity!!, feed = id)
            ScopeLevel.AP_CONNECTION -> PolicyScope.entity(tenant, entity!!, apConnection = id)
            ScopeLevel.CLIENT_ENTITY -> PolicyScope.entity(tenant, entity!!, clientEntity = id)
            ScopeLevel.INSTALLATION -> PolicyScope.installation()
        }
    }

    private fun failure(node: JsonNode): ConnectionFailure {
        if (!node.isObject) throw ContractViolation("lastFailure must be an object")
        return ConnectionFailure(Instant.parse(text(node, "at")), text(node, "reason"))
    }

    private fun text(
        node: JsonNode,
        field: String,
    ): String {
        val value = node.get(field)
        if (value == null || !value.isString ||
            value.asString().isBlank()
        ) {
            throw ContractViolation("$field is required")
        }
        return value.asString()
    }

    private fun optionalText(
        node: JsonNode,
        field: String,
    ): String? {
        val value = node.get(field)
        if (value == null || value.isNull) return null
        if (!value.isString) throw ContractViolation("$field must be a string")
        return value.asString()
    }

    private inline fun <reified E : Enum<E>> enumValue(text: String): E =
        enumValues<E>().firstOrNull { it.name == text }
            ?: throw ContractViolation("unknown ${E::class.simpleName} '$text'")

    private inline fun <T> wrapped(block: () -> T): T =
        try {
            block()
        } catch (failure: IllegalArgumentException) {
            throw ContractViolation("invalid payload: ${failure.message}", failure)
        } catch (failure: DateTimeParseException) {
            throw ContractViolation("invalid payload date: ${failure.message}", failure)
        }
}
