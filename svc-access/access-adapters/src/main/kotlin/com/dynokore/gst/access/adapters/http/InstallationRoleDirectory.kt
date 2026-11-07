package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.domain.role.Role

/**
 * The installation-scope roles of the configured `access.installation-roles` entries (INV-Q-8789 A): the roles whose
 * subject equals the actor exactly (case, spaces and prefixes all count). It is the one source of the INSTALLATION
 * lookup of [LiveSetterPermission] and [ResolvedResealPermissionCheck]; no grant counts here and no role is named.
 */
class InstallationRoleDirectory(
    entries: List<InstallationRole> = emptyList(),
) {
    private val bySubject: Map<String, Set<Role>> =
        entries.groupBy { it.subject }.mapValues { (_, rows) -> rows.map { it.role }.toSet() }

    /** The roles configured for exactly [actor]; empty for any other subject. */
    fun rolesHeldBy(actor: String): Set<Role> = bySubject[actor].orEmpty()
}
