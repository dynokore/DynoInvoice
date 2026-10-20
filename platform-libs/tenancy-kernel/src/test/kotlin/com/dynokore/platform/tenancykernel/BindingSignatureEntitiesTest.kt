package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/** INV-T-7520: the layout of the entity field and the four-field form (README.md "Signed binding"); no database. */
class BindingSignatureEntitiesTest {
    private val key = SignedBinding.key(3)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val e1 = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val e2 = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Test
    fun `the entity field is sorted, written once and lower-case, and - for the empty set`() {
        assertEquals("$e1,$e2", BindingSignature.entityField(linkedSetOf(e2, e1)))
        assertEquals("$e1,$e2", BindingSignature.entityField(linkedSetOf(e1, e2)))
        assertEquals("$e1", BindingSignature.entityField(setOf(e1)))
        assertEquals("-", BindingSignature.entityField(emptySet()))
    }

    @Test
    fun `a signed value has five fields with a set and four without, and the order of the set does not change it`() {
        val four = BindingSignature.sign(key, tenant, "a", 2_000_000_000L)
        val five = BindingSignature.sign(key, tenant, "a", 2_000_000_000L, setOf(e1, e2))
        assertEquals(4, four.split('.').size)
        assertEquals(5, five.split('.').size)
        assertEquals("$e1,$e2", five.split('.')[3])
        assertEquals(five, BindingSignature.sign(key, tenant, "a", 2_000_000_000L, linkedSetOf(e2, e1)))
        // the empty set is a set: five fields, a different MAC from the value with no set
        val empty = BindingSignature.sign(key, tenant, "a", 2_000_000_000L, emptySet())
        assertEquals(5, empty.split('.').size)
        assertEquals("-", empty.split('.')[3])
        assertNotEquals(four.substringAfterLast('.'), empty.substringAfterLast('.'))
    }

    @Test
    fun `a set other than the signed one gives another MAC`() {
        val one = BindingSignature.sign(key, tenant, "a", 2_000_000_000L, setOf(e1))
        val two = BindingSignature.sign(key, tenant, "a", 2_000_000_000L, setOf(e1, e2))
        assertNotEquals(one.substringAfterLast('.'), two.substringAfterLast('.'))
    }
}
