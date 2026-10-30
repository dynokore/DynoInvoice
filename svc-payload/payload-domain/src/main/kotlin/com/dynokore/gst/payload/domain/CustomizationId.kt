package com.dynokore.gst.payload.domain

/** The outcome of the Customization ID selection: a published value, or a pair no source publishes. */
sealed interface CustomizationIdResult {
    data class Selected(
        val value: String,
    ) : CustomizationIdResult

    data class Unpublished(
        val family: SpecificationFamily,
        val submissionType: SubmissionType,
    ) : CustomizationIdResult
}

/**
 * The Customization ID (IBT-024) of a payload (INV-REQ-508, INV-Q-7180 A): the four published rows of INV-NOTE-2
 * section 9.6, a pure function. Every other pair is Unpublished (fail closed); there is no default value.
 */
object CustomizationId {
    private const val PINT_TYPE_1A = "urn:peppol:pint:billing-1@sg-1"
    private const val BIS_TYPE_1A =
        "urn:cen.eu:en16931:2017#conformant#urn:fdc:peppol.eu:2017:poacc:billing:international:sg:3.0"
    private const val PINT_TYPE_2 = "urn:peppol:pint:billing-1@sg-1:LocalTaxInvoice:sg:1.0"
    private const val BIS_TYPE_2 =
        "urn:cen.eu:en16931:2017#conformant#urn:fdc:peppol.eu:2017:poacc:billing:LocalTaxInvoice:sg:1.0"

    fun expected(
        family: SpecificationFamily,
        submissionType: SubmissionType,
    ): CustomizationIdResult {
        val published =
            when (family to submissionType) {
                SpecificationFamily.PINT_SG to SubmissionType.TYPE_1A -> PINT_TYPE_1A
                SpecificationFamily.SG_PEPPOL_BIS to SubmissionType.TYPE_1A -> BIS_TYPE_1A
                SpecificationFamily.PINT_SG to SubmissionType.TYPE_2 -> PINT_TYPE_2
                SpecificationFamily.SG_PEPPOL_BIS to SubmissionType.TYPE_2 -> BIS_TYPE_2
                else -> null
            }
        return if (published != null) {
            CustomizationIdResult.Selected(published)
        } else {
            CustomizationIdResult.Unpublished(family, submissionType)
        }
    }
}
