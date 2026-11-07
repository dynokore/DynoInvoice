package com.dynokore.gst.transport.adapters.events

import com.dynokore.gst.transport.application.ApplyEntityProfileChanged
import com.dynokore.gst.transport.application.ProfileApplyResult
import com.dynokore.gst.transport.domain.EntityNode
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.IssuanceModeEntry
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The consumer of `EntityProfileChanged` (tenancy contract; INV-Q-4993 B, INV-T-4908): reads the `issuanceMode`
 * entries of `node.profile` and `node.structureVersion` and nothing else, and hands them to [ApplyEntityProfileChanged]
 * in the inbox consumer's tenant transaction. An event without `node`, or without an `issuanceMode` entry, changes
 * nothing; a payload that breaks the contract is a [ContractViolation] (dead letter). The tenant is the envelope's.
 * A row of the same entity ref under another tenant is logged by tenant ids and entity only, never the payload.
 */
class EntityProfileChangedHandler(
    private val apply: ApplyEntityProfileChanged,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val entity = EntityRef(text(data, "entityRef"))
        val tenant = TenantRef(event.tenant.value.toString())
        return when (val result = apply.apply(tenant, entity, node(data.get("node")?.takeUnless { it.isNull }))) {
            ProfileApplyResult.Applied -> {
                HandleOutcome.APPLIED
            }

            ProfileApplyResult.Ignored -> {
                HandleOutcome.IGNORED
            }

            is ProfileApplyResult.TenantMismatch -> {
                log.warn(
                    "EntityProfileChanged for an entity held under another tenant: entity={} stored={} event={}",
                    result.entity.value,
                    result.storedTenant.value,
                    result.eventTenant.value,
                )
                HandleOutcome.IGNORED
            }
        }
    }

    private fun node(node: JsonNode?): EntityNode? =
        node?.let {
            if (!it.isObject) throw ContractViolation("node is not an object")
            EntityNode(structureVersion(it), issuanceModes(it.get("profile")?.takeUnless { p -> p.isNull }))
        }

    private fun structureVersion(node: JsonNode): Long {
        val version = node.get("structureVersion")
        if (version == null || !version.isIntegralNumber || !version.canConvertToLong()) {
            throw ContractViolation("node.structureVersion is not an integer")
        }
        return version.asLong()
    }

    private fun issuanceModes(profile: JsonNode?): List<IssuanceModeEntry> {
        if (profile != null && !profile.isArray) throw ContractViolation("node.profile is not an array")
        return profile
            ?.values()
            ?.filter { it.get("attribute")?.takeIf { a -> a.isString }?.asString() == ISSUANCE_MODE }
            ?.map { IssuanceModeEntry(text(it, "value"), date(it)) }
            .orEmpty()
    }

    private fun date(entry: JsonNode): LocalDate =
        try {
            LocalDate.parse(text(entry, "effectiveDate"))
        } catch (e: DateTimeParseException) {
            throw ContractViolation("effectiveDate is not a date", e)
        }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text)
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private fun text(
        data: JsonNode,
        name: String,
    ): String {
        val node = data.get(name)?.takeUnless { it.isNull } ?: throw ContractViolation("the payload has no $name")
        val value = if (node.isString) node.asString() else null
        return value?.takeUnless { it.isBlank() } ?: throw ContractViolation("$name is not a non-blank string")
    }

    private companion object {
        const val ISSUANCE_MODE = "issuanceMode"
        private val log = LoggerFactory.getLogger(EntityProfileChangedHandler::class.java)
    }
}
