package com.dynokore.gst.submission.domain.typing

import com.dynokore.gst.submission.domain.typing.Direction.PURCHASE
import com.dynokore.gst.submission.domain.typing.Direction.SALE
import com.dynokore.gst.submission.domain.typing.NetworkPath.ISSUED_VIA_NETWORK
import com.dynokore.gst.submission.domain.typing.NetworkPath.NOT_ON_NETWORK
import com.dynokore.gst.submission.domain.typing.NetworkPath.RECEIVED_VIA_NETWORK
import com.dynokore.gst.submission.domain.typing.NetworkPath.RELAY_ISSUANCE
import com.dynokore.gst.submission.domain.typing.SubmissionType.TYPE_1A
import com.dynokore.gst.submission.domain.typing.SubmissionType.TYPE_1B
import com.dynokore.gst.submission.domain.typing.SubmissionType.TYPE_2
import com.dynokore.gst.submission.domain.typing.SubmissionType.TYPE_3
import com.dynokore.gst.submission.domain.typing.TypeDecision.Decided
import com.dynokore.gst.submission.domain.typing.TypeDecision.Undecided
import com.dynokore.gst.submission.domain.typing.UndecidedReason.CONTRADICTORY_FACTS
import com.dynokore.gst.submission.domain.typing.UndecidedReason.FACTS_MISSING
import com.dynokore.gst.submission.domain.typing.UndecidedReason.SELF_BILLING_DIRECTION_MISMATCH
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.io.File

class SubmissionTypeDecisionTest {
    private fun facts(
        direction: Direction,
        path: NetworkPath,
        onNetwork: Boolean? = null,
        debitNote: Boolean = false,
        selfBilling: SelfBillingAssignment? = null,
    ) = RoutingFacts(direction, path, onNetwork, debitNote, selfBilling)

    private fun decided(type: SubmissionType) = Decided(type)

    private val reachability = listOf(true, false, null)

    @Test
    fun `INV-AC-452 INV-AC-10595 self-billed sale and purchase take the assignment the facts carry`() {
        SubmissionTypeDecision.decide(
            facts(SALE, NOT_ON_NETWORK, selfBilling = SelfBillingAssignment(SALE, TYPE_2)),
        ) shouldBe decided(TYPE_2)
        SubmissionTypeDecision.decide(
            facts(PURCHASE, NOT_ON_NETWORK, selfBilling = SelfBillingAssignment(PURCHASE, TYPE_3)),
        ) shouldBe decided(TYPE_3)
    }

    @Test
    fun `INV-AC-452 INV-AC-10595 a changed pack value changes the decision so no code constant sits behind it`() {
        SubmissionTypeDecision.decide(
            facts(PURCHASE, NOT_ON_NETWORK, selfBilling = SelfBillingAssignment(PURCHASE, TYPE_2)),
        ) shouldBe decided(TYPE_2)
    }

    @Test
    fun `INV-AC-10595 a self-billing assignment of the other direction is a mismatch`() {
        SubmissionTypeDecision.decide(
            facts(PURCHASE, NOT_ON_NETWORK, selfBilling = SelfBillingAssignment(SALE, TYPE_3)),
        ) shouldBe Undecided(SELF_BILLING_DIRECTION_MISMATCH)
    }

    @Test
    fun `INV-AC-443 INV-AC-10593 a sale not on the network is Type 2 whatever the reachability says`() {
        reachability.forEach {
            SubmissionTypeDecision.decide(facts(SALE, NOT_ON_NETWORK, it)) shouldBe decided(TYPE_2)
        }
    }

    @Test
    fun `INV-AC-10593 a purchase not on the network is Type 3 whatever the reachability says`() {
        reachability.forEach {
            val result = SubmissionTypeDecision.decide(facts(PURCHASE, NOT_ON_NETWORK, it))
            result shouldBe decided(TYPE_3)
            result shouldNotBe decided(TYPE_1B)
        }
    }

    @Test
    fun `INV-AC-444 INV-AC-446 INV-AC-10594 POS and STI aggregates are Type 2 and PCP aggregates Type 3`() {
        val sale = SubmissionTypeDecision.decide(facts(SALE, NOT_ON_NETWORK))
        val purchase = SubmissionTypeDecision.decide(facts(PURCHASE, NOT_ON_NETWORK))
        sale shouldBe decided(TYPE_2)
        purchase shouldBe decided(TYPE_3)
        sale shouldNotBe decided(TYPE_3)
        purchase shouldNotBe decided(TYPE_2)
        sale shouldNotBe decided(TYPE_1A)
        purchase shouldNotBe decided(TYPE_1B)
    }

    @Test
    fun `INV-AC-1558 INV-AC-10591 a relayed debit note to a reachable customer is Type 2`() {
        SubmissionTypeDecision.decide(facts(SALE, RELAY_ISSUANCE, true, debitNote = true)) shouldBe decided(TYPE_2)
        SubmissionTypeDecision.decide(facts(SALE, RELAY_ISSUANCE, true, debitNote = false)) shouldBe decided(TYPE_1A)
    }

    @Test
    fun `INV-AC-10591 a debit note with a network path is contradictory`() {
        SubmissionTypeDecision.decide(facts(SALE, ISSUED_VIA_NETWORK, debitNote = true)) shouldBe
            Undecided(CONTRADICTORY_FACTS)
        SubmissionTypeDecision.decide(facts(PURCHASE, RECEIVED_VIA_NETWORK, debitNote = true)) shouldBe
            Undecided(CONTRADICTORY_FACTS)
    }

    @Test
    fun `INV-AC-341 INV-AC-342 INV-AC-10592 a relayed sale to an unreachable or unknown customer is Type 2`() {
        SubmissionTypeDecision.decide(facts(SALE, RELAY_ISSUANCE, false)) shouldBe decided(TYPE_2)
        SubmissionTypeDecision.decide(facts(SALE, RELAY_ISSUANCE, null)) shouldBe decided(TYPE_2)
    }

    @Test
    fun `INV-AC-442 INV-AC-10592 a sale issued via the network is Type 1A`() {
        SubmissionTypeDecision.decide(facts(SALE, ISSUED_VIA_NETWORK)) shouldBe decided(TYPE_1A)
        SubmissionTypeDecision.decide(facts(SALE, RELAY_ISSUANCE, true)) shouldBe decided(TYPE_1A)
    }

    @Test
    fun `INV-AC-10592 a sale received via the network is contradictory`() {
        SubmissionTypeDecision.decide(facts(SALE, RECEIVED_VIA_NETWORK)) shouldBe Undecided(CONTRADICTORY_FACTS)
    }

    @Test
    fun `a purchase received via the network is Type 1B`() {
        SubmissionTypeDecision.decide(facts(PURCHASE, RECEIVED_VIA_NETWORK)) shouldBe decided(TYPE_1B)
    }

    @Test
    fun `purchase issued or relayed and sale-side look-alikes are contradictory`() {
        SubmissionTypeDecision.decide(facts(PURCHASE, ISSUED_VIA_NETWORK)) shouldBe Undecided(CONTRADICTORY_FACTS)
        SubmissionTypeDecision.decide(facts(PURCHASE, RELAY_ISSUANCE)) shouldBe Undecided(CONTRADICTORY_FACTS)
        SubmissionTypeDecision.decide(facts(PURCHASE, RELAY_ISSUANCE, true)) shouldBe Undecided(CONTRADICTORY_FACTS)
    }

    @Test
    fun `a self-billing assignment of the other direction is a mismatch`() {
        SubmissionTypeDecision.decide(
            facts(PURCHASE, NOT_ON_NETWORK, selfBilling = SelfBillingAssignment(SALE, TYPE_3)),
        ) shouldBe Undecided(SELF_BILLING_DIRECTION_MISMATCH)
    }

    @Test
    fun `null facts are undecided as missing`() {
        SubmissionTypeDecision.decide(null) shouldBe Undecided(FACTS_MISSING)
    }

    @Test
    fun `INV-AC-743 INV-AC-10596 a buyer's portal purchase is Type 3 with or without a self-billing assignment`() {
        reachability.forEach {
            SubmissionTypeDecision.decide(facts(PURCHASE, NOT_ON_NETWORK, it)) shouldBe decided(TYPE_3)
            SubmissionTypeDecision.decide(
                facts(PURCHASE, NOT_ON_NETWORK, it, selfBilling = SelfBillingAssignment(PURCHASE, TYPE_3)),
            ) shouldBe decided(TYPE_3)
        }
    }

    @Test
    fun `INV-AC-1818 INV-AC-10596 a buyer's portal sale is Type 2 with or without a self-billing assignment`() {
        reachability.forEach {
            SubmissionTypeDecision.decide(facts(SALE, NOT_ON_NETWORK, it)) shouldBe decided(TYPE_2)
            SubmissionTypeDecision.decide(
                facts(SALE, NOT_ON_NETWORK, it, selfBilling = SelfBillingAssignment(SALE, TYPE_2)),
            ) shouldBe decided(TYPE_2)
        }
    }

    @Test
    fun `INV-AC-10592 the decision input carries no acceptance fact`() {
        RoutingFacts::class.java.declaredFields
            .map { it.name }
            .shouldContainExactlyInAnyOrder(
                "direction",
                "networkPath",
                "counterpartyOnNetwork",
                "debitNote",
                "selfBilling",
            )
    }

    // Expected result per (direction, path, debitNote), for selfBilling absent / matching pair / mismatching pair.
    // A cell is one code, or three codes joined by '/' for reachability true / false / null.
    // Codes: 1A 1B 2 3 = Decided; X = CONTRADICTORY_FACTS; M = SELF_BILLING_DIRECTION_MISMATCH.
    // The matching pair is (direction, TYPE_2) for SALE and (direction, TYPE_3) for PURCHASE;
    // the mismatching pair is the other direction.
    private data class Row(
        val direction: Direction,
        val path: NetworkPath,
        val debitNote: Boolean,
        val absent: String,
        val matching: String,
        val mismatching: String,
    )

    private val table =
        listOf(
            Row(SALE, ISSUED_VIA_NETWORK, false, "1A", "2", "M"),
            Row(SALE, ISSUED_VIA_NETWORK, true, "X", "X", "X"),
            Row(SALE, RELAY_ISSUANCE, false, "1A/2/2", "2", "M"),
            Row(SALE, RELAY_ISSUANCE, true, "2", "2", "M"),
            Row(SALE, RECEIVED_VIA_NETWORK, false, "X", "2", "M"),
            Row(SALE, RECEIVED_VIA_NETWORK, true, "X", "X", "X"),
            Row(SALE, NOT_ON_NETWORK, false, "2", "2", "M"),
            Row(SALE, NOT_ON_NETWORK, true, "2", "2", "M"),
            Row(PURCHASE, ISSUED_VIA_NETWORK, false, "X", "3", "M"),
            Row(PURCHASE, ISSUED_VIA_NETWORK, true, "X", "X", "X"),
            Row(PURCHASE, RELAY_ISSUANCE, false, "X", "3", "M"),
            Row(PURCHASE, RELAY_ISSUANCE, true, "X", "3", "M"),
            Row(PURCHASE, RECEIVED_VIA_NETWORK, false, "1B", "3", "M"),
            Row(PURCHASE, RECEIVED_VIA_NETWORK, true, "X", "X", "X"),
            Row(PURCHASE, NOT_ON_NETWORK, false, "3", "3", "M"),
            Row(PURCHASE, NOT_ON_NETWORK, true, "3", "3", "M"),
        )

    private fun expected(code: String): TypeDecision =
        when (code) {
            "1A" -> decided(TYPE_1A)
            "1B" -> decided(TYPE_1B)
            "2" -> decided(TYPE_2)
            "3" -> decided(TYPE_3)
            "X" -> Undecided(CONTRADICTORY_FACTS)
            "M" -> Undecided(SELF_BILLING_DIRECTION_MISMATCH)
            else -> error("unknown code $code")
        }

    private fun cells(cell: String): List<String> {
        val parts = cell.split('/')
        return if (parts.size == 1) List(reachability.size) { parts[0] } else parts
    }

    @Test
    fun `the table covers every combination of direction, path, reachability, debit note and self-billing`() {
        table.map { Triple(it.direction, it.path, it.debitNote) }.toSet().size shouldBe 2 * 4 * 2
        table.size shouldBe 2 * 4 * 2
        var checked = 0
        for (row in table) {
            val other = if (row.direction == SALE) PURCHASE else SALE
            val matchingType = if (row.direction == SALE) TYPE_2 else TYPE_3
            val variants =
                listOf(
                    row.absent to null,
                    row.matching to SelfBillingAssignment(row.direction, matchingType),
                    row.mismatching to SelfBillingAssignment(other, matchingType),
                )
            for ((cell, selfBilling) in variants) {
                val codes = cells(cell)
                reachability.forEachIndexed { index, reach ->
                    val input = facts(row.direction, row.path, reach, row.debitNote, selfBilling)
                    SubmissionTypeDecision.decide(input) shouldBe expected(codes[index])
                    checked++
                }
            }
        }
        checked shouldBe 2 * 4 * 3 * 2 * 3
    }

    @Test
    fun `the Direction members equal the canonical RecordDocumentRequest direction enum`() {
        val text = File(System.getProperty("gst.canonical.openapi")).readText()
        val request =
            text
                .substringAfter(
                    "\n    RecordDocumentRequest:\n",
                ).substringBefore("\n    RecordDocumentResponse:")
        val pattern = "\\n        direction:\\n(?:          [^\\n]*\\n)*?          enum:\\s*\\[([^\\]]*)]"
        val list = Regex(pattern).find(request)!!
        val members = list.groupValues[1].split(',').map { it.trim() }
        Direction.entries.map { it.name } shouldContainExactlyInAnyOrder members
    }

    @Test
    fun `the SubmissionType members equal the scenario pack party submissionType enum`() {
        val text = File(System.getProperty("gst.rules.handlers.schema")).readText()
        val party = text.substringAfter("\n    \"party\": {").substringBefore("\n    \"sourceClass\"")
        val list = Regex("\"submissionType\":\\s*\\{[^}]*?\"enum\":\\s*\\[([^\\]]*)]").find(party)!!
        val members = Regex("\"([^\"]+)\"").findAll(list.groupValues[1]).map { it.groupValues[1] }.toList()
        members.isNotEmpty() shouldBe true
        SubmissionType.entries.map { it.name } shouldContainExactlyInAnyOrder members
    }

    @Test
    fun `the local vocabularies are pinned`() {
        NetworkPath.entries.map { it.name } shouldContainExactlyInAnyOrder
            listOf("ISSUED_VIA_NETWORK", "RELAY_ISSUANCE", "RECEIVED_VIA_NETWORK", "NOT_ON_NETWORK")
        UndecidedReason.entries.map { it.name } shouldContainExactlyInAnyOrder
            listOf("CONTRADICTORY_FACTS", "SELF_BILLING_DIRECTION_MISMATCH", "FACTS_MISSING")
    }

    // Deny by default: every class the component's bytecode names outside the typing package and kotlin must be listed
    // here with its reason. A clock, a store or a configuration reader is a class not on the list.
    private val allowedExternal =
        setOf(
            "java/lang/Object", // every class
            "java/lang/Boolean", // nullable counterpartyOnNetwork
            "org/jetbrains/annotations/NotNull", // Kotlin nullability annotation
            "org/jetbrains/annotations/Nullable", // Kotlin nullability annotation
        )

    @Test
    fun `the component names no class beyond the typing package, kotlin and the allowed list`() {
        val names = mutableSetOf<String>()
        val descriptorPrefix = Regex("^[A-Z]+(?=(?:java|javax|kotlin|org|com|jdk|sun)/)")
        listOf("SubmissionTypeDecision").forEach { simple ->
            val bytes =
                SubmissionTypeDecision::class.java
                    .getResourceAsStream("$simple.class")!!
                    .readBytes()
                    .toString(Charsets.ISO_8859_1)
            Regex("(?:[A-Za-z][A-Za-z0-9_]*/)+[A-Za-z][A-Za-z0-9_$]*").findAll(bytes).forEach {
                names +=
                    it.value.replace(descriptorPrefix, "")
            }
        }
        val own = "com/dynokore/gst/submission/domain/typing/"
        val unknown = names.filterNot { it.startsWith(own) || it.startsWith("kotlin/") || it in allowedExternal }
        unknown shouldBe emptyList()
        names.any { it.startsWith(own) } shouldBe true
    }
}
