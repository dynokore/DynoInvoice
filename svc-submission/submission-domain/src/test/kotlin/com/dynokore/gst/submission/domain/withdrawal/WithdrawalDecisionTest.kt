package com.dynokore.gst.submission.domain.withdrawal

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.catalogue.RolePermissions
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.LifecycleState.ACKNOWLEDGED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.AWAITING_PRECEDING
import com.dynokore.gst.submission.domain.ordering.LifecycleState.BLOCKED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.CANCELLED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.HELD
import com.dynokore.gst.submission.domain.ordering.LifecycleState.INGESTED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.NETWORK_ISSUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.NOT_SUBMITTABLE
import com.dynokore.gst.submission.domain.ordering.LifecycleState.QUEUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.READY
import com.dynokore.gst.submission.domain.ordering.LifecycleState.REJECTED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT
import com.dynokore.gst.submission.domain.ordering.LifecycleState.WITHDRAWN
import com.dynokore.gst.submission.domain.ordering.isTransmitted
import com.dynokore.gst.submission.domain.withdrawal.WithdrawalDecision.AlreadyWithdrawn
import com.dynokore.gst.submission.domain.withdrawal.WithdrawalDecision.Refused
import com.dynokore.gst.submission.domain.withdrawal.WithdrawalDecision.Withdraw
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class WithdrawalDecisionTest {
    private val reason = "Upstream ERP reversed the document before issue"

    private fun decide(
        versions: List<LifecycleState>,
        permitted: Boolean = true,
        reason: String? = this.reason,
    ) = WithdrawalDecision.decide(WithdrawalInput(permitted, reason, versions))

    private val withdrawable = setOf(INGESTED, HELD, BLOCKED, READY, AWAITING_PRECEDING, QUEUED)

    @ParameterizedTest(name = "INV-AC-617 {0} alone gives Withdraw")
    @EnumSource(value = LifecycleState::class, names = ["QUEUED", "READY", "HELD", "BLOCKED", "INGESTED", "AWAITING_PRECEDING"])
    fun `INV-AC-617 a withdrawable single version gives Withdraw`(state: LifecycleState) {
        decide(listOf(state)) shouldBe Withdraw
    }

    @Test
    fun `INV-AC-617 CANCELLED then QUEUED gives Withdraw`() {
        decide(listOf(CANCELLED, QUEUED)) shouldBe Withdraw
    }

    @Test
    fun `INV-AC-1774 the issued Type 1A document and a SENT version are refused with SUB_021`() {
        decide(listOf(NETWORK_ISSUED)) shouldBe Refused(RefusalCode.SUB_021)
        decide(listOf(SENT)) shouldBe Refused(RefusalCode.SUB_021)
        decide(listOf(QUEUED, SENT)) shouldBe Refused(RefusalCode.SUB_021)
        decide(listOf(SENT, QUEUED)) shouldBe Refused(RefusalCode.SUB_021)
    }

    @ParameterizedTest(name = "INV-AC-1774 single version {0}")
    @EnumSource(LifecycleState::class)
    fun `INV-AC-1774 every state as the single version`(state: LifecycleState) {
        val expected =
            when (state) {
                in withdrawable -> Withdraw
                WITHDRAWN -> AlreadyWithdrawn
                else -> Refused(RefusalCode.SUB_021)
            }
        decide(listOf(state)) shouldBe expected
    }

    @Test
    fun `INV-AC-1774 the 18 states fall into five sets`() {
        val transmitted = LifecycleState.entries.filter(::isTransmitted).toSet()
        transmitted.size shouldBe 8
        val sets = listOf(transmitted, setOf(REJECTED), setOf(CANCELLED, NOT_SUBMITTABLE), setOf(WITHDRAWN), withdrawable)
        sets.sumOf { it.size } shouldBe 18
        sets.flatten().toSet() shouldBe LifecycleState.entries.toSet()
    }

    @Test
    fun `INV-AC-1775 a caller without the permission is refused with SRT_004`() {
        decide(listOf(QUEUED), permitted = false) shouldBe Refused(RefusalCode.SRT_004)
        decide(listOf(SENT), permitted = false) shouldBe Refused(RefusalCode.SRT_004)
        decide(listOf(QUEUED), permitted = false, reason = null) shouldBe Refused(RefusalCode.SRT_004)
    }

    @ParameterizedTest(name = "INV-AC-1776 reason [{0}]")
    @ValueSource(strings = ["", "   ", "\t", " \t \n"])
    fun `INV-AC-1776 a blank reason is refused with SRT_001`(blank: String) {
        decide(listOf(QUEUED), reason = blank) shouldBe Refused(RefusalCode.SRT_001)
    }

    @Test
    fun `INV-AC-1776 a null reason is refused with SRT_001`() {
        decide(listOf(QUEUED), reason = null) shouldBe Refused(RefusalCode.SRT_001)
        decide(listOf(SENT), reason = null) shouldBe Refused(RefusalCode.SRT_001)
    }

    @Test
    fun `INV-AC-1776 the reason length is counted in code points`() {
        decide(listOf(QUEUED), reason = "a".repeat(500)) shouldBe Withdraw
        decide(listOf(QUEUED), reason = "a".repeat(501)) shouldBe Refused(RefusalCode.SRT_001)
        val supplementary = "😀".repeat(500)
        supplementary.length shouldBe 1000
        decide(listOf(QUEUED), reason = supplementary) shouldBe Withdraw
        decide(listOf(QUEUED), reason = "😀".repeat(501)) shouldBe Refused(RefusalCode.SRT_001)
    }

    @Test
    fun `INV-AC-1776 the order of the refusals, the first that applies wins`() {
        decide(emptyList(), permitted = false) shouldBe Refused(RefusalCode.SRT_005)
        decide(emptyList()) shouldBe Refused(RefusalCode.SRT_005)
        decide(listOf(QUEUED), permitted = false, reason = null) shouldBe Refused(RefusalCode.SRT_004)
        decide(listOf(WITHDRAWN), reason = null) shouldBe Refused(RefusalCode.SRT_001)
        decide(listOf(WITHDRAWN)) shouldBe AlreadyWithdrawn
        decide(listOf(SENT, WITHDRAWN)) shouldBe AlreadyWithdrawn
    }

    @Test
    fun `INV-AC-1774 REJECTED anywhere, CANCELLED and NOT_SUBMITTABLE as the last version are refused with SUB_021`() {
        listOf(
            listOf(REJECTED),
            listOf(NOT_SUBMITTABLE),
            listOf(CANCELLED),
            listOf(REJECTED, QUEUED),
            listOf(QUEUED, REJECTED),
            listOf(REJECTED, CANCELLED),
        ).forEach { decide(it) shouldBe Refused(RefusalCode.SUB_021) }
        decide(listOf(CANCELLED, QUEUED)) shouldBe Withdraw
    }

    @Test
    fun `INV-AC-1774 isTransmitted keeps its eight states and REJECTED stays false`() {
        isTransmitted(REJECTED) shouldBe false
        isTransmitted(ACKNOWLEDGED) shouldBe true
        WithdrawalDecision.wasSentToAccessPoint(REJECTED) shouldBe true
    }

    @Test
    fun `INV-AC-20073 the pure withdrawal decision withdraws a QUEUED document and refuses the rest`() {
        decide(listOf(QUEUED)) shouldBe Withdraw
        val sent = listOf(SENT, NETWORK_ISSUED).map { decide(listOf(it)) } + decide(listOf(QUEUED, SENT))
        sent.forEach { it shouldBe Refused(RefusalCode.SUB_021) }
        val noPermission = decide(listOf(QUEUED), permitted = false)
        noPermission shouldBe Refused(RefusalCode.SRT_004)
        val noReason = decide(listOf(QUEUED), reason = null)
        noReason shouldBe Refused(RefusalCode.SRT_001)
        (sent + noPermission + noReason).forEach { it shouldNotBe Withdraw }
    }

    @Test
    fun `INV-AC-20073 equal input gives equal output`() {
        decide(listOf(QUEUED)) shouldBe decide(listOf(QUEUED))
        Refused(RefusalCode.SUB_021) shouldBe Refused(RefusalCode.SUB_021)
    }

    // ---- equality with the contract and the catalogue ----

    private val openApi by lazy { File(System.getProperty("gst.contract.dir"), "openapi.yaml").readText() }

    private val operationBlock by lazy {
        val tail = openApi.substringAfter("operationId: withdrawSubmission")
        tail.substringBefore("\n  /")
    }

    @Test
    fun `INV-AC-20073 the RefusalCode strings equal the x-error-codes of withdrawSubmission`() {
        val line = Regex("x-error-codes:\\s*\\[([^\\]]*)]").find(operationBlock)!!.groupValues[1]
        val codes = line.split(',').map { it.trim() }
        codes shouldContainExactlyInAnyOrder RefusalCode.entries.map { it.code }
    }

    @Test
    fun `INV-AC-20073 the RefusalCode statuses equal the response keys other than 200 and default`() {
        val responses = operationBlock.substringAfter("\n      responses:\n")
        val keys =
            Regex("(?m)^ {8}'?\"?([0-9]{3}|default)'?\"?:").findAll(responses).map { it.groupValues[1] }.toList()
        keys.filter { it != "200" && it != "default" }.map { it.toInt() } shouldContainExactlyInAnyOrder
            RefusalCode.entries.map { it.httpStatus }
    }

    @Test
    fun `INV-AC-20073 WITHDRAWN is the one member of WithdrawResult state`() {
        val block = openApi.substringAfter("\n    WithdrawResult:\n").substringBefore("\n    ", "")
        val state = openApi.substringAfter("\n    WithdrawResult:\n").substringAfter("\n        state:\n")
        val members = Regex("enum:\\s*\\[([^\\]]*)]").find(state)!!.groupValues[1].split(',').map { it.trim() }
        block.isNotEmpty() shouldBe true
        members shouldBe listOf(WITHDRAWN.name)
    }

    @Test
    fun `INV-AC-20073 WITHDRAW_PERMISSION is the catalogue key held by tax approver only`() {
        WITHDRAW_PERMISSION shouldBe "submission.withdraw"
        DeploymentForm.entries.forEach { form ->
            val cells = RolePermissions.cells(form)
            cells.filterValues { WITHDRAW_PERMISSION in it.keys }.keys shouldBe setOf("tax.approver")
        }
    }

    @Test
    fun `INV-AC-20073 the RefusalCode strings are rows of the error catalogue`() {
        RefusalCode.entries.forEach { ErrorCode.fromCode(it.code) shouldNotBe null }
        RefusalCode.entries.map { ErrorCode.fromCode(it.code)!!.code } shouldBe RefusalCode.entries.map { it.code }
    }
}
