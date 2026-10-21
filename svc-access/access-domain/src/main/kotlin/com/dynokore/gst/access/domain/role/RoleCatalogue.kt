package com.dynokore.gst.access.domain.role

/**
 * The roles that exist in this installation's deployment form (INV-REQ-876, INV-REQ-888, INV-AC-1928): a role outside
 * it is no role here, so a grant of it is refused and a listing never shows it.
 */
class RoleCatalogue(
    roles: Set<Role>,
) {
    val roles: Set<Role> = roles.toSet()

    fun contains(role: Role): Boolean = role in roles

    companion object {
        /** Every role of the enum: test data and the form-less case. */
        val ALL = RoleCatalogue(Role.entries.toSet())
    }
}
