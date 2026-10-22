package com.dynokore.gst.access.application.authorise

import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.principal.PrincipalRepository
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.principal.MachinePermissionSets
import com.dynokore.gst.access.domain.principal.PrincipalScopes
import com.dynokore.gst.access.domain.principal.PrincipalStatus
import com.dynokore.platform.tenancykernel.TransactionRunner

sealed interface MachineDecision {
    data object Allow : MachineDecision

    data class Deny(
        val reasonCode: String,
    ) : MachineDecision
}

/**
 * `authorise` for a service principal (INV-REQ-584, INV-REQ-867, INV-REQ-911): the permission must be in the fixed set
 * of the principal's type, and the principal's scope must cover the target. A monitoring principal's scope is the
 * installation and its set holds no tenant data, so the target entity does not matter for it. Unknown, revoked or
 * out-of-set is a DENY (fail closed); it is read live at every call.
 */
class MachineAuthorise(
    private val tx: TransactionRunner,
    private val principals: PrincipalRepository,
    private val directory: EntityDirectory,
    private val sets: MachinePermissionSets,
) {
    fun handle(
        principalRef: String,
        permission: String,
        target: EntityRef,
    ): MachineDecision {
        val principal = tx.inTransaction { principals.find(principalRef) } ?: return MachineDecision.Deny(NO_PRINCIPAL)
        return when {
            principal.status != PrincipalStatus.ACTIVE -> {
                MachineDecision.Deny(REVOKED)
            }

            !sets.permits(principal.type, permission) -> {
                MachineDecision.Deny(NOT_IN_SET)
            }

            PrincipalScopes.isInstallation(principal.scope) -> {
                MachineDecision.Allow
            }

            principal.scope.startsWith(ENTITY) -> {
                if (principal.scope.removePrefix(ENTITY) == target.value) {
                    MachineDecision.Allow
                } else {
                    MachineDecision.Deny(OUT_OF_SCOPE)
                }
            }

            principal.scope.startsWith(TENANT) -> {
                if (directory.membership(target) != null) MachineDecision.Allow else MachineDecision.Deny(OUT_OF_SCOPE)
            }

            else -> {
                MachineDecision.Deny(OUT_OF_SCOPE)
            }
        }
    }

    companion object {
        const val NO_PRINCIPAL = "NO_PRINCIPAL"
        const val REVOKED = "REVOKED"
        const val NOT_IN_SET = "NOT_IN_PERMISSION_SET"
        const val OUT_OF_SCOPE = "OUT_OF_SCOPE"
        private const val ENTITY = "entity:"
        private const val TENANT = "tenant:"
    }
}
