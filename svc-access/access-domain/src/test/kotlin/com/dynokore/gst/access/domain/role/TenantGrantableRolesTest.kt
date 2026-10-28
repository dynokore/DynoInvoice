package com.dynokore.gst.access.domain.role

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** INV-AC-12017: the six Operator roles are refused in a customer tenant, the other 13 are not. */
class TenantGrantableRolesTest {
    @Test
    fun `INV-AC-12017 refused and grantable names together equal the wire names of Role entries`() {
        assertEquals(
            Role.entries.map { it.wire }.toSet(),
            TenantGrantableRoles.REFUSED + TenantGrantableRoles.GRANTABLE,
        )
    }

    @Test
    fun `INV-AC-12017 refused and grantable names have no common member`() {
        assertEquals(emptySet<String>(), TenantGrantableRoles.REFUSED intersect TenantGrantableRoles.GRANTABLE)
    }

    @Test
    fun `INV-AC-12017 refuses is true exactly for the six operator wire names`() {
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
        assertEquals(13, Role.entries.size - refused.size)
    }

    @Test
    fun `INV-AC-12017 a case variant and a trailing space are not members of the refused set`() {
        assertFalse("Platform.Support" in TenantGrantableRoles.REFUSED)
        assertFalse("platform.support " in TenantGrantableRoles.REFUSED)
    }

    @Test
    fun `INV-AC-12017 a role added to a copy of the sets fails the equality`() {
        val copy = TenantGrantableRoles.REFUSED + TenantGrantableRoles.GRANTABLE + "new.role"
        assertFalse(Role.entries.map { it.wire }.toSet() == copy)
    }

    @Test
    fun `INV-AC-12017 a name in both sets fails the intersection check`() {
        val overlap = TenantGrantableRoles.REFUSED + "tenant.admin" intersect TenantGrantableRoles.GRANTABLE
        assertEquals(setOf("tenant.admin"), overlap)
    }
}
