package com.dynokore.gst.tenancy.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ElectionPermissionsTest {
    @Test
    fun `INV-AC-19183 withdraw and list equal the catalogue keys`() {
        assertEquals("election-change.withdraw", ElectionPermissions.WITHDRAW)
        assertEquals("election-change.list", ElectionPermissions.LIST)
    }

    @Test
    fun `INV-AC-19183 manage confirm and approve keep their values`() {
        assertEquals("entity-config.manage", ElectionPermissions.MANAGE)
        assertEquals("entity-config.confirm", ElectionPermissions.CONFIRM)
        assertEquals("election-change.approve", ElectionPermissions.APPROVE)
    }

    @Test
    fun `INV-AC-19183 the six constants are pairwise different`() {
        val all =
            listOf(
                ElectionPermissions.MANAGE,
                ElectionPermissions.CONFIRM,
                ElectionPermissions.APPROVE,
                ElectionPermissions.WITHDRAW,
                ElectionPermissions.LIST,
            )
        assertEquals(5, all.toSet().size)
    }
}
