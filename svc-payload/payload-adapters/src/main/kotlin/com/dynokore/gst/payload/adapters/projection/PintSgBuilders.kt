package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.CustomizationId
import com.dynokore.gst.payload.domain.CustomizationIdResult
import com.dynokore.gst.payload.domain.Direction
import com.dynokore.gst.payload.domain.ProfileId
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.gst.payload.domain.party.PartyIdentifierInput
import com.dynokore.gst.payload.domain.party.PartyIdentifiers
import com.dynokore.gst.payload.ubl.ubl_21.cbc.CustomizationIDType
import com.dynokore.gst.payload.ubl.ubl_21.cbc.ProfileIDType
import com.dynokore.gst.payload.ubl.ubl_21.creditnote.CreditNoteType
import com.dynokore.gst.payload.ubl.ubl_21.invoice.InvoiceType
import com.dynokore.gst.payload.ubl.ubl_21.creditnote.ObjectFactory as CreditNoteFactory
import com.dynokore.gst.payload.ubl.ubl_21.invoice.ObjectFactory as InvoiceFactory

/** Document type code 380, root Invoice, specification PINT-SG-1.0 of the fixture (INV-Q-7106 A item 3). */
class PintSgInvoiceBuilder : DocumentBuilder {
    override val key = BuilderKey(SpecificationFamily.PINT_SG, "380")

    /** The first refusal that applies wins: the identity triple, then the Customization ID (INV-Q-7180 A). */
    override fun build(
        input: PartyIdentifierInput,
        direction: Direction,
        family: SpecificationFamily,
        submissionType: SubmissionType,
    ): BuildResult {
        val (parties, resolved) = PartyXml.parties(input)
        val customization = CustomizationId.expected(family, submissionType)
        return when {
            parties == null -> {
                BuildResult.Refused((resolved as PartyIdentifiers.Refused).reason)
            }

            customization !is CustomizationIdResult.Selected -> {
                with(customization as CustomizationIdResult.Unpublished) {
                    BuildResult.CustomizationIdUnpublished(this.family, this.submissionType)
                }
            }

            else -> {
                val invoice =
                    InvoiceType().apply {
                        customizationID = CustomizationIDType().apply { value = customization.value }
                        profileID = ProfileIDType().apply { value = ProfileId.expected(direction, family) }
                        accountingCustomerParty = parties.buyer
                        accountingSupplierParty = parties.supplier
                    }
                val xml = PartyXml.marshal(InvoiceFactory().createInvoice(invoice), InvoiceFactory::class.java)
                BuildResult.Built(xml, parties.senderId)
            }
        }
    }
}

/** Document type code 381, root CreditNote, specification PINT-SG-1.0 of the fixture. */
class PintSgCreditNoteBuilder : DocumentBuilder {
    override val key = BuilderKey(SpecificationFamily.PINT_SG, "381")

    /** The first refusal that applies wins: the identity triple, then the Customization ID (INV-Q-7180 A). */
    override fun build(
        input: PartyIdentifierInput,
        direction: Direction,
        family: SpecificationFamily,
        submissionType: SubmissionType,
    ): BuildResult {
        val (parties, resolved) = PartyXml.parties(input)
        val customization = CustomizationId.expected(family, submissionType)
        return when {
            parties == null -> {
                BuildResult.Refused((resolved as PartyIdentifiers.Refused).reason)
            }

            customization !is CustomizationIdResult.Selected -> {
                with(customization as CustomizationIdResult.Unpublished) {
                    BuildResult.CustomizationIdUnpublished(this.family, this.submissionType)
                }
            }

            else -> {
                val note =
                    CreditNoteType().apply {
                        customizationID = CustomizationIDType().apply { value = customization.value }
                        profileID = ProfileIDType().apply { value = ProfileId.expected(direction, family) }
                        accountingCustomerParty = parties.buyer
                        accountingSupplierParty = parties.supplier
                    }
                val xml = PartyXml.marshal(CreditNoteFactory().createCreditNote(note), CreditNoteFactory::class.java)
                BuildResult.Built(xml, parties.senderId)
            }
        }
    }
}
