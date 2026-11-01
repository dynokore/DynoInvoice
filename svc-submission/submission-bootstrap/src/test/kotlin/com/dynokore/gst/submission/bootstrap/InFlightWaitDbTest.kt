package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WorkQueue
import com.dynokore.gst.submission.application.queue.EnqueueResult
import com.dynokore.gst.submission.application.queue.InFlightBreach
import com.dynokore.gst.submission.application.queue.InFlightStore
import com.dynokore.gst.submission.application.queue.ReleaseResult
import com.dynokore.gst.submission.application.queue.SubmissionQueue
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.gst.submission.application.queue.TenantTransactions
import com.dynokore.gst.submission.application.queue.WaitingSweeper
import com.dynokore.gst.submission.domain.ordering.InFlight
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The wait of a later version and its release (INV-REQ-571, INV-REQ-548 behaviour 4) on a real database. */
class InFlightWaitDbTest {
    private val actor = "svc:submission-svc"

    private class Boundary(
        context: ApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val queue: SubmissionQueue = context.getBean(SubmissionQueue::class.java)
        val store: SubmissionStore = context.getBean(SubmissionStore::class.java)
        val versions: InFlightStore = context.getBean(InFlightStore::class.java)
        val scope: TenantTransactions = context.getBean(TenantTransactions::class.java)
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        SubmissionContext.online(dir).run { block(Boundary(it)) }
    }

    private fun <T> Boundary.asTenant(
        tenant: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(tenant), actor) { runner.inTransaction(block) }

    private fun Boundary.seed(
        tenant: UUID,
        doc: String,
        version: Int,
        state: String,
    ) = asTenant(tenant) {
        dsl
            .insertInto(Submission.SUBMISSION_)
            .set(Submission.SUBMISSION_.TENANT_ID, tenant)
            .set(Submission.SUBMISSION_.DOC_UUID, doc)
            .set(Submission.SUBMISSION_.VERSION, version)
            .set(Submission.SUBMISSION_.STATE, state)
            .set(Submission.SUBMISSION_.ENTITY_REF, "E-001")
            .execute()
    }

    private fun Boundary.setState(
        tenant: UUID,
        doc: String,
        version: Int,
        state: String,
    ) = asTenant(tenant) {
        dsl
            .update(Submission.SUBMISSION_)
            .set(Submission.SUBMISSION_.STATE, state)
            .where(Submission.SUBMISSION_.DOC_UUID.eq(doc))
            .and(Submission.SUBMISSION_.VERSION.eq(version))
            .execute()
    }

    private fun Boundary.states(
        tenant: UUID,
        doc: String,
    ): Map<Int, String> =
        asTenant(tenant) {
            dsl
                .select(Submission.SUBMISSION_.VERSION, Submission.SUBMISSION_.STATE)
                .from(Submission.SUBMISSION_)
                .where(Submission.SUBMISSION_.DOC_UUID.eq(doc))
                .fetch()
                .associate { checkNotNull(it.value1()) to checkNotNull(it.value2()) }
        }

    /** The versions of the payloads of the `work_queue` items of the Doc UUID. */
    private fun Boundary.itemVersions(
        tenant: UUID,
        doc: String,
    ): List<Int> =
        asTenant(tenant) {
            dsl
                .select(WorkQueue.WORK_QUEUE.PAYLOAD)
                .from(WorkQueue.WORK_QUEUE)
                .where(WorkQueue.WORK_QUEUE.PARTITION_KEY.eq(doc))
                .fetch(WorkQueue.WORK_QUEUE.PAYLOAD)
                .map { Regex("\"version\":\\s*(\\d+)").find(checkNotNull(it).data())!!.groupValues[1].toInt() }
                .sorted()
        }

    @Test
    fun `INV-AC-610 version 2 waits while version 1 is in flight and is released after version 1 is acknowledged`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val tenant = UUID.randomUUID()
        for (blocking in listOf("RETRY", "QUEUED", "SENT", "SENT_UNCONFIRMED")) {
            val doc = UUID.randomUUID().toString()
            b.seed(tenant, doc, 1, blocking)
            b.seed(tenant, doc, 2, "READY")

            b.asTenant(tenant) { b.queue.enqueue(doc, 2) } shouldBe EnqueueResult.Waiting(1)

            b.states(tenant, doc) shouldBe mapOf(1 to blocking, 2 to "READY")
            b.itemVersions(tenant, doc).contains(2) shouldBe false
            b.asTenant(tenant) { b.queue.releaseNext(doc) } shouldBe ReleaseResult.NothingToRelease
        }
        val doc = UUID.randomUUID().toString()
        b.seed(tenant, doc, 1, "RETRY")
        b.seed(tenant, doc, 2, "READY")
        b.asTenant(tenant) { b.queue.enqueue(doc, 2) } shouldBe EnqueueResult.Waiting(1)

        b.setState(tenant, doc, 1, "ACKNOWLEDGED")
        b.asTenant(tenant) { b.queue.releaseNext(doc) } shouldBe ReleaseResult.Released(2)

        b.states(tenant, doc) shouldBe mapOf(1 to "ACKNOWLEDGED", 2 to "QUEUED")
        b.itemVersions(tenant, doc) shouldBe listOf(2)
    }

    @Test
    fun `the update that bypasses the check throws InFlightBreach with SUB-008 and leaves both rows unchanged`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        b.seed(tenant, doc, 1, "SENT")
        b.seed(tenant, doc, 2, "READY")

        val breach =
            shouldThrow<InFlightBreach> {
                b.asTenant(tenant) { b.store.moveReadyToQueued(TenantId(tenant), doc, 2) }
            }

        breach.code shouldBe "SUB-008"
        breach.code shouldBe InFlight.SUB_008
        breach.docUuid shouldBe doc
        breach.version shouldBe 2
        b.states(tenant, doc) shouldBe mapOf(1 to "SENT", 2 to "READY")
        b.itemVersions(tenant, doc) shouldBe emptyList()
    }

    @Test
    fun `two threads enqueueing versions 2 and 3 give one Queued and one Waiting and no exception`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val tenant = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(2)
        try {
            // many documents: a missing row lock shows as a breach only when the two updates overlap
            repeat(RACES) { _ ->
                val doc = UUID.randomUUID().toString()
                b.seed(tenant, doc, 1, "ACKNOWLEDGED")
                b.seed(tenant, doc, 2, "READY")
                b.seed(tenant, doc, 3, "READY")
                val barrier = CyclicBarrier(2)
                val results =
                    listOf(2, 3)
                        .map { v ->
                            pool.submit<Result<EnqueueResult>> {
                                runCatching {
                                    b.asTenant(tenant) {
                                        barrier.await()
                                        b.queue.enqueue(doc, v)
                                    }
                                }
                            }
                        }.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }

                results.mapNotNull { it.exceptionOrNull() } shouldBe emptyList()
                val values = results.map { it.getOrThrow() }
                values.count { it == EnqueueResult.Queued } shouldBe 1
                values.count { it is EnqueueResult.Waiting } shouldBe 1
                b.itemVersions(tenant, doc).size shouldBe 1
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `sweep releases version 2 when version 1 left flight without the outcome call`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        val blocked = UUID.randomUUID().toString()
        b.seed(tenant, doc, 1, "ACKNOWLEDGED")
        b.seed(tenant, doc, 2, "READY")
        b.seed(tenant, blocked, 1, "SENT")
        b.seed(tenant, blocked, 2, "READY")
        val sweeper = WaitingSweeper({ listOf(TenantId(tenant)) }, b.scope, b.versions, b.queue)

        sweeper.sweep() shouldBe 1

        b.states(tenant, doc) shouldBe mapOf(1 to "ACKNOWLEDGED", 2 to "QUEUED")
        b.itemVersions(tenant, doc) shouldBe listOf(2)
        b.states(tenant, blocked) shouldBe mapOf(1 to "SENT", 2 to "READY")
        sweeper.sweep() shouldBe 0
    }

    @Test
    fun `releaseNext releases version 2 before version 3 when both are READY`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        b.seed(tenant, doc, 3, "READY")
        b.seed(tenant, doc, 2, "READY")
        b.seed(tenant, doc, 1, "REJECTED")

        b.asTenant(tenant) { b.queue.releaseNext(doc) } shouldBe ReleaseResult.Released(2)

        b.states(tenant, doc) shouldBe mapOf(1 to "REJECTED", 2 to "QUEUED", 3 to "READY")
        b.asTenant(tenant) { b.queue.releaseNext(doc) } shouldBe ReleaseResult.NothingToRelease
    }

    @Test
    fun `the sweeper interval is 60 seconds and the batch 100`() {
        WaitingSweeper.INTERVAL.seconds shouldBe 60
        WaitingSweeper.BATCH shouldBe 100
    }

    private companion object {
        const val WAIT_SECONDS = 60L
        const val RACES = 30
    }
}
