package com.dynokore.platform.configresolver

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDate

/** The event context, the audit entry's prior state and the `PolicyChanged` payload of [JdbcPolicyWriter]. */
internal class PolicyPayloads(
    private val mapper: JsonMapper,
) {
    fun contextOf(
        key: VariationPointKey,
        target: SettingTarget,
        correlation: CorrelationId,
        causation: CausationId,
    ): EventContext {
        val tenant = target.tenant
        val entityId = target.entity
        val (eventTenant, entity) =
            when {
                tenant == null -> TenantId.PLATFORM to EntityRef.PLATFORM
                entityId != null -> tenant to EntityRef(entityId)
                else -> tenant to EntityRef.TENANT_WIDE
            }
        return EventContext(
            tenant = eventTenant,
            entity = entity,
            docUuid = null,
            transactionDate = null,
            aggregateKey = "config:$key:${target.scopeText}",
            aggregateVersion = null,
            correlation = correlation,
            causation = causation,
        )
    }

    private fun valueNode(selected: List<SelectedOption>): ArrayNode =
        mapper.createArrayNode().apply {
            selected.forEach { option ->
                val node = addObject()
                node.put("key", option.key)
                option.valueJson?.let { node.set("value", mapper.readTree(it)) }
            }
        }

    /** The prior value at the target on the effective date, as the audit entry's prior state (INV-AC-2229). */
    fun priorState(
        key: VariationPointKey,
        target: SettingTarget,
        effectiveFrom: LocalDate,
        prior: ResolvedSetting?,
    ): String {
        val node: ObjectNode = mapper.createObjectNode()
        node.put("vpId", key.value)
        node.put("scope", target.scopeText)
        node.put("effectiveDate", effectiveFrom.toString())
        if (prior == null) {
            node.putNull("value")
            node.putNull("source")
        } else {
            node.set("value", valueNode(prior.selected))
            node.put("source", sourceName(prior.source))
            putSourceFields(node, prior.source)
        }
        return mapper.writeValueAsString(node)
    }

    private fun putSourceFields(
        node: ObjectNode,
        source: PolicySource,
    ) {
        when (source) {
            is PolicySource.Override -> {
                node.put("sourceScope", source.scopeId).put("settingId", source.settingId.toString())
            }

            is PolicySource.Profile -> {
                node
                    .put("sourceScope", source.scopeId)
                    .put("settingId", source.settingId.toString())
                    .put("profileId", source.profileId)
                    .put("profileVersion", source.profileVersion)
            }

            PolicySource.CatalogueDefault -> {}
        }
    }

    /** The `PolicyChanged` payload (platform-contracts policy-changed-payload.schema.json, INV-API-3306). */
    fun policyChanged(version: SettingVersion): String {
        val node = mapper.createObjectNode()
        node.put("scope", version.target.scopeText)
        node.put("vpId", version.key.value)
        node.set("value", valueNode(version.selected))
        node.put("effectiveDate", version.effectiveFrom.toString())
        node.put("source", version.source.name)
        version.relaxation?.let { node.put("relaxationAcknowledgementReference", it.toString()) }
        // Exactly the INV-API-3306 data model: the setting id, profile id and profile version stay on the
        // setting record, where INV-AC-2092 reads them, not on the wire (owner ruling INV-Q-2979, INV-Q-3714).
        return mapper.writeValueAsString(node)
    }

    /** The audit entry's prior state of an acknowledgement: the relaxation and the roles that acknowledged before. */
    fun acknowledgedState(
        relaxation: RelaxationId,
        earlier: List<AcknowledgementRow>,
    ): String {
        val prior = mapper.createObjectNode()
        prior.put("relaxation", relaxation.toString())
        val roles = prior.putArray("acknowledgedRoles")
        earlier.forEach { roles.add(it.acknowledgement.role) }
        return mapper.writeValueAsString(prior)
    }

    private fun sourceName(source: PolicySource) =
        when (source) {
            is PolicySource.Override -> "OVERRIDE"
            is PolicySource.Profile -> "PROFILE"
            PolicySource.CatalogueDefault -> "DEFAULT"
        }
}
