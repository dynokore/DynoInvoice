package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.SubmissionDb
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WorkQueue
import com.dynokore.gst.submission.application.queue.SubmissionQueue
import com.dynokore.gst.submission.application.queue.VersionNotReady
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.io.File
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * `SubmissionQueue.enqueue` against a real database (P109): state and queue item commit together or not at all
 * (INV-REQ-1156, INV-AC-2140).
 */
class SubmissionQueueDbTest {
    private val actor = "svc:submission-svc"

    private class Boundary(
        context: ApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val queue: SubmissionQueue = context.getBean(SubmissionQueue::class.java)
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> block(Boundary(context)) }
    }

    private fun <T> Boundary.asTenant(
        tenant: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(tenant), actor) { runner.inTransaction(block) }

    private fun Boundary.seed(
        tenant: UUID,
        docUuid: String,
        state: String = "READY",
    ) = asTenant(tenant) {
        dsl
            .insertInto(Submission.SUBMISSION_)
            .set(Submission.SUBMISSION_.TENANT_ID, tenant)
            .set(Submission.SUBMISSION_.DOC_UUID, docUuid)
            .set(Submission.SUBMISSION_.VERSION, 1)
            .set(Submission.SUBMISSION_.STATE, state)
            .execute()
    }

    private fun Boundary.stateOf(
        tenant: UUID,
        docUuid: String,
    ): String? =
        asTenant(tenant) {
            dsl
                .select(Submission.SUBMISSION_.STATE)
                .from(Submission.SUBMISSION_)
                .where(Submission.SUBMISSION_.DOC_UUID.eq(docUuid))
                .fetchOne(Submission.SUBMISSION_.STATE)
        }

    private fun Boundary.itemsOf(
        tenant: UUID,
        docUuid: String,
    ): List<String> =
        asTenant(tenant) {
            dsl
                .select(WorkQueue.WORK_QUEUE.PAYLOAD)
                .from(WorkQueue.WORK_QUEUE)
                .where(WorkQueue.WORK_QUEUE.PARTITION_KEY.eq(docUuid))
                .fetch(WorkQueue.WORK_QUEUE.PAYLOAD)
                .map {
                    checkNotNull(it)
                        .data()
                        .removeSurrounding("{", "}")
                        .replace(" ", "")
                        .split(',')
                        .sorted()
                        .joinToString(",")
                }
        }

    /** Starts the helper JVM, waits for its two writes, kills it with SIGKILL before it can commit. */
    private fun killedAfterBothWrites(
        dir: Path,
        tenant: UUID,
        docUuid: String,
    ) {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process =
            ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                EnqueueHelperJvm::class.java.name,
                SubmissionDb.jdbcUrl,
                dir.toString(),
                tenant.toString(),
                docUuid,
                "1",
            ).redirectError(ProcessBuilder.Redirect.to(File(dir.toFile(), "helper-stderr.log")))
                .start()
        try {
            val lines = process.inputReader()
            val written = generateSequence { lines.readLine() }.any { it == EnqueueHelperJvm.WRITTEN }
            check(
                written,
            ) { "the helper JVM ended before its writes: ${File(dir.toFile(), "helper-stderr.log").readText()}" }
        } finally {
            process.destroyForcibly()
            process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)
        }
        check(
            process.exitValue() == SIGKILL_EXIT,
        ) { "the helper JVM was not killed by SIGKILL: ${process.exitValue()}" }
    }

    @Test
    fun `INV-AC-2140 a kill before commit leaves READY and no item and a second enqueue queues it once`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        boundary.seed(tenant, doc)

        killedAfterBothWrites(dir, tenant, doc)

        boundary.stateOf(tenant, doc) shouldBe "READY"
        boundary.itemsOf(tenant, doc) shouldBe emptyList()

        boundary.asTenant(tenant) { boundary.queue.enqueue(doc, 1) }

        boundary.stateOf(tenant, doc) shouldBe "QUEUED"
        boundary.itemsOf(tenant, doc) shouldBe listOf(""""docUuid":"$doc","version":1""")
    }

    @Test
    fun `a forced exception after the second write rolls both writes back`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        boundary.seed(tenant, doc)

        shouldThrow<IllegalStateException> {
            boundary.asTenant(tenant) {
                boundary.queue.enqueue(doc, 1)
                check(false) { "forced failure after both writes" }
            }
        }

        boundary.stateOf(tenant, doc) shouldBe "READY"
        boundary.itemsOf(tenant, doc) shouldBe emptyList()
    }

    @Test
    fun `enqueuing a version that is not READY throws and writes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        val queued = UUID.randomUUID().toString()
        val missing = UUID.randomUUID().toString()
        boundary.seed(tenant, queued, state = "SENT")

        shouldThrow<VersionNotReady> { boundary.asTenant(tenant) { boundary.queue.enqueue(queued, 1) } }
        shouldThrow<VersionNotReady> { boundary.asTenant(tenant) { boundary.queue.enqueue(missing, 1) } }

        boundary.stateOf(tenant, queued) shouldBe "SENT"
        boundary.itemsOf(tenant, queued) shouldBe emptyList()
        boundary.itemsOf(tenant, missing) shouldBe emptyList()
    }

    @Test
    fun `a second enqueue of the same version throws and leaves one item`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        boundary.seed(tenant, doc)

        boundary.asTenant(tenant) { boundary.queue.enqueue(doc, 1) }
        shouldThrow<VersionNotReady> { boundary.asTenant(tenant) { boundary.queue.enqueue(doc, 1) } }

        boundary.itemsOf(tenant, doc).size shouldBe 1
    }

    private companion object {
        const val WAIT_SECONDS = 30L

        /** 128 + 9: the exit status of a JVM killed by SIGKILL. */
        const val SIGKILL_EXIT = 137
    }
}
