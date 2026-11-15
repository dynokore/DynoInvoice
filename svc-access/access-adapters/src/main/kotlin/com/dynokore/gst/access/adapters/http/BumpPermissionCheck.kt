package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import org.springframework.stereotype.Component

/** The permission key of `bumpAuthorityIncarnation` (INV-Q-10142 A, row "Run the authority incarnation command"). */
const val AUTHORITY_INCARNATION_BUMP = "authority-incarnation.bump"

/** The caller check of `bumpAuthorityIncarnation`: the permission key, decided per request (INV-Q-10027). */
fun interface BumpPermissionCheck {
    fun mayBump(actor: String): Boolean
}

/**
 * Allows when one installation-level role of the acting user ID (`InstallationRoleDirectory.rolesHeldBy`, the
 * configured `access.installation-roles`) has [AUTHORITY_INCARNATION_BUMP] PERMITTED in the permission matrix of the
 * deployed form (INV-Q-9243 B; the way `resealBreakGlass` is decided, INV-Q-16100 A). No role is named here. A blank
 * actor, no matching role, any other allowance, a failed read and an exception all refuse (fail closed, OWASP ASVS
 * V4.1.5). The key must be in the loaded matrix, otherwise construction fails and the start-up stops.
 */
@Component
class ResolvedBumpPermissionCheck internal constructor(
    private val roles: InstallationRoleDirectory,
    private val matrix: PermissionMatrix,
    key: String,
) : BumpPermissionCheck {
    @org.springframework.beans.factory.annotation.Autowired
    constructor(roles: InstallationRoleDirectory, matrix: PermissionMatrix) :
        this(roles, matrix, AUTHORITY_INCARNATION_BUMP)

    private val permission = Permission(key)

    init {
        checkNotNull(matrix.kindOf(permission)) { "the permission '$key' is not in the permission catalogue" }
    }

    override fun mayBump(actor: String): Boolean =
        runCatching {
            actor.isNotBlank() &&
                roles.rolesHeldBy(actor).any { matrix.allowance(it, permission) == Allowance.PERMITTED }
        }.getOrDefault(false)
}
