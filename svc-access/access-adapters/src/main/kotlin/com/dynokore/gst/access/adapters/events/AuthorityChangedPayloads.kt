package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.consent.ConsentAuthorityRecord
import com.dynokore.gst.access.application.grant.RoleGrantChange
import com.dynokore.gst.access.domain.consent.ConsentStatus
import com.dynokore.gst.access.domain.consent.ScopeItem
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The `authority-changed-payload` documents of the three record kinds (INV-API-3325, INV-Q-8972 A), shared by the live
 * publishers and the replay source so that a replayed state is the same JSON as its live event.
 */
class AuthorityChangedPayloads(
    private val mapper: ObjectMapper,
) {
    fun consent(record: ConsentAuthorityRecord): ObjectNode {
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

    fun roleGrant(change: RoleGrantChange): ObjectNode {
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

    /** A principalRecord in state REVOKED: [validFrom] is the instant of the revocation. */
    fun principalRevoked(
        principalRef: String,
        authorityVersion: Long,
        validFrom: Instant,
    ): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("changeKind", "REVOCATION")
        root.put("principalRef", principalRef)
        root.put("authorityVersion", authorityVersion)
        root.putObject("validity").put("from", ISO.format(validFrom.atOffset(ZoneOffset.UTC)))
        root.putObject("principalRecord").put("state", "REVOKED")
        return root
    }

    private companion object {
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
