package com.dynokore.gst.evidence.application.cases

import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.tenancykernel.TenantId

/** The permission keys of the case operations (INV-Q-11686 A). A decision names a permission, never a role. */
object CasePermissions {
    const val PRIVACY_REQUEST_HANDLE = "privacy-request.handle"
    const val SECURITY_CASE_RECORD = "security-case.record"
}

/** Thrown when the principal does not hold the permission. */
class CaseAccessDenied(
    val permission: String,
) : RuntimeException("permission $permission is not held")

/** Decides one permission key for a principal in a tenant. The decision names no entity (INV-Q-11688 A). */
interface CaseAuthorizer {
    /** @throws CaseAccessDenied when [permission] is not held. */
    fun require(
        tenant: TenantId,
        principal: Principal,
        permission: String,
    )
}
