package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.Direction
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.gst.payload.domain.header.DocumentHeaderInput
import com.dynokore.gst.payload.domain.party.PartyIdentifierInput

/**
 * Document type code 380 of the family SG_PEPPOL_BIS (INV-REQ-508). The PINT_SG builder writes the Customization ID
 * and the Profile ID from its `family` argument, so this builder only fixes its own key and delegates.
 */
class SgPeppolBisInvoiceBuilder : DocumentBuilder {
    override val key = BuilderKey(SpecificationFamily.SG_PEPPOL_BIS, "380")

    private val delegate = PintSgInvoiceBuilder()

    @Suppress("LongParameterList") // the signature of DocumentBuilder
    override fun build(
        input: PartyIdentifierInput,
        header: DocumentHeaderInput,
        direction: Direction,
        family: SpecificationFamily,
        submissionType: SubmissionType,
        endpoint: EndpointInputs,
    ): BuildResult {
        require(family == SpecificationFamily.SG_PEPPOL_BIS) { "the builder $key refuses the family $family" }
        return delegate.build(input, header, direction, family, submissionType, endpoint)
    }
}

/** Document type code 381 of the family SG_PEPPOL_BIS (INV-REQ-508); see [SgPeppolBisInvoiceBuilder]. */
class SgPeppolBisCreditNoteBuilder : DocumentBuilder {
    override val key = BuilderKey(SpecificationFamily.SG_PEPPOL_BIS, "381")

    private val delegate = PintSgCreditNoteBuilder()

    @Suppress("LongParameterList") // the signature of DocumentBuilder
    override fun build(
        input: PartyIdentifierInput,
        header: DocumentHeaderInput,
        direction: Direction,
        family: SpecificationFamily,
        submissionType: SubmissionType,
        endpoint: EndpointInputs,
    ): BuildResult {
        require(family == SpecificationFamily.SG_PEPPOL_BIS) { "the builder $key refuses the family $family" }
        return delegate.build(input, header, direction, family, submissionType, endpoint)
    }
}
