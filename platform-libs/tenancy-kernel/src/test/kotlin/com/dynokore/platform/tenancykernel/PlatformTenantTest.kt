package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * The reserved platform tenant (Keel INV-Q-2927 option D, INV-Q-3012, INV-Q-3016): the max UUID,
 * refused by token-claim parsing and by the ordinary bind, bound only through [PlatformTenantBinding].
 * The token side end to end (service-runtime's token adapters) is INV-T-2874's test.
 */
class PlatformTenantTest {
    @Test
    fun `the platform tenant is the max UUID and nothing else is platform`() {
        assertEquals("ffffffff-ffff-ffff-ffff-ffffffffffff", TenantId.PLATFORM.toString())
        assertTrue(TenantId.PLATFORM.isPlatform)
        assertFalse(TenantId(UUID.randomUUID()).isPlatform)
        assertFalse(TenantId(UUID(0L, 0L)).isPlatform)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ffffffff-ffff-ffff-ffff-ffffffffffff", "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF"])
    fun `no token claim can carry the platform tenant`(claim: String) {
        assertThrows(IllegalArgumentException::class.java) { TenantId.fromClaim(claim) }
        // Storage still reads it back: platform rows hold it.
        assertEquals(TenantId.PLATFORM, TenantId.of(claim))
    }

    @Test
    fun `an ordinary token claim parses`() {
        val tenant = UUID.randomUUID()
        assertEquals(TenantId(tenant), TenantId.fromClaim(tenant.toString()))
        assertThrows(IllegalArgumentException::class.java) { TenantId.fromClaim("not-a-uuid") }
    }

    @Test
    fun `the ordinary bind refuses the platform tenant and binds nothing`() {
        val ctx = TenantContextImpl()
        assertThrows(IllegalArgumentException::class.java) {
            ctx.bind(TenantId.PLATFORM, "svc:test/job:probe") { ctx.current() }
        }
        assertThrows(IllegalStateException::class.java) { ctx.current() }
    }

    @Test
    fun `bindPlatform binds the platform tenant and restores the enclosing one`() {
        val ctx = TenantContextImpl()
        val tenant = TenantId(UUID.randomUUID())
        assertEquals(TenantId.PLATFORM, ctx.bindPlatform("svc:test/job:probe") { ctx.current() })
        assertThrows(IllegalStateException::class.java) { ctx.current() }
        val seen =
            ctx.bind(tenant, "test-actor") {
                listOf(ctx.bindPlatform("svc:test/job:probe") { ctx.current() }, ctx.current())
            }
        assertEquals(listOf(TenantId.PLATFORM, tenant), seen)
    }
}
