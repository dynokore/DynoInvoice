package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.adapters.projection.MdePackOffNetworkPrefix
import com.dynokore.gst.payload.adapters.projection.TableConformanceGate
import com.dynokore.gst.payload.application.build.BuildInput
import com.dynokore.gst.payload.application.build.Projection
import com.dynokore.gst.payload.domain.CustomizationId
import com.dynokore.gst.payload.domain.CustomizationIdResult
import com.dynokore.gst.payload.domain.Direction
import com.dynokore.gst.payload.domain.ProfileId
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.SubmissionType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import javax.xml.XMLConstants
import javax.xml.namespace.NamespaceContext
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/** INV-T-24266: the projector over `DocumentBuilderRegistry.standard()` with the family SG_PEPPOL_BIS. */
class SgPeppolBisProjectorTest {
    private fun document(typeCode: String) =
        """
        {"documentTypeCode":{"value":"$typeCode","classification":"NONE"},
         "parties":{"value":{
           "supplier":{"identifierScheme":"SG_UEN","identifierValue":"201912345A","name":"Acme"},
           "buyer":{"identifierScheme":"SG_UEN","identifierValue":"201888888A","name":"Buyer"}},"classification":"IDENTIFYING"},
         "documentNumber":{"value":"INV-2026-000123","classification":"NONE"},
         "documentDate":{"value":"2026-03-20","classification":"NONE"},
         "identityTriple":{"value":{"senderId":"0195:201912345A","supplierId":"201912345A","supplierGstn":"M2-1234567-8"},
           "classification":"IDENTIFYING"}}
        """.trimIndent()

    private fun projector(family: String) =
        PintSgPayloadProjector({ family }, { ActivePackRead("TEST", MdePackOffNetworkPrefix("{}"), "{}") })

    private fun project(
        family: String,
        code: String,
    ) = projector(family).project(BuildInput("E-001", document(code), null, "SALE", true), "TYPE_2")

    private fun value(
        xml: String,
        expression: String,
    ): String {
        val ns =
            object : NamespaceContext {
                override fun getNamespaceURI(prefix: String) =
                    when (prefix) {
                        "cbc" -> "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"
                        else -> XMLConstants.NULL_NS_URI
                    }

                override fun getPrefix(namespaceURI: String): String? = null

                override fun getPrefixes(namespaceURI: String): Iterator<String> = emptyList<String>().iterator()
            }
        val xp = XPathFactory.newInstance().newXPath().also { it.namespaceContext = ns }
        return xp.evaluate(expression, TableConformanceGate.parse(xml), XPathConstants.STRING) as String
    }

    @Test
    fun `INV-AC-24265 the projector builds a 380 and a 381 document of the family SG_PEPPOL_BIS`() {
        val selected = CustomizationId.expected(SpecificationFamily.SG_PEPPOL_BIS, SubmissionType.TYPE_2)
        val customization = (selected as CustomizationIdResult.Selected).value
        val profile = ProfileId.expected(Direction.SALE, SpecificationFamily.SG_PEPPOL_BIS)
        for ((code, root) in listOf("380" to "Invoice", "381" to "CreditNote")) {
            val xml = String((project("SG_PEPPOL_BIS", code) as Projection.Built).bytes, Charsets.UTF_8)
            value(xml, "local-name(/*)") shouldBe root
            value(xml, "/*/cbc:CustomizationID") shouldBe customization
            value(xml, "/*/cbc:ProfileID") shouldBe profile
        }
    }

    @Test
    fun `INV-AC-24265 the projector blocks the type code 383 with NO_BUILDER for each family`() {
        for (family in listOf("SG_PEPPOL_BIS", "PINT_SG")) {
            (project(family, "383") as Projection.Blocked).validationTrace shouldContain "NO_BUILDER"
        }
    }
}
