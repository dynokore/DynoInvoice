package com.dynokore.gst.rules.domain.coding

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.catalogue.Severity
import com.dynokore.gst.rules.domain.pack.SourceClass
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class LineCoderTest {
    private fun entry(code: String) =
        CatalogueEntry(code, "SUPPLY_STANDARD_RATED", "SUPPLY", "9", null, SourceClass.PRIMARY, "Annex E p.79")

    private fun catalogue(
        complete: Boolean,
        vararg codes: String,
    ) = CodeCatalogue(complete, codes.map(::entry))

    private val selfBilling =
        ScenarioData(
            customerCumIssuer = SelfBillingAssignment(Direction.PURCHASE, SubmissionType.TYPE_3),
            supplierCumRecipient = SelfBillingAssignment(Direction.SALE, SubmissionType.TYPE_2),
            schemeTagSides =
                listOf(
                    SchemeTagSide("CUSTOMER_ACCOUNTING", Side.PURCHASE, Handling.HANDLED),
                    SchemeTagSide("A3PL", Side.SUPPLY, Handling.HANDLED),
                    SchemeTagSide("CASH_ACCOUNTING", Side.PURCHASE, Handling.PENDING_QUESTION),
                ),
            treatments =
                listOf(
                    Treatment(
                        "CUSTOMER_ACCOUNTING",
                        Side.PURCHASE,
                        "TXCA",
                        RateBasis.PREVAILING,
                        null,
                        emptySet(),
                        null,
                        null,
                    ),
                    Treatment("A3PL", Side.SUPPLY, "NA", RateBasis.FIXED, "0", setOf("A3PL"), null, "A3PL supply."),
                ),
        )

    private val full = catalogue(true, "SR", "ZR", "NA", "TXCA", "SRCA-C")

    private fun coder(
        catalogue: CodeCatalogue = full,
        scenario: ScenarioData = selfBilling,
        vararg mapping: MappingEntry,
    ) = LineCoder(mapping.toList(), catalogue, scenario)

    @Test
    fun `INV-REQ-364 an unmapped code is RUL-001 BLOCK whatever the completeness flag says`() {
        listOf(true, false).forEach { complete ->
            val answer =
                coder(catalogue(complete, "SR"), mapping = arrayOf(MappingEntry("SR-STD", "SR")))
                    .code(LineInput("X-NEW"))
            answer shouldBe LineCoding.Refused(ErrorCode.RUL_001)
            (answer as LineCoding.Refused).severity shouldBe Severity.BLOCK
        }
    }

    @Test
    fun `INV-REQ-364 a refused line does not touch a mapped line and a repeat gives the same refusal`() {
        val c = coder(mapping = arrayOf(MappingEntry("SR-STD", "SR")))
        val first = c.code(LineInput("X-NEW"))
        c.code(LineInput("SR-STD")) shouldBe LineCoding.Coded("SR")
        c.code(LineInput("X-NEW")) shouldBe first
    }

    @Test
    fun `INV-REQ-364 a mapped code absent from the catalogue is refused with RUL-006`() {
        val c = coder(mapping = arrayOf(MappingEntry("Q", "SRX")))
        c.code(LineInput("Q")) shouldBe LineCoding.Refused(ErrorCode.RUL_006)
    }

    @Test
    fun `INV-REQ-2429 a code the catalogue gains in a later version is accepted by the same coder class`() {
        val mapping = arrayOf(MappingEntry("N", "NEWX"))
        val before = coder(catalogue(true, "SR"), mapping = mapping)
        before.code(LineInput("N")) shouldBe LineCoding.Refused(ErrorCode.RUL_006)
        coder(catalogue(true, "SR", "NEWX"), mapping = mapping).code(LineInput("N")) shouldBe LineCoding.Coded("NEWX")
    }

    @Test
    fun `INV-REQ-363 each line is coded on its own and in either order the same`() {
        val c = coder(mapping = arrayOf(MappingEntry("A", "SR"), MappingEntry("B", "SR"), MappingEntry("C", "ZR")))
        val forward = listOf("A", "B", "C").map { c.code(LineInput(it)) }
        val reverse = listOf("C", "B", "A").map { c.code(LineInput(it)) }.reversed()
        forward shouldBe reverse
        forward.map { (it as LineCoding.Coded).categoryCode } shouldBe listOf("SR", "SR", "ZR")
    }

    @Test
    fun `INV-REQ-365 two sides are coded independently with their own mapping and the divergence raises nothing`() {
        val supplier = coder(mapping = arrayOf(MappingEntry("T", "SR")))
        val customer = coder(catalogue(true, "TX"), mapping = arrayOf(MappingEntry("T", "TX")))
        supplier.code(LineInput("T")) shouldBe LineCoding.Coded("SR")
        customer.code(LineInput("T")) shouldBe LineCoding.Coded("TX")
        supplier.code(LineInput("T")) shouldBe LineCoding.Coded("SR")
    }

    @Test
    fun `INV-REQ-495 the customer side of customer accounting is TXCA and SRCA-C stays a valid supply code`() {
        val c =
            coder(
                mapping =
                    arrayOf(
                        MappingEntry("CA-IN", "TXCA", "CUSTOMER_ACCOUNTING"),
                        MappingEntry("CA-OUT", "SRCA-C"),
                    ),
            )
        val purchase = c.code(LineInput("CA-IN", Direction.PURCHASE, election = "VP-53")) as LineCoding.Coded
        purchase.categoryCode shouldBe "TXCA"
        purchase.prevailingRate shouldBe true
        c.code(LineInput("CA-OUT")) shouldBe LineCoding.Coded("SRCA-C")
    }

    @Test
    fun `INV-REQ-416 the self-billing party is chosen by direction from the pack data`() {
        val c = coder(mapping = arrayOf(MappingEntry("SB", "SR", "SELF_BILLING"), MappingEntry("PL", "SR")))

        fun assignment(direction: Direction?) =
            (c.code(LineInput("SB", direction)) as LineCoding.Coded).selfBillingAssignment
        assignment(Direction.PURCHASE) shouldBe SelfBillingAssignment(Direction.PURCHASE, SubmissionType.TYPE_3)
        assignment(Direction.SALE) shouldBe SelfBillingAssignment(Direction.SALE, SubmissionType.TYPE_2)
        assignment(null) shouldBe SelfBillingAssignment(Direction.SALE, SubmissionType.TYPE_2)
        (c.code(LineInput("PL", Direction.PURCHASE)) as LineCoding.Coded).selfBillingAssignment shouldBe null
        // a pack release that swaps the assignment changes the answer with the same coder class
        val swapped =
            selfBilling.copy(
                customerCumIssuer = selfBilling.supplierCumRecipient,
                supplierCumRecipient = selfBilling.customerCumIssuer,
            )
        val after = coder(scenario = swapped, mapping = arrayOf(MappingEntry("SB", "SR", "SELF_BILLING")))
        val swappedAnswer = after.code(LineInput("SB", Direction.PURCHASE)) as LineCoding.Coded
        swappedAnswer.selfBillingAssignment!!.submissionType shouldBe SubmissionType.TYPE_2
    }

    @Test
    fun `INV-REQ-1805 a handler needs the election and the counterparty data and a pending pair is blocked`() {
        val c =
            coder(
                mapping =
                    arrayOf(
                        MappingEntry("L", "NA", "A3PL"),
                        MappingEntry("CASH", "SR", "CASH_ACCOUNTING"),
                        MappingEntry("U", "SR", "UNLISTED"),
                    ),
            )
        val approved = setOf("A3PL")
        val ok = c.code(LineInput("L", election = "VP-53", counterpartyApprovedSchemes = approved)) as LineCoding.Coded
        ok.categoryCode shouldBe "NA"
        ok.rate shouldBe "0"
        ok.ibt022Statement shouldBe "A3PL supply."
        c.code(LineInput("L", counterpartyApprovedSchemes = approved)) shouldBe LineCoding.Refused(ErrorCode.RUL_004)
        c.code(LineInput("L", election = "VP-53")) shouldBe LineCoding.Refused(ErrorCode.RUL_011)
        val other = LineInput("L", election = "VP-53", counterpartyApprovedSchemes = setOf("MES"))
        c.code(other) shouldBe LineCoding.Refused(ErrorCode.RUL_011)
        c.code(LineInput("CASH", Direction.PURCHASE, "VP-53")) shouldBe LineCoding.Refused(ErrorCode.RUL_004)
        c.code(LineInput("U", election = "VP-53")) shouldBe LineCoding.Refused(ErrorCode.RUL_004)
    }

    @Test
    fun `INV-REQ-1805 a treatment pack release changes the answer with no engine change`() {
        val mapping = arrayOf(MappingEntry("L", "NA", "A3PL"))
        val raised = selfBilling.treatments.map { if (it.tag == "A3PL") it.copy(rate = "5") else it }
        val changed = selfBilling.copy(treatments = raised)
        val input = LineInput("L", election = "VP-53", counterpartyApprovedSchemes = setOf("A3PL"))
        (coder(mapping = mapping).code(input) as LineCoding.Coded).rate shouldBe "0"
        (coder(scenario = changed, mapping = mapping).code(input) as LineCoding.Coded).rate shouldBe "5"
    }

    @Test
    fun `INV-REQ-497 a namespaced code is refused at authoring with RUL-010, look-alikes by their own rule`() {
        val entries =
            listOf(
                MappingEntry("A", "VENDOR:SR"),
                MappingEntry("B", "SR"),
                MappingEntry("C", "SRCA-C"),
                MappingEntry("D", "SRX"),
            )
        val refused = MappingCodeChecks.atAuthoring(entries)
        refused shouldBe listOf(CodeFinding("A", "VENDOR:SR", ErrorCode.RUL_010))
        val signOff = MappingCodeChecks.atSignOff(entries, catalogue(true, "SR", "SRCA-C"))
        signOff shouldBe
            listOf(CodeFinding("A", "VENDOR:SR", ErrorCode.RUL_010), CodeFinding("D", "SRX", ErrorCode.RUL_009))
        MappingCodeChecks.atAuthoring(entries.drop(1)).shouldBeEmpty()
    }

    @Test
    fun `INV-REQ-497 deny by default - lower case, padded, empty and punctuated codes are not bare`() {
        listOf("sr", " SR", "SR ", "", "peppol.SR", "a/SR", "SR:", "SR-", "-SR", "SR--X", "AAAAAAAAAAA").forEach {
            MappingCodeChecks.isBare(it) shouldBe false
        }
        listOf("SR", "SRCA-C", "TXNA", "A").forEach { MappingCodeChecks.isBare(it) shouldBe true }
    }

    @Test
    fun `INV-REQ-2429 a catalogue refuses a code twice and a mapping coder refuses an internal code mapped twice`() {
        runCatching { catalogue(true, "SR", "SR") }.isFailure shouldBe true
        val twice = arrayOf(MappingEntry("A", "SR"), MappingEntry("A", "ZR"))
        runCatching { coder(mapping = twice) }.isFailure shouldBe true
        catalogue(true, "SR").entries shouldHaveSize 1
        catalogue(true, "SR").entryOf("SR")!!.sourceClass shouldBe SourceClass.PRIMARY
        coder(mapping = arrayOf(MappingEntry("A", "SR"))).code(LineInput("A")).shouldBeInstanceOf<LineCoding.Coded>()
    }

    @Test
    fun `INV-REQ-497 RUL-010 tests the colon only, with RUL-009 at sign-off and RUL-006 at evaluation for the rest`() {
        val namespaced = listOf("gst:SR", ":SR").map { MappingEntry("N-$it", it) }
        MappingCodeChecks.atAuthoring(namespaced).map { it.code }.distinct() shouldBe listOf(ErrorCode.RUL_010)
        MappingCodeChecks.atAuthoring(namespaced) shouldHaveSize 2
        listOf("sr", "SR ", "gst-SR", "ABCDEFGHIJK", "SRX", "SRCA-C").forEach { code ->
            val e = listOf(MappingEntry("K", code))
            MappingCodeChecks.atAuthoring(e).shouldBeEmpty()
            val signOff = MappingCodeChecks.atSignOff(e, full)
            val evaluated = LineCoder(e, full, selfBilling).code(LineInput("K"))
            if (code == "SRCA-C") {
                signOff.shouldBeEmpty()
                evaluated shouldBe LineCoding.Coded("SRCA-C")
            } else {
                signOff shouldBe listOf(CodeFinding("K", code, ErrorCode.RUL_009))
                evaluated shouldBe LineCoding.Refused(ErrorCode.RUL_006)
            }
        }
        MappingCodeChecks.atAuthoring(listOf(MappingEntry("S", "SRCA-S"))).shouldBeEmpty()
        MappingCodeChecks
            .atSignOff(listOf(MappingEntry("S", "SRCA-S")), catalogue(true, "SRCA-S"))
            .shouldBeEmpty()
    }

    @Test
    fun `INV-REQ-1805 a tag with no row for the line side is RUL-004 and any one listed scheme matches`() {
        val c = coder(mapping = arrayOf(MappingEntry("L", "NA", "A3PL"), MappingEntry("T", "SR", "CASH_ACCOUNTING")))
        // A3PL has a row for SUPPLY only; the purchase side has none
        c.code(LineInput("L", Direction.PURCHASE, "VP-53")) shouldBe LineCoding.Refused(ErrorCode.RUL_004)
        val two =
            selfBilling.treatments.map {
                if (it.tag ==
                    "A3PL"
                ) {
                    it.copy(counterpartyApprovedUnder = setOf("X", "A3PL"))
                } else {
                    it
                }
            }
        val anyOne = LineInput("L", election = "VP-53", counterpartyApprovedSchemes = setOf("A3PL", "MES"))
        coder(scenario = selfBilling.copy(treatments = two), mapping = arrayOf(MappingEntry("L", "NA", "A3PL")))
            .code(anyOne)
            .shouldBeInstanceOf<LineCoding.Coded>()
        c.code(LineInput("T", Direction.PURCHASE, "VP-53")) shouldBe LineCoding.Refused(ErrorCode.RUL_004)
    }

    @Test
    fun `INV-Q-11930 sign-off gives RUL-010 to a namespaced code whatever the catalogue holds, in entry order`() {
        val withNamespaced = catalogue(true, "SR", "gst:SR")
        listOf("VENDOR:SR", "gst:SR", ":SR").forEach { code ->
            val e = listOf(MappingEntry("N", code))
            MappingCodeChecks.atSignOff(e, full) shouldBe listOf(CodeFinding("N", code, ErrorCode.RUL_010))
            MappingCodeChecks.atSignOff(e, withNamespaced) shouldBe listOf(CodeFinding("N", code, ErrorCode.RUL_010))
        }
        // the full-width colon is not the separator
        val fullWidth = listOf(MappingEntry("F", "gst\uFF1ASR"))
        MappingCodeChecks.atAuthoring(fullWidth).shouldBeEmpty()
        MappingCodeChecks.atSignOff(fullWidth, full) shouldBe listOf(CodeFinding("F", "gst\uFF1ASR", ErrorCode.RUL_009))
        val mixed = listOf(MappingEntry("1", "gst:SR"), MappingEntry("2", "SRX"), MappingEntry("3", "SR"))
        MappingCodeChecks.atSignOff(mixed, full) shouldBe
            listOf(CodeFinding("1", "gst:SR", ErrorCode.RUL_010), CodeFinding("2", "SRX", ErrorCode.RUL_009))
    }
}
