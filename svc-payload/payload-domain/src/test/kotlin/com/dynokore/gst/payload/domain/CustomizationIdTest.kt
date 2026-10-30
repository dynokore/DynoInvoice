package com.dynokore.gst.payload.domain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class CustomizationIdTest {
    private val pint1a = "urn:peppol:pint:billing-1@sg-1"
    private val bis1a = "urn:cen.eu:en16931:2017#conformant#urn:fdc:peppol.eu:2017:poacc:billing:international:sg:3.0"
    private val pint2 = "urn:peppol:pint:billing-1@sg-1:LocalTaxInvoice:sg:1.0"
    private val bis2 = "urn:cen.eu:en16931:2017#conformant#urn:fdc:peppol.eu:2017:poacc:billing:LocalTaxInvoice:sg:1.0"

    private val pint = SpecificationFamily.PINT_SG
    private val bis = SpecificationFamily.SG_PEPPOL_BIS

    private val table: Map<Pair<SpecificationFamily, SubmissionType>, CustomizationIdResult> =
        mapOf(
            (pint to SubmissionType.TYPE_1A) to CustomizationIdResult.Selected(pint1a),
            (bis to SubmissionType.TYPE_1A) to CustomizationIdResult.Selected(bis1a),
            (pint to SubmissionType.TYPE_2) to CustomizationIdResult.Selected(pint2),
            (bis to SubmissionType.TYPE_2) to CustomizationIdResult.Selected(bis2),
            (pint to SubmissionType.TYPE_1B) to CustomizationIdResult.Unpublished(pint, SubmissionType.TYPE_1B),
            (bis to SubmissionType.TYPE_1B) to CustomizationIdResult.Unpublished(bis, SubmissionType.TYPE_1B),
            (pint to SubmissionType.TYPE_3) to CustomizationIdResult.Unpublished(pint, SubmissionType.TYPE_3),
            (bis to SubmissionType.TYPE_3) to CustomizationIdResult.Unpublished(bis, SubmissionType.TYPE_3),
        )

    private val selectedValues = listOf(pint1a, bis1a, pint2, bis2)

    @Test
    fun `INV-AC-1505 a Type 2 SG Peppol BIS supply carries the quoted IBT-024 and IBT-023`() {
        CustomizationId.expected(bis, SubmissionType.TYPE_2) shouldBe CustomizationIdResult.Selected(bis2)
        ProfileId.expected(Direction.SALE, bis) shouldBe "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0"
    }

    @Test
    fun `INV-AC-12967 the table covers all eight pairs and each gives its expected result`() {
        table.size shouldBe SpecificationFamily.entries.size * SubmissionType.entries.size
        for (family in SpecificationFamily.entries) {
            for (type in SubmissionType.entries) {
                CustomizationId.expected(family, type) shouldBe table.getValue(family to type)
            }
        }
    }

    @Test
    fun `INV-AC-12967 the four published values are pairwise different`() {
        selectedValues.toSet().size shouldBe 4
        CustomizationId.expected(pint, SubmissionType.TYPE_2) shouldNotBe
            CustomizationId.expected(bis, SubmissionType.TYPE_2)
        CustomizationId.expected(pint, SubmissionType.TYPE_1A) shouldNotBe
            CustomizationId.expected(bis, SubmissionType.TYPE_1A)
    }

    @Test
    fun `INV-AC-12967 a trailing space or a capitalised Billing is not equal to any published value`() {
        for (value in selectedValues) {
            for (variant in listOf("$value ", value.replace("billing", "Billing"))) {
                (CustomizationIdResult.Selected(variant) in table.values) shouldBe false
            }
        }
    }

    @Test
    fun `INV-AC-12967 Unpublished carries its inputs for TYPE_1B and TYPE_3 of both families`() {
        for (family in SpecificationFamily.entries) {
            for (type in listOf(SubmissionType.TYPE_1B, SubmissionType.TYPE_3)) {
                CustomizationId.expected(family, type) shouldBe CustomizationIdResult.Unpublished(family, type)
            }
        }
    }
}
