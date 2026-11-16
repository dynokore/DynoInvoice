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
        PintSgPayloadProjector({ "PINT_SG" }, { ActivePackRead("TEST", MdePackOffNetworkPrefix("{}"), "{}") })

    private fun xml(projection: Projection): String = String((projection as Projection.Built).bytes, Charsets.UTF_8)

    @Test
    fun `INV-AC-23006 projector maps buyer supplier and header`() {
        val text = xml(projector.project(input(), "TYPE_2"))
        text shouldContain "INV-2026-000123"
        text shouldContain "2026-03-20"
        text shouldContain "Thanks"
        text shouldContain "201888888A"
    }

    private fun entry(
        type: String,
        term: String = "IBT-022",
    ) = """{"aggregateType":"$type","businessTermId":"$term","literal":"$type"}"""

    private val fullPack = "{\"aggregateDefaults\":[${entry("POS")},${entry("STI")},${entry("PCP")}]}"

    private fun aggregate(
        type: String?,
        pack: String = fullPack,
    ): Projection {
        val doc =
            if (type == null) document() else document().replaceFirst("{", "{\"aggregateType\":{\"value\":\"$type\"},")
        val p = PintSgPayloadProjector({ "PINT_SG" }, { ActivePackRead("TEST", MdePackOffNetworkPrefix("{}"), pack) })
        return p.project(BuildInput("E-001", doc, "AGG-2026-0001", "SALE", true), "TYPE_2")
    }

    private fun notes(text: String): List<String> =
        Regex("<(?:\\w+:)?Note(?:\\s[^>]*)?>([^<]*)</").findAll(text).map { it.groupValues[1] }.toList()

    private fun assertNote(
        projection: Projection,
        literal: String,
    ) {
        val text = xml(projection)
        notes(text) shouldBe listOf(literal)
    }

    @Test
    fun `INV-AC-23492 POS aggregate gets the literal POS as its note`() = assertNote(aggregate("POS"), "POS")

    @Test
    fun `INV-AC-23492 STI aggregate gets the literal STI as its note`() = assertNote(aggregate("STI"), "STI")

    @Test
    fun `INV-AC-23492 PCP aggregate gets the literal PCP as its note`() = assertNote(aggregate("PCP"), "PCP")

    @Test
    fun `INV-AC-23492 pack without an entry blocks the build`() {
        val noSti = "{\"aggregateDefaults\":[${entry("POS")}]}"
        (aggregate("STI", noSti) as Projection.Blocked).validationTrace shouldContain "AGGREGATE_LITERAL_ABSENT"
        val twice = "{\"aggregateDefaults\":[${entry("POS")},${entry("POS")}]}"
        (aggregate("POS", twice) as Projection.Blocked).validationTrace shouldContain "AGGREGATE_LITERAL_INVALID"
    }

    @Test
    fun `INV-AC-23492 other business term blocks the build`() {
        val other = "{\"aggregateDefaults\":[${entry("STI", "IBT-023")}]}"
        (aggregate("STI", other) as Projection.Blocked).validationTrace shouldContain
            "AGGREGATE_LITERAL_TERM_UNSUPPORTED"
        val wrongLiteral =
            "{\"aggregateDefaults\":[{\"aggregateType\":\"POS\",\"businessTermId\":\"IBT-022\",\"literal\":\"STI\"}]}"
        (aggregate("POS", wrongLiteral) as Projection.Blocked).validationTrace shouldContain "AGGREGATE_LITERAL_INVALID"
    }

    @Test
    fun `INV-AC-23492 aggregate without aggregate type blocks the build`() {
        (aggregate(null) as Projection.Blocked).validationTrace shouldContain "AGGREGATE_TYPE_ABSENT"
    }

    @Test
    fun `INV-AC-23492 ordinary invoice keeps its own note`() {
        val text =
            xml(
                PintSgPayloadProjector(
                    { "PINT_SG" },
                    { ActivePackRead("TEST", MdePackOffNetworkPrefix("{}"), fullPack) },
                ).project(input(), "TYPE_2"),
            )
        notes(text) shouldBe listOf("Thanks")
    }

    @Test
    fun `INV-AC-23006 projector blocks a buyer outside the value member`() {
        val flat =
            """
            {"documentTypeCode":{"value":"380"},"parties":{"buyer":{"identifierScheme":"SG_UEN","identifierValue":"201888888A"},
             "supplier":{"identifierScheme":"SG_UEN","identifierValue":"201912345A"}},
             "documentNumber":{"value":"N1"},"documentDate":{"value":"2026-03-20"},
             "identityTriple":{"value":{"senderId":"0195:201912345A","supplierId":"201912345A","supplierGstn":"M2-1234567-8"}}}
            """.trimIndent()
        val blocked = projector.project(BuildInput("E-001", flat, null, "SALE", true), "TYPE_2") as Projection.Blocked
        blocked.validationTrace shouldContain "PARTY_IDENTIFIER_ABSENT"
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
