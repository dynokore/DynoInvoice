package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.SubmissionDb
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WorkQueue
import com.dynokore.platform.outboxandqueue.DrainOrderAssigner
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import com.dynokore.platform.outboxandqueue.WorkQueue as WorkQueuePort

/**
 * P109 wiring of the drain order (INV-T-20564; INV-REQ-554): the TPL-18 context offers exactly one bean that is a
 * `DrainOrderAssigner`, the queue service-runtime creates, and `assignDrainOrder` through it changes the stored
 * columns of an unclaimed item and of no claimed item.
 */
class DrainOrderWiringTest {
    private val actor = "svc:submission-svc"

    @Test
    fun `the context offers exactly one DrainOrderAssigner and it assigns only unclaimed items`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.online(dir).run { context ->
            context.getBeansOfType(DrainOrderAssigner::class.java).size shouldBe 1
            val assigner = context.getBean(DrainOrderAssigner::class.java)
            val queue = context.getBean(WorkQueuePort::class.java)
            val scope = context.getBean(TenantScope::class.java)
            val runner = context.getBean(TransactionRunner::class.java)
            val dsl = context.getBean(DSLContext::class.java)
            val tenant = UUID.randomUUID()
            val free = "free-${UUID.randomUUID()}"
            val taken = "taken-${UUID.randomUUID()}"
            scope.bind(TenantId(tenant), actor) {
                runner.inTransaction {
                    queue.enqueue(QueueEntry(free, Lane.LIVE, "{}"))
                    queue.enqueue(QueueEntry(taken, Lane.LIVE, "{}"))
                }
            }
            SubmissionDb.superuser().use { c ->
                c
                    .prepareStatement(
                        "update submission.work_queue set state = 'CLAIMED', claimed_at = now() " +
                            "where partition_key = ?",
                    ).use {
                        it.setString(1, taken)
                        it.executeUpdate()
                    }
            }
            val changed =
                scope.bind(TenantId(tenant), actor) {
                    runner.inTransaction {
                        assigner.assignDrainOrder(free, 1, 4) + assigner.assignDrainOrder(taken, 1, 4)
                    }
                }
            changed shouldBe 1

            fun columns(partition: String) =
                scope.bind(TenantId(tenant), actor) {
                    runner.inTransaction {
                        dsl
                            .select(WorkQueue.WORK_QUEUE.DRAIN_TIER, WorkQueue.WORK_QUEUE.DRAIN_RANK)
                            .from(WorkQueue.WORK_QUEUE)
                            .where(WorkQueue.WORK_QUEUE.PARTITION_KEY.eq(partition))
                            .fetchOne()!!
                            .let { it.value1()!!.toInt() to it.value2() }
                    }
                }
            columns(free) shouldBe (1 to 4)
            columns(taken) shouldBe (3 to null)
            deleteItemsOf(tenant)
        }
    }

    /** The queue is shared by the other tests of this JVM: leave no item behind. */
    private fun deleteItemsOf(tenant: UUID) =
        SubmissionDb.superuser().use { c ->
            c.prepareStatement("delete from submission.work_queue where tenant_id = ?").use {
                it.setObject(1, tenant)
                it.executeUpdate()
            }
        }
}
