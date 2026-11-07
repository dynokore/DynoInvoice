package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.party.PartyIdentifierInput
import com.dynokore.gst.payload.domain.party.PartyIdentifiers
import com.dynokore.gst.payload.ubl.ubl_21.creditnote.CreditNoteType
import com.dynokore.gst.payload.ubl.ubl_21.invoice.InvoiceType
import com.dynokore.gst.payload.ubl.ubl_21.creditnote.ObjectFactory as CreditNoteFactory
import com.dynokore.gst.payload.ubl.ubl_21.invoice.ObjectFactory as InvoiceFactory

/** Document type code 380, root Invoice, specification PINT-SG-1.0 of the fixture (INV-Q-7106 A item 3). */
class PintSgInvoiceBuilder : DocumentBuilder {
    override val key = BuilderKey(SpecificationFamily.PINT_SG, "380")

    override fun build(input: PartyIdentifierInput): BuildResult {
        val (parties, resolved) = PartyXml.parties(input)
        if (parties == null) return BuildResult.Refused((resolved as PartyIdentifiers.Refused).reason)
        val invoice =
            InvoiceType().apply {
                accountingCustomerParty = parties.buyer
                accountingSupplierParty = parties.supplier
            }
        val xml = PartyXml.marshal(InvoiceFactory().createInvoice(invoice), InvoiceFactory::class.java)
        return BuildResult.Built(xml, parties.senderId)
    }
}

/** Document type code 381, root CreditNote, specification PINT-SG-1.0 of the fixture. */
class PintSgCreditNoteBuilder : DocumentBuilder {
    override val key = BuilderKey(SpecificationFamily.PINT_SG, "381")

    override fun build(input: PartyIdentifierInput): BuildResult {
        val (parties, resolved) = PartyXml.parties(input)
        if (parties == null) return BuildResult.Refused((resolved as PartyIdentifiers.Refused).reason)
        val note =
            CreditNoteType().apply {
                accountingCustomerParty = parties.buyer
                accountingSupplierParty = parties.supplier
            }
        val xml = PartyXml.marshal(CreditNoteFactory().createCreditNote(note), CreditNoteFactory::class.java)
        return BuildResult.Built(xml, parties.senderId)
    }
}
