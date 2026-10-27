package com.dynokore.gst.access.domain.role

/**
 * The gate of INV-Q-9069 A: the six Operator roles are never granted or mapped in a customer tenant. Names are compared
 * by equality on the wire name; every other role is grantable. [GRANTABLE] and [REFUSED] together must name every
 * [Role], so a new role is not decided silently.
 */
object TenantGrantableRoles {
    val REFUSED: Set<String> =
        setOf(
            "platform.support",
            "platform.engineer",
            "release.manager",
            "commercial.admin",
            "regulatory.author",
            "regulatory.approver",
        )

    val GRANTABLE: Set<String> =
        setOf(
            "tenant.admin",
            "tax.operator",
            "tax.approver",
            "finance.viewer",
            "integration.engineer",
            "auditor",
            "agent.operator",
            "entity.admin",
            "security.officer",
            "billing.contact",
            "client.signatory",
            "deployment.operator",
            "implementation.consultant",
        )

    fun refuses(role: Role): Boolean = role.wire in REFUSED
}
