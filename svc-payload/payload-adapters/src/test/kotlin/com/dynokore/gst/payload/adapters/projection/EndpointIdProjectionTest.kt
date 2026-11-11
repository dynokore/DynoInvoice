package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.Direction
import com.dynokore.gst.payload.domain.EndpointId
import com.dynokore.gst.payload.domain.EndpointIdResult
import com.dynokore.gst.payload.domain.OffNetworkPrefixSource
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.gst.payload.domain.header.DocumentHeaderInput
import com.dynokore.gst.payload.domain.party.IdentityTriple
import com.dynokore.gst.payload.domain.party.PartyIdentifierInput
import com.dynokore.gst.payload.domain.party.PartyInput
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

/** INV-T-7193: IBT-049 in the built document, built from reachability and the pack prefix (INV-REQ-321). */
class EndpointIdProjectionTest {
    private val registry = DocumentBuilderRegistry.standard()
    private val triple = IdentityTriple("0195:201912345A", "201912345A", "M2-1234567-8")
    private val header = DocumentHeaderInput("INV-2026-0042", LocalDate.of(2026, 11, 3))
    private val path = "/Invoice/cac:AccountingCustomerParty/cac:Party/cbc:EndpointID"
    private val fixture: JsonNode = JsonMapper().readTree(resource("/mde/mde-pack-fixture.json"))

    private fun resource(name: String) = (javaClass.getResource(name) ?: error("$name is missing")).readText()

    private fun party(
        scheme: String = "SG_UEN",
        value: String = "201888888A",
    ) = PartyIdentifierInput(PartyInput(scheme, value), PartyInput("0195", "SSC-CENTRE-9"), triple)

    private val fixturePack = "/mde/mde-pack-fixture.json"
    private val changedPack = "/mde/mde-pack-fixture-prefix-changed.json"

    private fun builder(code: String) =
        (registry.find(BuilderKey(SpecificationFamily.PINT_SG, code)) as BuilderLookup.Found).builder

    private fun build(
        code: String,
        type: SubmissionType,
        onNetwork: Boolean?,
        pack: String,
        input: PartyIdentifierInput = party(),
    ): BuildResult =
        builder(code).build(
            input,
            header,
            Direction.SALE,
            SpecificationFamily.PINT_SG,
            type,
            EndpointInputs(onNetwork, MdePackOffNetworkPrefix(resource(pack))),
        )

    private fun texts(
        xml: String,
        code: String,
    ): List<String> {
        val nodes = TableConformanceGate.select(TableConformanceGate.parse(xml), path, code)
        return (0 until nodes.length).map { nodes.item(it).textContent }
    }

    @Test
    fun `INV-AC-1615 an on-network TYPE_1A buyer gives IBT-049 SGUEN201888888A in 380 and 381`() {
        for (code in listOf("380", "381")) {
            val xml = (build(code, SubmissionType.TYPE_1A, true, fixturePack) as BuildResult.Built).xml
            texts(xml, code) shouldBe listOf("SGUEN201888888A")
            TableConformanceGate.check(
                xml,
                code,
                TableConformanceGate.applyingRows(fixture, code, "PINT-SG-1.0"),
            ) shouldBe
                emptyList()
        }
    }

    @Test
    fun `INV-AC-1616 an off-network TYPE_2 buyer gets the pack prefix and the builder result has no hold member`() {
        for (code in listOf("380", "381")) {
            val result = build(code, SubmissionType.TYPE_2, false, fixturePack)
            texts((result as BuildResult.Built).xml, code) shouldBe listOf("C5UID201888888A")
        }
        EndpointId.construct(false, SubmissionType.TYPE_2, "SG_UEN", "201888888A", "C5UID") shouldBe
            EndpointIdResult.Built("C5UID201888888A")
        BuildResult::class.sealedSubclasses.map { it.simpleName }.none {
            it!!.contains(
                "Hold",
                ignoreCase = true,
            )
        } shouldBe
            true
    }

    @Test
    fun `INV-AC-1617 a pack with another prefix changes IBT-049 with the same classes and no other byte`() {
        val first = (build("380", SubmissionType.TYPE_2, false, fixturePack) as BuildResult.Built).xml
        val second =
            (build("380", SubmissionType.TYPE_2, false, changedPack) as BuildResult.Built).xml
        texts(first, "380") shouldBe listOf("C5UID201888888A")
        texts(second, "380") shouldBe listOf("XXUID201888888A")
        first.replace("C5UID", "<P>") shouldBe second.replace("XXUID", "<P>")
        EndpointId::class.java shouldBe Class.forName("com.dynokore.gst.payload.domain.EndpointId")
        // one builder object, two sources
        val one = builder("380")
        val inputs = { pack: String -> EndpointInputs(false, MdePackOffNetworkPrefix(resource(pack))) }
        val a =
            one.build(
                party(),
                header,
                Direction.SALE,
                SpecificationFamily.PINT_SG,
                SubmissionType.TYPE_2,
                inputs(fixturePack),
            )
        val b =
            one.build(
                party(),
                header,
                Direction.SALE,
                SpecificationFamily.PINT_SG,
                SubmissionType.TYPE_2,
                inputs(changedPack),
            )
        (a as BuildResult.Built).xml shouldBe first
        (b as BuildResult.Built).xml shouldBe second
    }

    @Test
    fun `EndpointId builder refusals carry the result and no XML`() {
        val pack = fixturePack
        for (code in listOf("380", "381")) {
            build(code, SubmissionType.TYPE_2, null, pack) shouldBe
                BuildResult.EndpointRefused(EndpointIdResult.ReachabilityUnknown)
            build(code, SubmissionType.TYPE_1A, false, pack) shouldBe
                BuildResult.EndpointRefused(EndpointIdResult.Unpublished(SubmissionType.TYPE_1A))
            build(code, SubmissionType.TYPE_2, false, pack, party("FOREIGN_REG")) shouldBe
                BuildResult.EndpointRefused(EndpointIdResult.NoUenToAppend)
            build(code, SubmissionType.TYPE_2, false, "/mde/mde-pack-no-prefix.json") shouldBe
                BuildResult.EndpointRefused(EndpointIdResult.PrefixNotConfigured)
        }
    }

    @Test
    fun `EndpointId refusal order is identity triple then Customization ID then endpoint`() {
        val pack = EndpointInputs(null, MdePackOffNetworkPrefix("{}"))
        val builder = builder("380")
        val noTriple = PartyIdentifierInput(PartyInput("FOREIGN_REG", "X"), PartyInput("0195", "S"), null)
        builder
            .build(noTriple, header, Direction.SALE, SpecificationFamily.PINT_SG, SubmissionType.TYPE_2, pack)
            .shouldBe(BuildResult.Refused(com.dynokore.gst.payload.domain.party.RefusalReason.IDENTITY_TRIPLE_ABSENT))
        // an unpublished Customization ID pair is refused before the endpoint (which would be ReachabilityUnknown)
        val result =
            builder.build(
                party(),
                header,
                Direction.SALE,
                SpecificationFamily.PINT_SG,
                SubmissionType.TYPE_1B,
                pack,
            )
        (result is BuildResult.CustomizationIdUnpublished) shouldBe true
    }

    @Test
    fun `EndpointId an absent offNetworkEndpoint gives a null prefix and a bad prefix is a parse failure`() {
        MdePackOffNetworkPrefix("{\"rows\": []}").prefix() shouldBe null
        MdePackOffNetworkPrefix(resource(fixturePack)).prefix() shouldBe "C5UID"
        for (bad in listOf("\"\"", "\"  \"", "5", "null", "{}")) {
            shouldThrow<OffNetworkPrefixParseException> {
                MdePackOffNetworkPrefix("{\"offNetworkEndpoint\": {\"prefix\": $bad}}").prefix()
            }
        }
        shouldThrow<OffNetworkPrefixParseException> { MdePackOffNetworkPrefix("{\"offNetworkEndpoint\": {}}").prefix() }
    }

    @Test
    fun `EndpointId the prefix source is read on each call, never cached`() {
        var json = "{\"offNetworkEndpoint\": {\"prefix\": \"AAA\"}}"
        val source: OffNetworkPrefixSource = MdePackOffNetworkPrefix(json)
        source.prefix() shouldBe "AAA"
        MdePackOffNetworkPrefix("{\"offNetworkEndpoint\": {\"prefix\": \"BBB\"}}").prefix() shouldBe "BBB"
        json = ""
        source.prefix() shouldBe "AAA"
    }
}
