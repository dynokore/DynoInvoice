package com.dynokore.gst.access.application.authorise

import com.dynokore.gst.access.application.consent.AgentScope
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.grant.AuthorisationPolicy
import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.ZoneId

/**
 * `authorise(principal, permission, target)`: role, permission and scope evaluated together against the live grant
 * table,
 * never a cached tenant context (INV-REQ-859, INV-REQ-1214). Each call reads the grants again; this class keeps no
 * state.
 * A grant of `agent.operator`, or one whose scope is consent-derived, also needs a live consent record that grants
 * the scope item the permission needs.
 * A target whose entity is unknown to the structure is denied (fail closed).
 */
class Authorise(
    private val tx: TransactionRunner,
    private val grants: GrantRepository,
    private val directory: EntityDirectory,
    private val matrix: PermissionMatrix,
    private val agentScope: AgentScope,
    private val clock: Clock,
) {
    fun handle(
        principal: IdentityRef,
        permission: Permission,
        target: Target,
    ): Decision {
        val membership = directory.membership(target.entity) ?: return Decision.Deny(UNKNOWN_ENTITY)
        // a live read of the grant table in one transaction under the bound tenant; nothing is kept between calls
        val now = clock.instant()
        val held =
            tx.inTransaction {
                // an agent's grant is usable only while the consent record, read now, grants the scope item the
                // permission needs (INV-REQ-913 behaviour 4, INV-REQ-865)
                val today = now.atZone(BUSINESS_ZONE).toLocalDate()
                grants.findByIdentity(principal).filter {
                    when {
                        it.role == Role.AGENT_OPERATOR -> {
                            agentScope.permits(
                                principal,
                                target.entity,
                                permission,
                                today,
                            )
                        }

                        it.scope is Scope.ConsentDerived -> {
                            agentScope.permitsClient(target.entity, permission, today)
                        }

                        else -> {
                            true
                        }
                    }
                }
            }
        return AuthorisationPolicy.decide(
            held,
            AuthorisationPolicy.Question(permission, target, membership),
            matrix,
            now,
        )
    }

    companion object {
        const val UNKNOWN_ENTITY = "NO_ENTITY"

        /** The business date of a consent's `validFrom` is the platform's (config-resolver BusinessDate). */
        private val BUSINESS_ZONE: ZoneId = ZoneId.of("Asia/Singapore")
    }
}
