package com.dynokore.gst.access.adapters.review

import com.dynokore.gst.access.adapters.http.LiveTenantGrants
import com.dynokore.gst.access.domain.grant.TenantAuthority
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.platform.tenancykernel.TenantContext

/** The permission key of `runAccessReview` and `closeAccessReview` (INV-REQ-894, the row "run access reviews"). */
const val ACCESS_REVIEW_RUN = "access-review.run"

/** Whether the caller holds the key `access-review.run` in the bound tenant, decided per request (INV-Q-10027). */
fun interface ReviewPermissionCheck {
    fun mayRunReviews(): Boolean
}

/**
 * This service is the authority and holds no replica, so it decides the key from the actor's own live grants of the
 * bound tenant and its own [PermissionMatrix], as `LiveSetterPermission` does (OWASP ASVS V4.1, NIST SP 800-53 AC-6).
 * A grant qualifies when it is live and tenant-wide and its role has [ACCESS_REVIEW_RUN] PERMITTED or WITHIN_SCOPE in
 * the matrix; no role name is compared here. A matrix that lacks the key, no actor, the platform tenant, a failed read
 * and an exception all refuse (fail closed).
 */
class LiveReviewPermissionCheck(
    private val tenants: TenantContext,
    private val live: LiveTenantGrants,
    private val matrix: PermissionMatrix,
    key: String = ACCESS_REVIEW_RUN,
) : ReviewPermissionCheck {
    private val permission = Permission(key)

    override fun mayRunReviews(): Boolean =
        runCatching {
            val actor = tenants.currentActor() ?: return false
            live.read(actor, tenants.current()) { grants, tenant, at ->
                TenantAuthority.tenantRoles(grants, tenant, at).any {
                    matrix.allowance(it, permission).let { a ->
                        a == Allowance.PERMITTED || a == Allowance.WITHIN_SCOPE
                    }
                }
            } == true
        }.getOrDefault(false)
}
