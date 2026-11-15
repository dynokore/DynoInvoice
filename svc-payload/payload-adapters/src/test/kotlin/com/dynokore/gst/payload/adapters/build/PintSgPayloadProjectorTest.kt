package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.adapters.projection.MdePackOffNetworkPrefix
import com.dynokore.gst.payload.application.build.BuildInput
import com.dynokore.gst.payload.application.build.BuildNotReady
import com.dynokore.gst.payload.application.build.Projection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** The projector over the real builders of `DocumentBuilderRegistry.standard()`; no database. */
class PintSgPayloadProjectorTest {
    private fun document(typeCode: String = "380") =
        """
        {"documentTypeCode":{"value":"$typeCode","classification":"NONE"},
         "parties":{"value":{
           "supplier":{"identifierScheme":"SG_UEN","identifierValue":"201912345A","name":"Acme"},
           "buyer":{"identifierScheme":"SG_UEN","identifierValue":"201888888A","name":"Buyer"}},"classification":"IDENTIFYING"},
         "documentNumber":{"value":"INV-2026-000123","classification":"NONE"},
         "documentDate":{"value":"2026-03-20","classification":"NONE"},
         "invoiceNote":{"value":"Thanks","classification":"NONE"},
         "identityTriple":{"value":{"senderId":"0195:201912345A","supplierId":"201912345A","supplierGstn":"M2-1234567-8"},
           "classification":"IDENTIFYING"}}
        """.trimIndent()

    private fun input(
        typeCode: String = "380",
        aggregateId: String? = null,
        direction: String? = "SALE",
    ) = BuildInput("E-001", document(typeCode), aggregateId, direction, true)

    private val projector =
        PintSgPayloadProjector({ "PINT_SG" }, { MdePackOffNetworkPrefix("{}") })

    private fun xml(projection: Projection): String = String((projection as Projection.Built).bytes, Charsets.UTF_8)

    @Test
    fun `INV-AC-23006 projector maps buyer supplier and header`() {
        val text = xml(projector.project(input(), "TYPE_2"))
        text shouldContain "INV-2026-000123"
        text shouldContain "2026-03-20"
        text shouldContain "Thanks"
        text shouldContain "201888888A"
    }

    @Test
    fun `INV-AC-23006 projector refuses an aggregate document`() {
        shouldThrow<IllegalStateException> { projector.project(input(aggregateId = "AGG-2026-0001"), "TYPE_2") }
    }

    @Test
    fun `INV-AC-23006 projector blocks an absent direction with its reason`() {
        val blocked = projector.project(input(direction = null), "TYPE_2") as Projection.Blocked
        blocked.validationTrace shouldContain "DIRECTION_ABSENT"
    }

    @Test
    fun `INV-AC-23006 projector blocks a type code without a builder and a refused document`() {
        (projector.project(input(typeCode = "999"), "TYPE_2") as Projection.Blocked).validationTrace shouldContain
            "NO_BUILDER"
        val noTriple = BuildInput("E-001", document().replace("\"identityTriple\"", "\"other\""), null, "SALE", true)
        (projector.project(noTriple, "TYPE_2") as Projection.Blocked).validationTrace shouldContain
            "IDENTITY_TRIPLE_ABSENT"
    }

    @Test
    fun `INV-AC-23006 projector without an active pack is not ready`() {
        shouldThrow<BuildNotReady> { PintSgPayloadProjector({ "PINT_SG" }, { null }).project(input(), "TYPE_2") }
    }
}
