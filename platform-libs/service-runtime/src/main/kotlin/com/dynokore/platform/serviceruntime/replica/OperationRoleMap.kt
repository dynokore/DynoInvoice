package com.dynokore.platform.serviceruntime.replica

/**
 * How a role's cell for an operation reads in the generated permission matrix: [PERMITTED] or [WITHIN_SCOPE]. A role
 * with no cell is not listed at all (denied). Whatever the allowance, a grant counts only where its scope covers the
 * request, so the replica never reads an allowance as wider than the grant (INV-Q-8972 A).
 */
enum class CellAllowance { PERMITTED, WITHIN_SCOPE }

/**
 * The operation-to-roles map the authority replica decides against (INV-Q-8972 A, owner rulings R2 and R3 in
 * INV-NOTE-10639). Role names are product vocabulary and stay out of this library (ARC-16): service-runtime sees roles
 * as opaque strings and asks this port which roles the matrix lists for an operation. product-runtime binds it to the
 * generated gst-catalogue map for the deployment form of the installation.
 */
fun interface OperationRoleMap {
    /**
     * The roles that hold [permissionKey], with their allowance. An unknown key, a role name or a key with a stray
     * character gives the empty map (fail closed).
     */
    fun rolesFor(permissionKey: String): Map<String, CellAllowance>
}
