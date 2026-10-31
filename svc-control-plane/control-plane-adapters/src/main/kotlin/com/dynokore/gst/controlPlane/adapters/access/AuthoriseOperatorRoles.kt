package com.dynokore.gst.controlPlane.adapters.access

import com.dynokore.gst.controlPlane.application.pack.OperatorRoles

/**
 * The [OperatorRoles] port over the live access-svc `authorise` query (INV-Q-8687 A, INV-Q-8723 A): for each
 * permission key of the role, `GET /internal/v1/access/authorise?principalRef=<actor>&permission=<key>&scope=platform`.
 * A role is held only if every key answers HTTP 200, `decision` ALLOW and a `role` equal to the role asked. Any other
 * role gives false without a call; every failure gives false (see [AuthoriseClient]).
 */
class AuthoriseOperatorRoles(
    private val authorise: AuthoriseClient,
) : OperatorRoles {
    override fun holds(
        actor: String,
        role: String,
    ): Boolean {
        val keys = ROLE_PERMISSIONS[role]
        return keys != null &&
            keys.all {
                val answer = authorise.ask(actor, it)
                answer != null && answer.allow && answer.role == role
            }
    }

    companion object {
        /** Catalogue keys per operator role, compared by equality (INV-Q-8687 A, INV-Q-9779 A, INV-Q-11433 A). */
        val ROLE_PERMISSIONS: Map<String, List<String>> =
            mapOf(
                "commercial.admin" to listOf("plan.manage", "entitlement.manage", "licence.manage"),
                "release.manager" to listOf("release-train.certify", "release-train.publish"),
                "regulatory.author" to listOf("regulatory-pack.author"),
                "regulatory.approver" to listOf("regulatory-pack.approve"),
                "platform.engineer" to listOf("audit-entry.purge"),
            )
    }
}
