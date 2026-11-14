package com.dynokore.gst.evidence.application.retention

import com.dynokore.gst.evidence.domain.retention.HoldStatus
import com.dynokore.gst.evidence.domain.retention.LegalHold
import com.dynokore.gst.evidence.domain.retention.NoticeWindow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DeleteAtRetentionEndTest {
    private val now = Instant.parse("2026-06-10T12:00:01Z")
    private val period = LocalDate.parse("2020-03-01")
    private val calls = mutableListOf<String>()
    private var partition: PartitionRecord? =
        PartitionRecord("PART-1", "E1", period, period.plusDays(30), PartitionState.ACTIVE, listOf("h1"))
    private var retentionEnd: Instant? = Instant.parse("2026-01-01T00:00:00Z")
    private var hold: LegalHold? = null
    private var notice: NoticeRecord? = NoticeRecord(Instant.parse("2026-06-09T12:00:00Z"), NoticeWindow(1))
    private var approvals =
        listOf(Approval("u1", "retention.delete.request"), Approval("u2", "retention.delete.approve"))
    private var integrityOk = true
    private var auditActor: String? = null

    private val registry =
        object : PartitionRegistry {
            override fun find(partitionId: String): PartitionRecord? {
                calls += "find"
                return partition
            }
        }
    private val subject =
        DeleteAtRetentionEnd(
            registry,
            object : HoldReader {
                override fun activeCovering(
                    tenant: String,
                    entity: String,
                    periodFrom: LocalDate,
                    periodTo: LocalDate,
                ): List<LegalHold> {
                    calls += "holds"
                    return listOfNotNull(hold)
                }
            },
            object : RetentionEndReader {
                override fun retentionEnd(
                    tenant: String,
                    partitionId: String,
                ): Instant? {
                    calls += "retentionEnd"
                    return retentionEnd
                }
            },
            object : NoticeReader {
                override fun window(
                    tenant: String,
                    partitionId: String,
                ): NoticeRecord? {
                    calls += "notice"
                    return notice
                }
            },
            object : ApprovalReader {
                override fun approvals(
                    tenant: String,
                    partitionId: String,
                ): List<Approval> {
                    calls += "approvals"
                    return approvals
                }
            },
            object : DeletionIntegrity {
                override fun allowsDeletion(
                    tenant: String,
                    partitionId: String,
                ): Boolean {
                    calls += "integrity"
                    return integrityOk
                }
            },
            object : DeletionAudit {
                override fun append(
                    tenant: String,
                    partitionId: String,
                    actorUserId: String,
                    tailHashes: List<String>,
                ): String {
                    calls += "audit"
                    auditActor = actorUserId
                    return "AUD-1"
                }
            },
            object : PartitionLifecycle {
                override fun detach(
                    partitionId: String,
                    auditRef: String,
                    retentionEnd: Instant,
                ) {
                    calls += "detach"
                }

                override fun archive(partitionId: String): String {
                    calls += "archive"
                    return "ARC-1"
                }

                override fun drop(partitionId: String) {
                    calls += "drop"
                    partition = partition?.copy(state = PartitionState.DROPPED)
                }
            },
            Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun run() = subject.execute("T1", "user-7", "PART-1")

    /** The result is [expected] and exactly the ports in [reads] were read, in order; no write port was touched. */
    private fun refused(
        expected: DeleteAtRetentionEndResult,
        vararg reads: String,
    ) {
        run() shouldBe expected
        calls shouldBe listOf("find") + reads.toList()
    }

    private val activeHold = LegalHold("H1", "E1", period, "dispute", HoldStatus.ACTIVE)

    @Test
    fun `INV-AC-22963 retention end later than now is refused also with no hold and a closed window`() {
        retentionEnd = now.plusSeconds(60)
        refused(DeleteAtRetentionEndResult.RETENTION_NOT_ENDED, "retentionEnd")
    }

    @Test
    fun `INV-AC-22963 retention end null is refused`() {
        retentionEnd = null
        refused(DeleteAtRetentionEndResult.RETENTION_NOT_ENDED, "retentionEnd")
    }

    @Test
    fun `INV-AC-22963 hold in force is refused also after the window`() {
        hold = activeHold
        refused(DeleteAtRetentionEndResult.HOLD_IN_FORCE, "retentionEnd", "holds")
    }

    @Test
    fun `INV-AC-22963 no notice record is NOTICE_MISSING`() {
        notice = null
        refused(DeleteAtRetentionEndResult.NOTICE_MISSING, "retentionEnd", "holds", "notice")
    }

    @Test
    fun `INV-AC-22963 window open is refused`() {
        notice = NoticeRecord(now.minusSeconds(60), NoticeWindow(1))
        refused(DeleteAtRetentionEndResult.WINDOW_OPEN, "retentionEnd", "holds", "notice")
    }

    @Test
    fun `INV-AC-22963 one approval is refused`() {
        approvals = approvals.take(1)
        refused(DeleteAtRetentionEndResult.APPROVALS_MISSING, "retentionEnd", "holds", "notice", "approvals")
    }

    @Test
    fun `INV-AC-22963 the same approver twice is refused`() {
        approvals = listOf(Approval("u1", "retention.delete.request"), Approval("u1", "retention.delete.approve"))
        refused(DeleteAtRetentionEndResult.APPROVALS_MISSING, "retentionEnd", "holds", "notice", "approvals")
    }

    @Test
    fun `INV-AC-22963 integrity refusing is CHAIN_VERIFICATION_FAILED`() {
        integrityOk = false
        refused(
            DeleteAtRetentionEndResult.CHAIN_VERIFICATION_FAILED,
            "retentionEnd",
            "holds",
            "notice",
            "approvals",
            "integrity",
        )
    }

    @Test
    fun `INV-AC-22963 unknown partition is NOT_FOUND`() {
        partition = null
        refused(DeleteAtRetentionEndResult.NOT_FOUND)
    }

    @Test
    fun `INV-AC-22963 two distinct approvals delete audit first then detach archive drop with the user ID`() {
        run() shouldBe DeleteAtRetentionEndResult.DELETED
        calls shouldBe
            listOf(
                "find",
                "retentionEnd",
                "holds",
                "notice",
                "approvals",
                "integrity",
                "audit",
                "detach",
                "archive",
                "drop",
            )
        auditActor shouldBe "user-7"
    }

    @Test
    fun `INV-AC-22963 a second deletion is ALREADY_DELETED and calls no port but the registry`() {
        run() shouldBe DeleteAtRetentionEndResult.DELETED
        calls.clear()
        run() shouldBe DeleteAtRetentionEndResult.ALREADY_DELETED
        calls shouldBe listOf("find")
    }

    @Test
    fun `INV-AC-22963 a partition stored as dropped is ALREADY_DELETED and only the registry is read`() {
        partition = partition?.copy(state = PartitionState.DROPPED)
        refused(DeleteAtRetentionEndResult.ALREADY_DELETED)
    }
}
