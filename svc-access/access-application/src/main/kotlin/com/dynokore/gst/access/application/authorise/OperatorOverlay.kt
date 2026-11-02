package com.dynokore.gst.access.application.authorise

import com.dynokore.gst.access.application.elevation.Elevations
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.domain.elevation.OperatorAccess
import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.Role
import java.time.Instant

/**
 * What an operator may do on top of its grants (INV-REQ-862 behaviours 1 and 3, INV-REQ-888 behaviour 2). Called inside
 * the transaction of `authorise`, after the grant decision; reads the records live, keeps no state (INV-REQ-1214).
 * - An ALLOW whose role is `deployment.operator` stands only inside an approved, unexpired diagnostic invitation of the
 *   same identity for that tenant; otherwise it is DENY ([OperatorAccess.NO_INVITATION]).
 * - A DENY of exactly the keys in [OperatorAccess.OPENED_BY_ELEVATION] becomes ALLOW inside an approved, unexpired
 *   elevation of the same identity for that tenant. Every other key, tenant or instant keeps the DENY.
 * Each access it opens is audited (INV-AC-1930, INV-AC-7953).
 */
class OperatorOverlay(
    private val elevations: Elevations,
    private val audit: GrantAudit,
) {
    fun apply(
        decision: Decision,
        principal: IdentityRef,
        permission: Permission,
        tenant: TenantRef?,
        at: Instant,
    ): Decision =
        when {
            decision is Decision.Allow && decision.role == Role.DEPLOYMENT_OPERATOR -> {
                val records = elevations.findByRequester(principal)
                val invited =
                    if (tenant == null) {
                        OperatorAccess.invitedHere(records, principal, at)
                    } else {
                        OperatorAccess.invited(records, principal, tenant, at)
                    }
                if (invited) {
                    used("operatorDiagnosticAccess", principal, permission, tenant)
                    decision
                } else {
                    Decision.Deny(OperatorAccess.NO_INVITATION)
                }
            }

            decision is Decision.Deny &&
                tenant != null &&
                permission in OperatorAccess.OPENED_BY_ELEVATION &&
                OperatorAccess.elevated(elevations.findByRequester(principal), principal, tenant, at) -> {
                used("elevatedAccess", principal, permission, tenant)
                Decision.Allow(Role.PLATFORM_SUPPORT)
            }

            else -> {
                decision
            }
        }

    private fun used(
        action: String,
        principal: IdentityRef,
        permission: Permission,
        tenant: TenantRef?,
    ) = audit.record(
        GrantAuditEntry(principal.value, action, "tenant:${tenant?.value ?: "platform"}", permission.value, null),
    )
}
