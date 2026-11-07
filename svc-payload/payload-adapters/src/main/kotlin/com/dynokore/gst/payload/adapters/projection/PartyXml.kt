package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.party.PartyIdentifierInput
import com.dynokore.gst.payload.domain.party.PartyIdentifierRules
import com.dynokore.gst.payload.domain.party.PartyIdentifiers
import com.dynokore.gst.payload.ubl.ubl_21.cac.CustomerPartyType
import com.dynokore.gst.payload.ubl.ubl_21.cac.PartyLegalEntityType
import com.dynokore.gst.payload.ubl.ubl_21.cac.PartyTaxSchemeType
import com.dynokore.gst.payload.ubl.ubl_21.cac.PartyType
import com.dynokore.gst.payload.ubl.ubl_21.cac.SupplierPartyType
import com.dynokore.gst.payload.ubl.ubl_21.cbc.CompanyIDType
import com.dynokore.platform.integrationcanon.xml.HardenedXml
import jakarta.xml.bind.JAXBContext
import jakarta.xml.bind.JAXBElement
import java.io.StringWriter
import javax.xml.transform.OutputKeys
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Shared steps of the builders: the party parts and the marshalling through integration-canon's hardened factories. */
internal object PartyXml {
    /** The parties of a document, or the refusal; the callerSupplier is never read (INV-REQ-699 behavior 1). */
    class Parties(
        val buyer: CustomerPartyType,
        val supplier: SupplierPartyType,
        val senderId: String,
    )

    fun parties(input: PartyIdentifierInput): Pair<Parties?, PartyIdentifiers> {
        val resolved = PartyIdentifierRules.resolve(input)
        if (resolved !is PartyIdentifiers.Resolved) return null to resolved
        val buyerParty = PartyType()
        resolved.buyerLegalRegistrationId?.let { uen ->
            buyerParty.partyLegalEntity.add(PartyLegalEntityType().apply { companyID = company(uen) })
        }
        val supplierParty = PartyType()
        supplierParty.partyLegalEntity.add(PartyLegalEntityType().apply { companyID = company(resolved.supplierId) })
        supplierParty.partyTaxScheme.add(
            PartyTaxSchemeType().apply { companyID = company(resolved.supplierGstn) },
        )
        return Parties(
            buyer = CustomerPartyType().apply { party = buyerParty },
            supplier = SupplierPartyType().apply { party = supplierParty },
            senderId = resolved.senderId,
        ) to resolved
    }

    private fun company(value: String) = CompanyIDType().apply { this.value = value }

    /** Marshals into a DOM built by the hardened factory and serialises it with the hardened transformer factory. */
    fun marshal(
        element: JAXBElement<*>,
        contextClass: Class<*>,
    ): String {
        val document = HardenedXml.documentBuilderFactory().newDocumentBuilder().newDocument()
        JAXBContext.newInstance(contextClass).createMarshaller().marshal(element, document)
        val transformer = HardenedXml.transformerFactory().newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        val out = StringWriter()
        transformer.transform(DOMSource(document), StreamResult(out))
        return out.toString()
    }
}
