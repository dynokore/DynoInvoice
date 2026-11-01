package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WorkQueue
import com.dynokore.gst.submission.application.queue.RetryDueScan
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.platform.outboxandqueue.WorkQueue as WorkQueuePort

/** The retry due-scan against a real database (INV-Q-7266 A "Retry delay"). */
class RetryDueScanDbTest {
    private val actor = "svc:submission-svc"
    private val due = Instant.parse("2026-11-01T10:00:00Z")

    @Test
    fun `RetryDueScan called twice with the same now enqueues once and clears next_attempt_at`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.online(dir).run { context ->
            val tenants = context.getBean(TenantContext::class.java)
            val runner = context.getBean(TransactionRunner::class.java)
            val dsl = context.getBean(DSLContext::class.java)
            val scan = context.getBean(RetryDueScan::class.java)
            val queue = context.getBean(WorkQueuePort::class.java)
            while (true) {
                val item = queue.claimNext() ?: break
                tenants.bind(item.tenant, actor) { runner.inTransaction { queue.complete(item) } }
            }
            val tenant = UUID.randomUUID()
            val dueDoc = UUID.randomUUID().toString()
            val laterDoc = UUID.randomUUID().toString()
            val sentDoc = UUID.randomUUID().toString()

            fun <T> asTenant(block: () -> T): T = tenants.bind(TenantId(tenant), actor) { runner.inTransaction(block) }

            fun seed(
                doc: String,
                state: String,
                at: Instant?,
            ) = asTenant {
                dsl
                    .insertInto(Submission.SUBMISSION_)
                    .set(Submission.SUBMISSION_.TENANT_ID, tenant)
                    .set(Submission.SUBMISSION_.DOC_UUID, doc)
                    .set(Submission.SUBMISSION_.VERSION, 1)
                    .set(Submission.SUBMISSION_.STATE, state)
                    .set(Submission.SUBMISSION_.ENTITY_REF, "E-001")
                    .set(Submission.SUBMISSION_.NEXT_ATTEMPT_AT, at?.atOffset(ZoneOffset.UTC))
                    .execute()
            }
            seed(dueDoc, "RETRY", due)
            seed(laterDoc, "RETRY", due.plusSeconds(1))
            seed(sentDoc, "SENT", due)

            asTenant { scan.run(due) } shouldBe 1
            asTenant { scan.run(due) } shouldBe 0

            val items =
                asTenant {
                    dsl
                        .select(
                            WorkQueue.WORK_QUEUE.PARTITION_KEY,
                            WorkQueue.WORK_QUEUE.LANE,
                            WorkQueue.WORK_QUEUE.PAYLOAD,
                        ).from(WorkQueue.WORK_QUEUE)
                        .fetch()
                }
            items.size shouldBe 1
            items[0].value1() shouldBe dueDoc
            items[0].value2() shouldBe "LIVE"
            checkNotNull(items[0].value3()).data().replace(" ", "") shouldBe """{"docUuid":"$dueDoc","version":1}"""
            val left =
                asTenant {
                    dsl
                        .select(Submission.SUBMISSION_.DOC_UUID)
                        .from(Submission.SUBMISSION_)
                        .where(Submission.SUBMISSION_.NEXT_ATTEMPT_AT.isNotNull)
                        .fetch(Submission.SUBMISSION_.DOC_UUID)
                }
            left.toSet() shouldBe setOf(laterDoc, sentDoc)
        }
    }
}
