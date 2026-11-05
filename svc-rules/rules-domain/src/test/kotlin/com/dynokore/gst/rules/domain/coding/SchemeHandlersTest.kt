package com.dynokore.gst.rules.domain.coding

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.pack.MappingSignOff
import com.dynokore.gst.rules.domain.pack.PackEntry
import com.dynokore.gst.rules.domain.pack.SourceClass
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The scheme handlers over invented pack data: every decision is read from [scenario], none is a constant. */
class SchemeHandlersTest {
    private fun cat(
        code: String,
        side: String,
    ) = CatalogueEntry(code, "G", side, "9", "0", SourceClass.PRIMARY, "invented")

    private val catalogue =
        CodeCatalogue(
            true,
            listOf(
                cat("SR", "SUPPLY"),
                cat("ZR", "SUPPLY"),
                cat("NA", "SUPPLY"),
                cat("TXNA", "PURCHASE"),
                cat("TX", "PURCHASE"),
                cat("ZP", "PURCHASE"),
            ),
        )

    private fun side(
        tag: String,
        s: Side,
        h: Handling = Handling.HANDLED,
    ) = SchemeTagSide(tag, s, h)

    @Suppress("LongParameterList") // the members of one invented treatment row
    private fun t(
        tag: String,
        s: Side,
        code: String,
        under: Set<String>,
        rate: String? = "0",
        agreement: String? = null,
        statement: String? = null,
    ) = Treatment(
        tag,
        s,
        code,
        if (rate == null) RateBasis.PREVAILING else RateBasis.FIXED,
        rate,
        under,
        agreement,
        statement,
        "INFERRED",
        "invented",
    )

    private val a3pl = "Payment of GST is not required under the Approved 3PL Company Scheme"
    private val amcs = "Supplies zero-rated under the Approved Marine Customer Scheme"
    private val arcs = "Goods supplied under ARCS"

    private val scenario =
        ScenarioData(
            SelfBillingAssignment(Direction.PURCHASE, SubmissionType.TYPE_3),
            SelfBillingAssignment(Direction.SALE, SubmissionType.TYPE_2),
            listOf(
                side("A3PL", Side.SUPPLY),
                side("A3PL", Side.PURCHASE),
                side("AMCS", Side.SUPPLY),
                side("AMCS", Side.PURCHASE),
                side("ARCS", Side.SUPPLY),
                side("ARCS", Side.PURCHASE),
                side("ACMT", Side.SUPPLY),
                side("MES", Side.SUPPLY),
                side("SWS", Side.SUPPLY),
                side("IGDS", Side.SUPPLY),
                side("AISS", Side.SUPPLY),
                side("CASH_ACCOUNTING", Side.SUPPLY),
                side("CASH_ACCOUNTING", Side.PURCHASE, Handling.PENDING_QUESTION),
                side("ACMT_RECIPIENT_ACCOUNTING", Side.SUPPLY, Handling.PENDING_QUESTION),
                side("ACMT_OVERSEAS_CLIENT_PURCHASE", Side.PURCHASE, Handling.PENDING_QUESTION),
                side("AMCS_SELF_ACCOUNTING", Side.SUPPLY, Handling.PENDING_QUESTION),
            ),
            listOf(
                t("A3PL", Side.SUPPLY, "NA", setOf("MES", "AISS", "ACMT", "A3PL"), statement = a3pl),
                t("A3PL", Side.PURCHASE, "TXNA", setOf("A3PL"), statement = a3pl),
                t("AMCS", Side.SUPPLY, "ZR", setOf("AMCS"), statement = amcs),
                t("AMCS", Side.PURCHASE, "ZP", setOf("AMCS"), statement = amcs),
                t("ARCS", Side.SUPPLY, "NA", setOf("ARCS"), agreement = "NOT_AGREED", statement = arcs),
                t("ARCS", Side.SUPPLY, "SR", setOf("ARCS"), rate = null, agreement = "AGREED"),
                t("ARCS", Side.PURCHASE, "TXNA", setOf("ARCS"), agreement = "NOT_AGREED", statement = arcs),
                t("ARCS", Side.PURCHASE, "TX", setOf("ARCS"), rate = null, agreement = "AGREED"),
            ),
        )

    private val mapping =
        listOf(
            MappingEntry("A3S", "NA", "A3PL"),
            MappingEntry("A3P", "TXNA", "A3PL"),
            MappingEntry("A3P-TX", "TX", "A3PL"),
            MappingEntry("AMS", "ZR", "AMCS"),
            MappingEntry("AMP", "ZP", "AMCS"),
            MappingEntry("AMSELF", "ZP", "AMCS_SELF_ACCOUNTING"),
            MappingEntry("ARS-NA", "NA", "ARCS"),
            MappingEntry("ARS-SR", "SR", "ARCS"),
            MappingEntry("ACR", "SR", "ACMT_RECIPIENT_ACCOUNTING"),
            MappingEntry("ACOC", "TX", "ACMT_OVERSEAS_CLIENT_PURCHASE"),
            MappingEntry("ACDA", "SR", "ACMT"),
            MappingEntry("CAP", "TX", "CASH_ACCOUNTING"),
            MappingEntry("CAS", "SR", "CASH_ACCOUNTING"),
            MappingEntry("MESS", "SR", "MES"),
            MappingEntry("SWSS", "ZR", "SWS"),
            MappingEntry("IGDSS", "SR", "IGDS"),
            MappingEntry("AISSS", "SR", "AISS"),
            MappingEntry("MESX", "SR", "MESS"),
            MappingEntry("SWSXX", "ZR", "SWSX"),
            MappingEntry("MESL", "SR", "mes"),
            MappingEntry("PLAIN", "SR", null),
        )

    private val coder = LineCoder(mapping, catalogue, scenario)
    private val purchase = Direction.PURCHASE

    private fun coded(
        code: String,
        rate: String?,
        statement: String?,
        prevailing: Boolean = false,
    ) = LineCoding.Coded(code, rate, prevailing, statement)

    @Test
    fun `INV-AC-2451 an A3PL supply to a customer with MES status is NA at rate 0 with the statement`() {
        coder.code(LineInput("A3S", elections = setOf("A3PL"), counterpartyApprovedSchemes = setOf("MES"))) shouldBe
            coded("NA", "0", a3pl)
    }

    @Test
    fun `INV-AC-2452 an A3PL supply with no status or an ended status is RUL-011 and never SR or NA`() {
        listOf<Set<String>?>(null, emptySet()).forEach {
            coder.code(LineInput("A3S", elections = setOf("A3PL"), counterpartyApprovedSchemes = it)) shouldBe
                LineCoding.Refused(ErrorCode.RUL_011)
        }
    }

    @Test
    fun `INV-AC-2453 an MES entity without A3PL election codes the purchase TXNA at rate 0 with the statement`() {
        coder.code(
            LineInput("A3P", purchase, elections = setOf("MES"), counterpartyApprovedSchemes = setOf("A3PL")),
        ) shouldBe coded("TXNA", "0", a3pl)
    }

    @Test
    fun `INV-AC-2536 an A3PL purchase from a supplier with no A3PL status is RUL-011 and not TXNA or TX`() {
        listOf("A3P", "A3P-TX").forEach {
            coder.code(LineInput(it, purchase, counterpartyApprovedSchemes = null)) shouldBe
                LineCoding.Refused(ErrorCode.RUL_011)
            coder.code(LineInput(it, purchase, counterpartyApprovedSchemes = setOf("MES"))) shouldBe
                LineCoding.Refused(ErrorCode.RUL_011)
        }
    }

    @Test
    fun `INV-AC-2454 an AMCS supply is ZR and the customer's purchase is ZP, each with the statement`() {
        coder.code(LineInput("AMS", elections = setOf("AMCS"), counterpartyApprovedSchemes = setOf("AMCS"))) shouldBe
            coded("ZR", "0", amcs)
        coder.code(
            LineInput("AMP", purchase, elections = setOf("AMCS"), counterpartyApprovedSchemes = setOf("AMCS")),
        ) shouldBe coded("ZP", "0", amcs)
    }

    @Test
    fun `INV-AC-2537 an AMCS supply to a customer whose status ended is RUL-011 with no code and no statement`() {
        coder.code(LineInput("AMS", elections = setOf("AMCS"), counterpartyApprovedSchemes = emptySet())) shouldBe
            LineCoding.Refused(ErrorCode.RUL_011)
    }

    @Test
    fun `INV-AC-2456 an ARCS supply with no agreement is NA at rate 0 and the INFERRED entry blocks sign-off`() {
        val arcsIn = LineInput("ARS-NA", elections = setOf("ARCS"), counterpartyApprovedSchemes = setOf("ARCS"))
        coder.code(arcsIn) shouldBe coded("NA", "0", arcs)
        coder.code(arcsIn.copy(internalTaxCode = "ARS-SR")) shouldBe coded("NA", "0", arcs)
        scenario.treatments.first { it.writtenAgreement == "NOT_AGREED" }.sourceClass shouldBe "INFERRED"
        val entry = PackEntry("arcs-waiver", "invented", SourceClass.INFERRED)
        MappingSignOff.attempt(listOf(entry)) shouldBe
            MappingSignOff.Outcome.Blocked(ErrorCode.RUL_007, listOf("arcs-waiver"))
        MappingSignOff.attempt(listOf(entry), setOf("arcs-waiver")) shouldBe MappingSignOff.Outcome.SignedOff
    }

    @Test
    fun `INV-AC-2457 with the flag set NA is RUL-011, SR is coded and a line override takes the waiver`() {
        val base = LineInput("ARS-SR", elections = setOf("ARCS"), counterpartyApprovedSchemes = setOf("ARCS"))
        val flagged = base.copy(counterpartyWrittenAgreement = "AGREED")
        coder.code(flagged.copy(internalTaxCode = "ARS-NA")) shouldBe LineCoding.Refused(ErrorCode.RUL_011)
        coder.code(flagged) shouldBe coded("SR", null, null, prevailing = true)
        coder.code(flagged.copy(writtenAgreement = "NOT_AGREED")) shouldBe coded("NA", "0", arcs)
        coder.code(base.copy(writtenAgreement = "AGREED")) shouldBe coded("SR", null, null, prevailing = true)
    }

    @Test
    fun `INV-AC-2518 a released sub-flow row compares the election with its parentScheme`() {
        val released =
            scenario.copy(
                schemeTagSides =
                    scenario.schemeTagSides.filter { it.tag != "ACMT_RECIPIENT_ACCOUNTING" } +
                        SchemeTagSide("ACMT_RECIPIENT_ACCOUNTING", Side.SUPPLY, Handling.HANDLED, "ACMT"),
            )
        val c = LineCoder(mapping, catalogue, released)
        c.code(LineInput("ACR", elections = setOf("AMCS"))) shouldBe LineCoding.Refused(ErrorCode.RUL_004)
        c.code(LineInput("ACR", elections = setOf("ACMT_RECIPIENT_ACCOUNTING"))) shouldBe LineCoding.Refused(ErrorCode.RUL_004)
        c.code(LineInput("ACR", elections = setOf("ACMT"))) shouldBe coded("SR", null, null)
    }

    @Test
    fun `INV-AC-2453 the recipient side of A3PL alone needs no election`() {
        val s = setOf("A3PL")
        coder.code(LineInput("A3P", purchase, counterpartyApprovedSchemes = s)) shouldBe
            coded("TXNA", "0", a3pl)
        coder.code(LineInput("A3S", counterpartyApprovedSchemes = setOf("MES"))) shouldBe
            LineCoding.Refused(ErrorCode.RUL_004)
        // look-alike tags on the purchase side still need the election
        val lookAlikes =
            LineCoder(
                listOf(MappingEntry("L1", "TXNA", "A3PL2"), MappingEntry("L2", "TXNA", "a3pl")),
                catalogue,
                scenario.copy(
                    schemeTagSides =
                        scenario.schemeTagSides + side("A3PL2", Side.PURCHASE) + side("a3pl", Side.PURCHASE),
                ),
            )
        listOf("L1", "L2").forEach {
            val (coding, trace) = lookAlikes.codeWithTrace(LineInput(it, purchase))
            coding shouldBe LineCoding.Refused(ErrorCode.RUL_004)
            trace["outcome"] shouldBe "SCHEME_NOT_ELECTED"
        }
    }

    @Test
    fun `INV-AC-2538 an NA line under ARCS to a counterparty with no ARCS status is RUL-011`() {
        listOf<Set<String>?>(emptySet(), setOf("MES"), null).forEach {
            coder.code(LineInput("ARS-NA", elections = setOf("ARCS"), counterpartyApprovedSchemes = it)) shouldBe
                LineCoding.Refused(ErrorCode.RUL_011)
        }
    }

    @Test
    fun `INV-AC-2458 and INV-AC-2515 an ACMT recipient-accounting line is RUL-004 for an ACMT entity`() {
        coder.code(LineInput("ACR", elections = setOf("ACMT"))) shouldBe LineCoding.Refused(ErrorCode.RUL_004)
    }

    @Test
    fun `INV-AC-2534 an ACMT overseas-client purchase is RUL-004 pending the open question`() {
        coder.code(LineInput("ACOC", purchase, elections = setOf("ACMT"))) shouldBe LineCoding.Refused(ErrorCode.RUL_004)
    }

    @Test
    fun `INV-AC-2516 an AMCS self-accounting line is RUL-004 for an AMCS entity`() {
        coder.code(LineInput("AMSELF", elections = setOf("AMCS"))) shouldBe LineCoding.Refused(ErrorCode.RUL_004)
    }

    @Test
    fun `INV-AC-2533 an ACMT distributing agent's local sale is an ordinary SR with no RUL-004`() {
        coder.code(LineInput("ACDA", elections = setOf("ACMT"))) shouldBe coded("SR", null, null)
    }

    @Test
    fun `INV-AC-2302 and INV-AC-2460 a Cash Accounting purchase is RUL-004 and its supply follows the general rules`() {
        coder.code(LineInput("CAP", purchase, elections = setOf("CASH_ACCOUNTING"))) shouldBe
            LineCoding.Refused(ErrorCode.RUL_004)
        coder.code(LineInput("CAS", elections = setOf("CASH_ACCOUNTING"))) shouldBe coded("SR", null, null)
    }

    @Test
    fun `INV-AC-2460 a line tagged AMCS from an entity that has not elected AMCS is RUL-004`() {
        listOf(null, "ARCS", "AMCS2", "amcs").forEach {
            coder.code(LineInput("AMS", elections = setOfNotNull(it), counterpartyApprovedSchemes = setOf("AMCS"))) shouldBe
                LineCoding.Refused(ErrorCode.RUL_004)
        }
    }

    @Test
    fun `INV-AC-2518 a tagged line from an entity that elects no scheme is RUL-004 and the trace names the scheme`() {
        val (coding, trace) = coder.codeWithTrace(LineInput("MESS"))
        coding shouldBe LineCoding.Refused(ErrorCode.RUL_004)
        trace["handler"] shouldBe "MES"
        trace["outcome"] shouldBe "SCHEME_NOT_ELECTED"
    }

    @Test
    fun `INV-AC-2499 a scheme line whose counterparty status is not recorded is RUL-011`() {
        coder.code(LineInput("AMS", elections = setOf("AMCS"), counterpartyApprovedSchemes = null)) shouldBe
            LineCoding.Refused(ErrorCode.RUL_011)
    }

    @Test
    fun `INV-AC-2517 and INV-AC-2449 SWS is an ordinary ZR supply and MES adds no data`() {
        coder.code(LineInput("SWSS", elections = setOf("SWS"))) shouldBe coded("ZR", null, null)
        coder.code(LineInput("MESS", elections = setOf("MES"))) shouldBe coded("SR", null, null)
    }

    @Test
    fun `INV-REQ-2447 the trace names the handler, the pack entry with its source class and the counterparty data`() {
        val (_, trace) =
            coder.codeWithTrace(LineInput("A3S", elections = setOf("A3PL"), counterpartyApprovedSchemes = setOf("MES", "A3PL")))
        trace["handler"] shouldBe "A3PL"
        trace["entrySourceClass"] shouldBe "INFERRED"
        trace["entrySourceReference"] shouldBe "invented"
        trace["counterpartyApprovedSchemes"] shouldBe "A3PL,MES"
    }

    @Test
    fun `INV-AC-2502 the SWS tag on the purchase code ZP is refused and the A3PL tag on TXNA is accepted`() {
        val sws = scenario.copy(schemeTagSides = scenario.schemeTagSides + side("SWS", Side.SUPPLY))
        SchemeTagAuthoring.check(MappingEntry("X", "ZP", "SWS"), catalogue, sws) shouldBe
            SchemeTagCheck.Refused("SWS", Side.PURCHASE)
        SchemeTagAuthoring.check(MappingEntry("X", "ZR", "SWS"), catalogue, sws) shouldBe SchemeTagCheck.Accepted
        SchemeTagAuthoring.check(MappingEntry("X", "ZP", null), catalogue, sws) shouldBe SchemeTagCheck.Accepted
        // deny by default: a tag no row lists and a code the catalogue lacks
        SchemeTagAuthoring.check(MappingEntry("X", "ZR", "NEWSCHEME"), catalogue, sws) shouldBe
            SchemeTagCheck.Refused("NEWSCHEME", Side.SUPPLY)
        SchemeTagAuthoring.check(MappingEntry("X", "QQ", "SWS"), catalogue, sws) shouldBe
            SchemeTagCheck.Refused("SWS", null)
        // look-alikes of a declared tag are different tags: exact equality, never prefix or case folding
        SchemeTagAuthoring.check(MappingEntry("X", "ZR", "SWSX"), catalogue, sws) shouldBe
            SchemeTagCheck.Refused("SWSX", Side.SUPPLY)
        SchemeTagAuthoring.check(MappingEntry("X", "ZR", "sws"), catalogue, sws) shouldBe
            SchemeTagCheck.Refused("sws", Side.SUPPLY)
    }

    @Test
    fun `INV-AC-2535 the A3PL tag declared for both sides is accepted on the purchase code TXNA`() {
        SchemeTagAuthoring.check(MappingEntry("X", "TXNA", "A3PL"), catalogue, scenario) shouldBe
            SchemeTagCheck.Accepted
        SchemeTagAuthoring.check(MappingEntry("X", "NA", "A3PL"), catalogue, scenario) shouldBe SchemeTagCheck.Accepted
    }

    private fun outcomeOf(
        code: String,
        vararg members: String,
    ) = coder.codeWithTrace(LineInput(code, elections = members.toSet())).let { it.first to it.second["outcome"] }

    @Test
    fun `INV-AC-2536 INV-Q-16860 the election check applies to MES IGDS AISS and SWS too`() {
        listOf("MESS" to ("MES" to "SR"), "IGDSS" to ("IGDS" to "SR"), "AISSS" to ("AISS" to "SR"), "SWSS" to ("SWS" to "ZR"))
            .forEach { (line, scheme) ->
                val (tag, plainCode) = scheme
                outcomeOf(line, tag) shouldBe (coded(plainCode, null, null) to "GENERAL_RULES")
                outcomeOf(line) shouldBe (LineCoding.Refused(ErrorCode.RUL_004) to "SCHEME_NOT_ELECTED")
                val other = if (tag == "SWS") "MES" else "SWS"
                outcomeOf(line, other) shouldBe (LineCoding.Refused(ErrorCode.RUL_004) to "SCHEME_NOT_ELECTED")
            }
    }

    @Test
    fun `INV-AC-2536 look-alike tags MESS SWSX and mes are not listed by the pack and are blocked as FLOW_BLOCKED`() {
        listOf("MESX", "SWSXX", "MESL").forEach {
            outcomeOf(it, "MES", "SWS", "MESS", "SWSX", "mes") shouldBe (LineCoding.Refused(ErrorCode.RUL_004) to "FLOW_BLOCKED")
        }
    }

    @Test
    fun `INV-AC-2536 a line without a tag is unaffected by any set`() {
        outcomeOf("PLAIN") shouldBe (coded("SR", null, null) to null)
        outcomeOf("PLAIN", "ACMT", "ARCS") shouldBe (coded("SR", null, null) to null)
    }

    @Test
    fun `INV-AC-2537 the elected set decides the ARCS handler and look-alike members are not members`() {
        fun arcsLine(vararg members: String) =
            coder.code(
                LineInput("ARS-NA", elections = members.toSet(), counterpartyApprovedSchemes = setOf("ARCS")),
            )
        arcsLine("ACMT", "ARCS") shouldBe coded("NA", "0", arcs)
        arcsLine("ACMT") shouldBe LineCoding.Refused(ErrorCode.RUL_004)
        arcsLine() shouldBe LineCoding.Refused(ErrorCode.RUL_004)
        arcsLine("arcs") shouldBe LineCoding.Refused(ErrorCode.RUL_004)
        arcsLine("ARCS ") shouldBe LineCoding.Refused(ErrorCode.RUL_004)
    }

    @Test
    fun `INV-AC-2537 the trace names the set sorted and comma-joined and the property that supplied it`() {
        val line =
            LineInput(
                "ARS-NA",
                elections = setOf("ARCS", "ACMT"),
                electionsSource = ELECTED_SCHEMES,
                counterpartyApprovedSchemes = setOf("ARCS"),
            )
        val trace = coder.codeWithTrace(line).second
        trace["elections"] shouldBe "ACMT,ARCS"
        trace["electionsSource"] shouldBe "electedSchemes"
        coder.codeWithTrace(LineInput("MESS")).second["electionsSource"] shouldBe "none"
    }
}
