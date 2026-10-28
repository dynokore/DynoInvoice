package com.dynokore.gst.access.application.separation

import com.dynokore.gst.access.domain.role.Role

/** Two different roles one person may not hold together in a tenant (VP-82). Unordered: equal in either order. */
data class RolePair(
    val roles: Set<Role>,
) {
    init {
        require(roles.size == 2) { "a role pair names two different roles" }
    }

    companion object {
        fun of(
            first: Role,
            second: Role,
        ) = RolePair(setOf(first, second))
    }
}
