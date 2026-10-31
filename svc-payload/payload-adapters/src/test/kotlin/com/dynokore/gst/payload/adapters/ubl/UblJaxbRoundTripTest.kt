package com.dynokore.gst.payload.adapters.ubl

import com.dynokore.gst.payload.ubl.ubl_21.cbc.CreditNoteTypeCodeType
import com.dynokore.gst.payload.ubl.ubl_21.cbc.IDType
import com.dynokore.gst.payload.ubl.ubl_21.cbc.InvoiceTypeCodeType
import com.dynokore.gst.payload.ubl.ubl_21.cbc.IssueDateType
import com.dynokore.gst.payload.ubl.ubl_21.creditnote.CreditNote
import com.dynokore.gst.payload.ubl.ubl_21.creditnote.CreditNoteType
import com.dynokore.gst.payload.ubl.ubl_21.invoice.Invoice
import com.dynokore.gst.payload.ubl.ubl_21.invoice.InvoiceType
import com.dynokore.platform.integrationcanon.xml.HardenedXml
import io.kotest.matchers.shouldBe
import jakarta.xml.bind.JAXBContext
import jakarta.xml.bind.JAXBException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.StringReader
import java.io.StringWriter
import javax.xml.datatype.DatatypeFactory
import javax.xml.stream.XMLStreamException
import com.dynokore.gst.payload.ubl.ubl_21.creditnote.ObjectFactory as CreditNoteFactory
import com.dynokore.gst.payload.ubl.ubl_21.invoice.ObjectFactory as InvoiceFactory

/** INV-T-7113, INV-Q-7106 A: the generated UBL 2.1 classes, marshalled and read back through the hardened factory. */
class UblJaxbRoundTripTest {
    private val base = "com.dynokore.gst.payload.ubl.ubl_21"

    private fun date() = DatatypeFactory.newInstance().newXMLGregorianCalendar("2026-10-31")

    // Invoice and CreditNote are the generated element classes (a JAXBElement of InvoiceType and CreditNoteType).
    private fun <T> roundTrip(
        element: jakarta.xml.bind.JAXBElement<T>,
        type: Class<T>,
        contextClass: Class<*>,
    ): T {
        val context = JAXBContext.newInstance(contextClass)
        val writer = StringWriter()
        context.createMarshaller().marshal(element, writer)
        val reader = HardenedXml.xmlInputFactory().createXMLStreamReader(StringReader(writer.toString()))
        return context.createUnmarshaller().unmarshal(reader, type).value
    }

    @Test
    fun `the generated Invoice and CreditNote are found by class name in the sub-packages`() {
        Class.forName("$base.invoice.Invoice") shouldBe Invoice::class.java
        Class.forName("$base.creditnote.CreditNote") shouldBe CreditNote::class.java
    }

    @Test
    fun `a minimal invoice round trips equal by field`() {
        val original =
            InvoiceType().apply {
                id = IDType().apply { value = "X1" }
                issueDate = IssueDateType().apply { value = date() }
                invoiceTypeCode = InvoiceTypeCodeType().apply { value = "380" }
            }
        val element = InvoiceFactory().createInvoice(original)
        val back = roundTrip(element, InvoiceType::class.java, InvoiceFactory::class.java)
        back.id.value shouldBe "X1"
        back.issueDate.value shouldBe original.issueDate.value
        back.invoiceTypeCode.value shouldBe "380"
    }

    @Test
    fun `a minimal credit note round trips equal by field`() {
        val original =
            CreditNoteType().apply {
                id = IDType().apply { value = "X1" }
                issueDate = IssueDateType().apply { value = date() }
                creditNoteTypeCode = CreditNoteTypeCodeType().apply { value = "381" }
            }
        val element = CreditNoteFactory().createCreditNote(original)
        val back = roundTrip(element, CreditNoteType::class.java, CreditNoteFactory::class.java)
        back.id.value shouldBe "X1"
        back.issueDate.value shouldBe original.issueDate.value
        back.creditNoteTypeCode.value shouldBe "381"
    }

    @Test
    fun `an input with a document type declaration is refused by the hardened factory`() {
        val xxe =
            """<?xml version="1.0"?><!DOCTYPE Invoice [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>""" +
                """<Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2" """ +
                """xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"><cbc:ID>&xxe;</cbc:ID></Invoice>"""
        val context = JAXBContext.newInstance(InvoiceFactory::class.java)
        val failure =
            assertThrows(Exception::class.java) {
                val reader = HardenedXml.xmlInputFactory().createXMLStreamReader(StringReader(xxe))
                context.createUnmarshaller().unmarshal(reader, InvoiceType::class.java)
            }
        (failure is XMLStreamException || failure is JAXBException) shouldBe true
    }
}
