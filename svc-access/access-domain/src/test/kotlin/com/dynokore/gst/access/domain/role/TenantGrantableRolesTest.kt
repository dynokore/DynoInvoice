package com.dynokore.gst.access.domain.role

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** INV-AC-9076 (domain side): the six Operator roles are refused in a customer tenant, the other 13 are not. */
class TenantGrantableRolesTest {
    @Test
    fun `refused and grantable names together equal the role catalogue and do not overlap`() {
        assertEquals(
            Role.entries.map { it.wire }.toSet(),
            TenantGrantableRoles.REFUSED + TenantGrantableRoles.GRANTABLE,
        )
        assertEquals(emptySet<String>(), TenantGrantableRoles.REFUSED intersect TenantGrantableRoles.GRANTABLE)
        assertEquals(6, TenantGrantableRoles.REFUSED.size)
        assertEquals(13, TenantGrantableRoles.GRANTABLE.size)
    }

    @Test
    fun `refuses is true exactly for the six operator wire names`() {
        val refused =
            Role.entries
                .filter { TenantGrantableRoles.refuses(it) }
                .map { it.wire }
                .toSet()
        assertEquals(
            setOf(
                "platform.support",
                "platform.engineer",
                "release.manager",
                "commercial.admin",
                "regulatory.author",
                "regulatory.approver",
            ),
            refused,
        )
    }
}
