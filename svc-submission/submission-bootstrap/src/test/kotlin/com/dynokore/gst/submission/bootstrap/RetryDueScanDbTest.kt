package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WorkQueue
import com.dynokore.gst.submission.application.queue.RetrySchedule
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** The retry due-scan against a real database (INV-Q-7266 A "Retry delay"). */
class RetryDueScanDbTest {
    private val due = Instant.parse("2026-11-01T10:00:00Z")

    @Test
    fun `RetryDueScan called twice with the same now enqueues once and clears next_attempt_at`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.online(dir).run { context ->
            val f = OutcomeFixture(context)
            val tenant = UUID.randomUUID()
            val dueDoc = UUID.randomUUID().toString()
            val laterDoc = UUID.randomUUID().toString()
            val sentDoc = UUID.randomUUID().toString()
            f.seed(tenant, dueDoc, LifecycleState.RETRY)
            f.seed(tenant, laterDoc, LifecycleState.RETRY)
            f.seed(tenant, sentDoc, LifecycleState.SENT)
            f.asTenant(tenant) {
                f.store.scheduleRetry(TenantId(tenant), dueDoc, 1, RetrySchedule(due, 1, due))
                f.store.scheduleRetry(TenantId(tenant), laterDoc, 1, RetrySchedule(due.plusSeconds(1), 1, due))
            }

            f.asTenant(tenant) { f.scan.run(due) } shouldBe 1
            f.asTenant(tenant) { f.scan.run(due) } shouldBe 0

            val items =
                f.asTenant(tenant) {
                    f.dsl
                        .selectFrom(WorkQueue.WORK_QUEUE)
                        .fetch()
                        .toList()
                }
            items.map { it.partitionKey to it.lane } shouldBe listOf(dueDoc to "LIVE")
            checkNotNull(items[0].payload).data().replace(" ", "") shouldBe """{"docUuid":"$dueDoc","version":1}"""
            f.row(tenant, dueDoc).nextAttemptAt shouldBe null
            (f.row(tenant, laterDoc).nextAttemptAt != null) shouldBe true
        }
    }
}
