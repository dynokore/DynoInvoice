package com.dynokore.gst.access.domain.role

/** A permission as named in access-svc's action catalogue (INV-API-3325 `action`; INV-REQ-859 behaviour 2). */
@JvmInline
value class Permission(
    val value: String,
) {
    init {
        require(
            value.isNotBlank() && value.length <= MAX_LENGTH,
        ) { "permission is required, at most $MAX_LENGTH characters" }
    }

    private companion object {
        const val MAX_LENGTH = 64
    }
}

/** Whether a permission only reads or also changes state: the auditor's role holds reads only (INV-REQ-863). */
enum class PermissionKind { READ, WRITE }

/**
 * Y is permitted; S is permitted within the granted scope only (INV-NOTE-3 section 22.3). Both need a covering grant.
 */
enum class Allowance { PERMITTED, WITHIN_SCOPE }

/** One permission of the matrix with its kind. */
data class PermissionDefinition(
    val permission: Permission,
    val kind: PermissionKind,
)

/**
 * The permission matrix: which roles hold which permissions (INV-REQ-859 behaviour 2). A role or permission the matrix
 * does
 * not list holds nothing, so an unknown pair is denied (fail closed). The constructor refuses a read-only role that
 * holds a
 * write permission, so the auditor cannot be given one by data (INV-REQ-863).
 */
class PermissionMatrix(
    definitions: Collection<PermissionDefinition>,
    rows: Map<Role, Map<Permission, Allowance>>,
    private val readOnlyRoles: Set<Role> = setOf(Role.AUDITOR),
) {
    private val kinds: Map<Permission, PermissionKind> = definitions.associate { it.permission to it.kind }
    private val matrix: Map<Role, Map<Permission, Allowance>> = rows.mapValues { it.value.toMap() }

    init {
        require(kinds.size == definitions.size) { "a permission is defined twice" }
        matrix.forEach { (role, permissions) ->
            permissions.keys.forEach { permission ->
                val kind =
                    requireNotNull(
                        kinds[permission],
                    ) { "role ${role.wire} lists an undefined permission ${permission.value}" }
                require(role !in readOnlyRoles || kind == PermissionKind.READ) {
                    "role ${role.wire} is read-only and cannot hold the write permission ${permission.value}"
                }
            }
        }
    }

    /** The allowance of [role] for [permission], or null when the matrix lists none. */
    fun allowance(
        role: Role,
        permission: Permission,
    ): Allowance? = matrix[role]?.get(permission)

    fun permissionsOf(role: Role): Set<Permission> = matrix[role]?.keys.orEmpty()

    fun kindOf(permission: Permission): PermissionKind? = kinds[permission]

    /**
     * Every listed cell, as role key to permission key to allowance: the shape the gst-catalogue table is compared in
     * (INV-AC-7945). A role with no cell is present with an empty map.
     */
    fun export(): Map<String, Map<String, Allowance>> =
        matrix.entries.associate { (role, permissions) ->
            role.wire to permissions.entries.associate { it.key.value to it.value }
        }

    companion object {
        /** Holds nothing: every question is denied. */
        val EMPTY = PermissionMatrix(emptyList(), emptyMap())
    }
}
