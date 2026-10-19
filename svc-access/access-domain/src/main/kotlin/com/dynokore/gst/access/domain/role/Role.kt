package com.dynokore.gst.access.domain.role

/**
 * The role catalogue (INV-NOTE-3 section 22.2, INV-REQ-859 behaviour 2). [wire] is the role as named on the wire.
 * The catalogue is closed: a name that is not here is no role, and a grant of it is refused.
 */
enum class Role(
    val wire: String,
) {
    TENANT_ADMIN("tenant.admin"),
    TAX_OPERATOR("tax.operator"),
    TAX_APPROVER("tax.approver"),
    FINANCE_VIEWER("finance.viewer"),
    INTEGRATION_ENGINEER("integration.engineer"),
    AUDITOR("auditor"),
    AGENT_OPERATOR("agent.operator"),
    PLATFORM_SUPPORT("platform.support"),
    PLATFORM_ENGINEER("platform.engineer"),
    REGULATORY_AUTHOR("regulatory.author"),
    REGULATORY_APPROVER("regulatory.approver"),
    ENTITY_ADMIN("entity.admin"),
    SECURITY_OFFICER("security.officer"),
    BILLING_CONTACT("billing.contact"),
    CLIENT_SIGNATORY("client.signatory"),
    DEPLOYMENT_OPERATOR("deployment.operator"),
    IMPLEMENTATION_CONSULTANT("implementation.consultant"),
    RELEASE_MANAGER("release.manager"),
    COMMERCIAL_ADMIN("commercial.admin"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        /** The role named [wire] exactly, or null: no case folding and no trimming. */
        fun ofWire(wire: String): Role? = byWire[wire]
    }
}
