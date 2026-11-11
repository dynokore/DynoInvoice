package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.CustomizationId
import com.dynokore.gst.payload.domain.CustomizationIdResult
import com.dynokore.gst.payload.domain.Direction
import com.dynokore.gst.payload.domain.ProfileId
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.gst.payload.domain.header.DocumentHeaderInput
import com.dynokore.gst.payload.domain.party.PartyIdentifierInput
import com.dynokore.gst.payload.domain.party.PartyIdentifiers
import com.dynokore.gst.payload.domain.party.RefusalReason
import com.dynokore.gst.payload.ubl.ubl_21.cbc.CustomizationIDType
import com.dynokore.gst.payload.ubl.ubl_21.cbc.IDType
import com.dynokore.gst.payload.ubl.ubl_21.cbc.IssueDateType
import com.dynokore.gst.payload.ubl.ubl_21.cbc.NoteType
import com.dynokore.gst.payload.ubl.ubl_21.cbc.ProfileIDType
import com.dynokore.gst.payload.ubl.ubl_21.creditnote.CreditNoteType
import com.dynokore.gst.payload.ubl.ubl_21.invoice.InvoiceType
import java.time.LocalDate
import javax.xml.datatype.DatatypeConstants
import javax.xml.datatype.DatatypeFactory
import javax.xml.datatype.XMLGregorianCalendar
import com.dynokore.gst.payload.ubl.ubl_21.creditnote.ObjectFactory as CreditNoteFactory
import com.dynokore.gst.payload.ubl.ubl_21.invoice.ObjectFactory as InvoiceFactory

/** An xsd:date without a time zone: it marshals as yyyy-MM-dd, the text of `LocalDate.toString()`. */
private fun xsdDate(value: LocalDate): XMLGregorianCalendar =
    DatatypeFactory.newInstance().newXMLGregorianCalendarDate(
        value.year,
        value.monthValue,
        value.dayOfMonth,
        DatatypeConstants.FIELD_UNDEFINED,
    )

/** Writes one cbc:Note with [text] exactly as given (no trimming); a null text writes none (IBT-022, INV-REQ-695). */
private fun addNote(
    notes: MutableList<NoteType>,
    text: String?,
) {
    if (text != null) notes.add(NoteType().apply { value = text })
}

/** Document type code 380, root Invoice, specification PINT-SG-1.0 of the fixture (INV-Q-7106 A item 3). */
class PintSgInvoiceBuilder : DocumentBuilder {
    override val key = BuilderKey(SpecificationFamily.PINT_SG, "380")

    /** Refusal order: identity triple, document number, document date, Customization ID (INV-Q-7180 A). */
    override fun build(
        input: PartyIdentifierInput,
        header: DocumentHeaderInput,
        direction: Direction,
        family: SpecificationFamily,
        submissionType: SubmissionType,
    ): BuildResult {
        val (parties, resolved) = PartyXml.parties(input)
        val customization = CustomizationId.expected(family, submissionType)
        val date = header.documentDate
        return when {
            parties == null -> {
                BuildResult.Refused((resolved as PartyIdentifiers.Refused).reason)
            }

            header.documentNumber.isNullOrBlank() -> {
                BuildResult.Refused(RefusalReason.DOCUMENT_NUMBER_ABSENT)
            }

            date == null -> {
                BuildResult.Refused(RefusalReason.DOCUMENT_DATE_ABSENT)
            }

            customization !is CustomizationIdResult.Selected -> {
                with(customization as CustomizationIdResult.Unpublished) {
                    BuildResult.CustomizationIdUnpublished(this.family, this.submissionType)
                }
            }

            else -> {
                val invoice =
                    InvoiceType().apply {
                        id = IDType().apply { value = header.documentNumber }
                        issueDate = IssueDateType().apply { value = xsdDate(date) }
                        customizationID = CustomizationIDType().apply { value = customization.value }
                        addNote(note, header.invoiceNote)
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

    /** Refusal order: identity triple, document number, document date, Customization ID (INV-Q-7180 A). */
    override fun build(
        input: PartyIdentifierInput,
        header: DocumentHeaderInput,
        direction: Direction,
        family: SpecificationFamily,
        submissionType: SubmissionType,
    ): BuildResult {
        val (parties, resolved) = PartyXml.parties(input)
        val customization = CustomizationId.expected(family, submissionType)
        val date = header.documentDate
        return when {
            parties == null -> {
                BuildResult.Refused((resolved as PartyIdentifiers.Refused).reason)
            }

            header.documentNumber.isNullOrBlank() -> {
                BuildResult.Refused(RefusalReason.DOCUMENT_NUMBER_ABSENT)
            }

            date == null -> {
                BuildResult.Refused(RefusalReason.DOCUMENT_DATE_ABSENT)
            }

            customization !is CustomizationIdResult.Selected -> {
                with(customization as CustomizationIdResult.Unpublished) {
                    BuildResult.CustomizationIdUnpublished(this.family, this.submissionType)
                }
            }

            else -> {
                val note =
                    CreditNoteType().apply {
                        id = IDType().apply { value = header.documentNumber }
                        issueDate = IssueDateType().apply { value = xsdDate(date) }
                        customizationID = CustomizationIDType().apply { value = customization.value }
                        addNote(note, header.invoiceNote)
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
