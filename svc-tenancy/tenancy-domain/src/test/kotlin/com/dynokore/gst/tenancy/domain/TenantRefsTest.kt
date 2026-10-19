package com.dynokore.gst.tenancy.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class TenantRefsTest {
    private fun named(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8))

    @Test
    fun `the canonical lower-case uuid text is that uuid`() {
        val text = "0a000000-0000-4000-8000-00000000000a"
        assertEquals(UUID.fromString(text), TenantRefs.idOf(text).value)
    }

    @Test
    fun `an upper-case uuid text is name-based and differs from the lower-case result`() {
        val upper = "0A000000-0000-4000-8000-00000000000A"
        assertEquals(named("gst-tenant:$upper"), TenantRefs.idOf(upper).value)
        assertNotEquals(TenantRefs.idOf(upper.lowercase()).value, TenantRefs.idOf(upper).value)
    }

    @Test
    fun `any other text is name-based`() {
        assertEquals(named("gst-tenant:TEN-7B1004"), TenantRefs.idOf("TEN-7B1004").value)
    }

    @Test
    fun `the platform id is refused`() {
        assertThrows(IllegalArgumentException::class.java) { TenantRefs.idOf("ffffffff-ffff-ffff-ffff-ffffffffffff") }
    }

    @Test
    fun `empty text is refused`() {
        assertThrows(IllegalArgumentException::class.java) { TenantRefs.idOf("") }
    }

    @Test
    fun `two calls with the same text return equal ids`() {
        assertEquals(TenantRefs.idOf("TEN-7B1004"), TenantRefs.idOf("TEN-7B1004"))
    }

    @Test
    fun `refOfContract is the text of the name-based uuid and idOf of it is that uuid`() {
        val ref = TenantRefs.refOfContract("CTR-2026-0091")
        assertEquals(named("gst-contract:CTR-2026-0091").toString(), ref)
        assertEquals(named("gst-contract:CTR-2026-0091"), TenantRefs.idOf(ref).value)
    }

    @Test
    fun `refOfWorkspace is the text of the name-based uuid and differs by production tenant and by name`() {
        val ref = TenantRefs.refOfWorkspace("TEN-7B1004", "sandbox-1")
        assertEquals(named("gst-workspace:TEN-7B1004:sandbox-1").toString(), ref)
        assertNotEquals(ref, TenantRefs.refOfWorkspace("TEN-7B1004", "sandbox-2"))
        assertNotEquals(ref, TenantRefs.refOfWorkspace("TEN-7B1005", "sandbox-1"))
    }
}
