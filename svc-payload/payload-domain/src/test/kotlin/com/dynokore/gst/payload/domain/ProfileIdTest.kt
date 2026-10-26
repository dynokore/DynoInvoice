package com.dynokore.gst.payload.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProfileIdTest {
    private val pintBilling = "urn:peppol:bis:billing"
    private val pintPayables = "urn:peppol:bis:Payables"
    private val bisBilling = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0"
    private val bisPayables = "urn:fdc:peppol.eu:2017:poacc:Payables:01:1.0"

    private fun mismatch(
        d: Direction,
        f: SpecificationFamily,
        actual: String,
        expected: String,
    ) = ProfileId.check(d, f, actual) shouldBe ProfileCheck.Mismatch(expected, actual)

    @Test
    fun `INV-AC-524 a purchase with the billing value of either family is a Mismatch to the same family Payables`() {
        mismatch(Direction.PURCHASE, SpecificationFamily.PINT_SG, pintBilling, pintPayables)
        mismatch(Direction.PURCHASE, SpecificationFamily.SG_PEPPOL_BIS, bisBilling, bisPayables)
    }

    @Test
    fun `INV-AC-10270 the four correct pairs are Consistent`() {
        ProfileId.check(Direction.SALE, SpecificationFamily.PINT_SG, pintBilling) shouldBe ProfileCheck.Consistent
        ProfileId.check(Direction.PURCHASE, SpecificationFamily.PINT_SG, pintPayables) shouldBe ProfileCheck.Consistent
        ProfileId.check(Direction.SALE, SpecificationFamily.SG_PEPPOL_BIS, bisBilling) shouldBe ProfileCheck.Consistent
        ProfileId.check(Direction.PURCHASE, SpecificationFamily.SG_PEPPOL_BIS, bisPayables) shouldBe
            ProfileCheck.Consistent
    }

    @Test
    fun `INV-AC-10270 expected equals the four literals`() {
        val all =
            Direction.entries
                .flatMap { d -> SpecificationFamily.entries.map { f -> (d to f) to ProfileId.expected(d, f) } }
                .toMap()
        all shouldBe
            mapOf(
                (Direction.SALE to SpecificationFamily.PINT_SG) to pintBilling,
                (Direction.PURCHASE to SpecificationFamily.PINT_SG) to pintPayables,
                (Direction.SALE to SpecificationFamily.SG_PEPPOL_BIS) to bisBilling,
                (Direction.PURCHASE to SpecificationFamily.SG_PEPPOL_BIS) to bisPayables,
            )
    }

    @Test
    fun `INV-AC-10270 PURCHASE with PINT_SG passes only for Payables`() {
        val d = Direction.PURCHASE
        val f = SpecificationFamily.PINT_SG
        ProfileId.check(d, f, pintPayables) shouldBe ProfileCheck.Consistent
        for (a in listOf(pintBilling, "urn:peppol:bis:payables", "")) mismatch(d, f, a, pintPayables)
    }

    @Test
    fun `INV-AC-10270 a trailing space is a Mismatch`() {
        mismatch(Direction.SALE, SpecificationFamily.PINT_SG, "$pintBilling ", pintBilling)
    }

    @Test
    fun `INV-AC-10270 a different case is a Mismatch`() {
        mismatch(Direction.SALE, SpecificationFamily.PINT_SG, "urn:peppol:bis:Billing", pintBilling)
        mismatch(Direction.PURCHASE, SpecificationFamily.PINT_SG, "urn:peppol:bis:payables", pintPayables)
    }

    @Test
    fun `INV-AC-10270 the Payables value on a SALE document is a Mismatch`() {
        mismatch(Direction.SALE, SpecificationFamily.PINT_SG, pintPayables, pintBilling)
    }

    @Test
    fun `INV-AC-10270 the other family's value is a Mismatch`() {
        mismatch(Direction.PURCHASE, SpecificationFamily.PINT_SG, bisPayables, pintPayables)
    }

    @Test
    fun `INV-AC-10270 the empty string is a Mismatch`() {
        mismatch(Direction.SALE, SpecificationFamily.SG_PEPPOL_BIS, "", bisBilling)
    }

    @Test
    fun `INV-AC-10270 both enums have exactly the contract constants`() {
        Direction.entries.map { it.name } shouldBe listOf("SALE", "PURCHASE")
        SpecificationFamily.entries.map { it.name } shouldBe listOf("PINT_SG", "SG_PEPPOL_BIS")
    }
}
