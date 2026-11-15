package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/** The permission key of `bumpAuthorityIncarnation` (INV-Q-10142 A, row "Run the authority incarnation command"). */
const val AUTHORITY_INCARNATION_BUMP = "authority-incarnation.bump"

/** The caller check of `bumpAuthorityIncarnation`: the permission key, decided per request (INV-Q-10027). */
fun interface BumpPermissionCheck {
    fun mayBump(actor: String): Boolean
}

/**
 * Decides [AUTHORITY_INCARNATION_BUMP] for the acting user ID through `AuthorityReplica.decide` at INSTALLATION scope
 * (INV-Q-10027; INV-Q-10142 A). No role is named here: the replica maps the key to the roles of the deployed form.
 * Fail closed: a denial, an exception, no replica and no bound actor all refuse (OWASP ASVS V4.1.5). The key must be
 * in the permission catalogue the service loaded; otherwise construction fails, so a mistyped key stops the start-up
 * (INV-Q-10027).
 */
@Component
class ReplicaBumpPermissionCheck internal constructor(
    matrix: PermissionMatrix,
    private val replica: () -> AuthorityReplica?,
    private val clock: Clock,
    private val key: String,
) : BumpPermissionCheck {
    @Autowired
    constructor(
        matrix: PermissionMatrix,
        replicas: ObjectProvider<AuthorityReplica>,
        clock: Clock,
    ) : this(matrix, { replicas.ifAvailable }, clock, AUTHORITY_INCARNATION_BUMP)

    init {
        checkNotNull(matrix.kindOf(Permission(key))) { "the permission '$key' is not in the permission catalogue" }
    }

    override fun mayBump(actor: String): Boolean =
        runCatching {
            val authority = replica() ?: return false
            val request =
                AuthorizationRequest(
                    TenantId.PLATFORM,
                    Principal(actor, PrincipalKind.HUMAN),
                    "",
                    key,
                    level = ScopeLevel.INSTALLATION,
                )
            authority.decide(request, Deadline.after(DECISION_BUDGET, clock)).allowed
        }.getOrDefault(false)

    private companion object {
        val DECISION_BUDGET: Duration = Duration.ofMillis(500)
    }
}
