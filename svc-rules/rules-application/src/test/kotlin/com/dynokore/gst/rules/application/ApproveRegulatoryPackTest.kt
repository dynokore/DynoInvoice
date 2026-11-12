package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.application.ApproveRegulatoryPackResult.AlreadyApproved
import com.dynokore.gst.rules.application.ApproveRegulatoryPackResult.Approved
import com.dynokore.gst.rules.application.ApproveRegulatoryPackResult.Denied
import com.dynokore.gst.rules.application.ApproveRegulatoryPackResult.NoDryRun
import com.dynokore.gst.rules.application.ApproveRegulatoryPackResult.NotFound
import com.dynokore.gst.rules.application.ApproveRegulatoryPackResult.NotStaged
import com.dynokore.gst.rules.application.ApproveRegulatoryPackResult.RequesterUnknown
import com.dynokore.gst.rules.application.ApproveRegulatoryPackResult.SelfApproval
import com.dynokore.gst.rules.domain.pack.PackStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ApproveRegulatoryPackTest {
    private class FakeStore(
        var record: RegulatoryPackRecord?,
    ) : RegulatoryPackStore {
        val approvals = mutableListOf<Triple<String, String, String>>()

        override fun find(
            packType: String,
            version: String,
        ): RegulatoryPackRecord? = record

        override fun markApproved(
            packType: String,
            version: String,
            approvedBy: String,
        ) {
            approvals += Triple(packType, version, approvedBy)
            record = record?.copy(status = PackStatus.APPROVED)
        }
    }

    private fun staged(
        by: String? = "u-stager",
        delta: Boolean = true,
        status: PackStatus = PackStatus.STAGED,
    ) = RegulatoryPackRecord(status, by, delta)

    private fun run(
        record: RegulatoryPackRecord?,
        allowed: Boolean = true,
        approver: String = "u-approver",
    ): Pair<ApproveRegulatoryPackResult, FakeStore> {
        val store = FakeStore(record)
        val result = ApproveRegulatoryPack(store) { allowed }.execute("PINT-SG", "1.1", approver)
        return result to store
    }

    @Test
    fun `INV-AC-20459 a different user approves a STAGED pack with a delta and the approver is recorded`() {
        val (r, s) = run(staged())
        r shouldBe Approved
        s.approvals shouldBe listOf(Triple("PINT-SG", "1.1", "u-approver"))
    }

    @Test
    fun `INV-AC-20459 a denied approver is Denied and nothing changes`() {
        val (r, s) = run(staged(), allowed = false)
        r shouldBe Denied
        s.approvals shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20459 a denied approver of an unknown pack is Denied, not NotFound`() {
        run(null, allowed = false).first shouldBe Denied
    }

    @Test
    fun `INV-AC-20459 an unknown pack is NotFound`() {
        val (r, s) = run(null)
        r shouldBe NotFound
        s.approvals shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20459 an APPROVED pack answers AlreadyApproved even for the stager or without a delta`() {
        val (r, s) = run(staged(by = null, delta = false, status = PackStatus.APPROVED), approver = "u-stager")
        r shouldBe AlreadyApproved
        s.approvals shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20459 every status other than STAGED and APPROVED is NotStaged`() {
        for (st in PackStatus.entries.filter { it != PackStatus.STAGED && it != PackStatus.APPROVED }) {
            val (r, s) = run(staged(status = st))
            r shouldBe NotStaged
            s.approvals shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-20459 a STAGED pack without a delta is NoDryRun, checked before the requester`() {
        val (r, s) = run(staged(by = null, delta = false))
        r shouldBe NoDryRun
        s.approvals shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20459 a null staging user ID is RequesterUnknown and fails closed`() {
        val (r, s) = run(staged(by = null))
        r shouldBe RequesterUnknown
        s.approvals shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20459 the same user ID is SelfApproval`() {
        val (r, s) = run(staged(by = "u-approver"))
        r shouldBe SelfApproval
        s.approvals shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20459 a staging user ID differing by one character or by case is allowed`() {
        run(staged(by = "u-approvex")).first shouldBe Approved
        run(staged(by = "U-Approver")).first shouldBe Approved
        run(staged(by = "u-approver ")).first shouldBe Approved
    }

    @Test
    fun `INV-AC-20459 a repeat answers AlreadyApproved and markApproved is called once in total`() {
        val store = FakeStore(staged())
        val uc = ApproveRegulatoryPack(store) { true }
        uc.execute("PINT-SG", "1.1", "u-approver") shouldBe Approved
        uc.execute("PINT-SG", "1.1", "u-approver") shouldBe AlreadyApproved
        uc.execute("PINT-SG", "1.1", "u-other") shouldBe AlreadyApproved
        store.approvals.size shouldBe 1
    }
}
