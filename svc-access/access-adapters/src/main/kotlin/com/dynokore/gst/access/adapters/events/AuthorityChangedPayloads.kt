package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.adapters.persistence.AuthorityIncarnationStore
import com.dynokore.gst.access.adapters.persistence.InstallationRoleGrantRow
import com.dynokore.gst.access.application.consent.ConsentAuthorityRecord
import com.dynokore.gst.access.application.grant.RoleGrantChange
import com.dynokore.gst.access.application.operatorsession.OperatorAccessRecord
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
    private val incarnations: AuthorityIncarnationStore,
) {
    fun consent(record: ConsentAuthorityRecord): ObjectNode {
        val consent = record.consent
        val from =
            consent.terms.validFrom
                .atStartOfDay()
                .atOffset(java.time.ZoneOffset.UTC)
        val root = newRoot()
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
        val root = newRoot()
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

    /**
     * The role grant of a configured installation entry (INV-T-9235, INV-Q-8972 A): scope level INSTALLATION and no
     * `scopeRef`; [validFrom] is the instant the state was written, and there is no end and no period.
     */
    fun installationGrant(row: InstallationRoleGrantRow): ObjectNode {
        val root = newRoot()
        root.put("changeKind", "ROLE_GRANT")
        root.put("principalRef", row.subject)
        root.put("authorityVersion", row.authorityVersion)
        val node = root.putObject("roleGrantRecord")
        node.put("grantRef", row.grantRef)
        node.put("role", row.role)
        node.put("state", if (row.active) "ACTIVE" else "REMOVED")
        node.put("scopeLevel", INSTALLATION)
        node.put("validFrom", ISO.format(row.updatedAt.withOffsetSameInstant(ZoneOffset.UTC)))
        return root
    }

    /** A principalRecord in state REVOKED: [validFrom] is the instant of the revocation. */
    fun principalRevoked(
        principalRef: String,
        authorityVersion: Long,
        validFrom: Instant,
    ): ObjectNode {
        val root = newRoot()
        root.put("changeKind", "REVOCATION")
        root.put("principalRef", principalRef)
        root.put("authorityVersion", authorityVersion)
        root.putObject("validity").put("from", ISO.format(validFrom.atOffset(ZoneOffset.UTC)))
        root.putObject("principalRecord").put("state", "REVOKED")
        return root
    }

    /**
     * An OPERATOR_ACCESS record (INV-Q-16496 A): the access of one operator session in the envelope tenant, keyed on
     * the case. `doc.view.content` is never in `permissions`; `elevation` is present only for an elevated session.
     */
    fun operatorAccess(record: OperatorAccessRecord): ObjectNode {
        val root = newRoot()
        root.put("changeKind", "OPERATOR_ACCESS")
        root.put("principalRef", record.operatorRef)
        root.put("authorityVersion", record.authorityVersion)
        val node = root.putObject("operatorAccessRecord")
        node.put("caseRef", record.caseRef)
        node.put("role", record.role)
        node.put("state", "ACTIVE")
        val permissions = node.putArray("permissions")
        record.permissions.forEach { permissions.add(it) }
        node.put("validUntil", ISO.format(record.validUntil.atOffset(ZoneOffset.UTC)))
        record.elevation?.let {
            val elevation = node.putObject("elevation")
            elevation.put("elevationRef", it.elevationRef)
            elevation.put("validFrom", ISO.format(it.validFrom.atOffset(ZoneOffset.UTC)))
            elevation.put("validUntil", ISO.format(it.validUntil.atOffset(ZoneOffset.UTC)))
        }
        return root
    }

    /**
     * The document root with the stored incarnation (INV-Q-9243 B (a)). The value is converted with `toIntExact`, as
     * the generated payload property is a 32-bit integer: a stored value above `Int.MAX_VALUE` throws here, before
     * anything is appended.
     */
    private fun newRoot(): ObjectNode =
        mapper.createObjectNode().also { it.put("incarnation", Math.toIntExact(incarnations.current())) }

    private companion object {
        const val INSTALLATION = "INSTALLATION"
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
