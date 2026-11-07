package com.dynokore.gst.tenancy.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ExemptPurchaseKeysTest {
    private val basis = "Legal fees relate to exempt supplies"

    private fun refusal(
        codes: List<String>,
        basis: String = this.basis,
    ) = ElectionKeys.exemptPurchaseRefusal(codes, basis)

    @Test
    fun `INV-AC-18132 the constant is the exact text`() {
        assertEquals("EXEMPT_PURCHASE_CODES", ElectionKeys.EXEMPT_PURCHASE_CODES)
    }

    @Test
    fun `INV-AC-18132 the singular look-alike is not the constant`() {
        assertNotEquals(ElectionKeys.EXEMPT_PURCHASE_CODES, "EXEMPT_PURCHASE_CODE")
    }

    @Test
    fun `INV-AC-18132 the lowercase look-alike is not the constant`() {
        assertNotEquals(ElectionKeys.EXEMPT_PURCHASE_CODES, "exempt_purchase_codes")
    }

    @Test
    fun `INV-AC-18132 the trailing-space look-alike is not the constant`() {
        assertNotEquals(ElectionKeys.EXEMPT_PURCHASE_CODES, "EXEMPT_PURCHASE_CODES ")
    }

    @Test
    fun `INV-AC-18132 the key is not yet recordable`() {
        assertNull(ElectionKeys.recordableKind("EXEMPT_PURCHASE_CODES"))
    }

    @Test
    fun `INV-AC-18132 valid codes and basis are accepted`() {
        assertNull(refusal(listOf("TX-N33", "TX-ESS")))
    }

    @Test
    fun `INV-AC-18132 a code with a trailing space is rejected`() {
        assertNotNull(refusal(listOf("TX-N33 ")))
    }

    @Test
    fun `INV-AC-18132 a lowercase code is rejected`() {
        assertNotNull(refusal(listOf("tx-n33")))
    }

    @Test
    fun `INV-AC-18132 an empty code list is rejected`() {
        assertNotNull(refusal(emptyList()))
    }

    @Test
    fun `INV-AC-18132 fifty-one codes are rejected and fifty are accepted`() {
        assertNotNull(refusal((1..51).map { "C$it" }))
        assertNull(refusal((1..50).map { "C$it" }))
    }

    @Test
    fun `INV-AC-18132 a duplicate code is rejected`() {
        assertNotNull(refusal(listOf("TX-N33", "TX-N33")))
    }

    @Test
    fun `INV-AC-18132 a code of 41 characters is rejected and 40 accepted`() {
        assertNotNull(refusal(listOf("A".repeat(41))))
        assertNull(refusal(listOf("A".repeat(40))))
    }

    @Test
    fun `INV-AC-18132 a code starting with a dash is rejected`() {
        assertNotNull(refusal(listOf("-TX")))
    }

    @Test
    fun `INV-AC-18132 a blank basis of three blanks is rejected`() {
        assertNotNull(refusal(listOf("TX-N33"), "   "))
    }

    @Test
    fun `INV-AC-18132 a basis of 501 characters is rejected and 500 accepted`() {
        assertNotNull(refusal(listOf("TX-N33"), "a".repeat(501)))
        assertNull(refusal(listOf("TX-N33"), "a".repeat(500)))
    }
}
