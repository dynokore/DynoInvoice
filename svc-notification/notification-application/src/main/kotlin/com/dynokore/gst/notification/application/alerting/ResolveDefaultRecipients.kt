package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.DefaultAlertKind
import com.dynokore.gst.notification.domain.alerting.ROLE_TENANT_ADMIN
import com.dynokore.gst.notification.domain.alerting.defaultRole
import com.dynokore.platform.tenancykernel.TenantId

/** The holders of [role] in [tenant] only (INV-REQ-910; tenant isolation, OWASP ASVS V4, NIST SP 800-53 AC-3). */
fun interface RoleHolders {
    fun holders(
        tenant: TenantId,
        role: String,
    ): List<String>
}

sealed interface DefaultRecipientResult {
    /** [people] is sorted ascending and distinct. */
    data class Recipients(
        val people: List<String>,
    ) : DefaultRecipientResult

    /** No holder of the default role nor of tenant.admin: the caller retries or dead-letters, never drops. */
    data class NoRecipient(
        val kind: DefaultAlertKind,
    ) : DefaultRecipientResult
}

/** INV-REQ-910: holders of the default role in the tenant, else its tenant.admin holders, else NoRecipient. */
class ResolveDefaultRecipients(
    private val holders: RoleHolders,
) {
    fun resolve(
        tenant: TenantId,
        kind: DefaultAlertKind,
    ): DefaultRecipientResult {
        val people =
            sorted(holders.holders(tenant, defaultRole(kind)))
                .ifEmpty { sorted(holders.holders(tenant, ROLE_TENANT_ADMIN)) }
        return if (people.isEmpty()) {
            DefaultRecipientResult.NoRecipient(kind)
        } else {
            DefaultRecipientResult.Recipients(people)
        }
    }

    private fun sorted(people: List<String>): List<String> = people.distinct().sorted()
}
