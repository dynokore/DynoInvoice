package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.backlog.BacklogRead
import com.dynokore.gst.submission.application.backlog.BacklogVersionSource
import com.dynokore.gst.submission.application.compliance.ComplianceTenantSource
import com.dynokore.gst.submission.application.queue.TenantTransactions
import com.dynokore.gst.submission.domain.metrics.BacklogVersion
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** The scheduled backlog evaluation over fakes (INV-REQ-1370, INV-AC-20583). */
class BacklogEvaluationJobTest {
    private val now = Instant.parse("2026-11-12T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val t1 = TenantId(UUID.randomUUID())
    private val t2 = TenantId(UUID.randomUUID())
    private val t3 = TenantId(UUID.randomUUID())

    /** Events appended inside a transaction are kept only when the block returns. */
    private class Store {
        val committed = mutableListOf<Pair<TenantId, String>>()
        private val pending = mutableListOf<Pair<TenantId, String>>()
        private var bound: TenantId? = null

        val tenants =
            object : TenantContext {
                override fun current(): TenantId = checkNotNull(bound)

                override fun currentActor(): String = "svc:submission-svc"

                override fun <T> bind(
                    tenant: TenantId,
                    actor: String,
                    block: () -> T,
                ): T {
                    bound = tenant
                    return block()
                }
            }
        val runner =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T {
                    pending.clear()
                    val result = block()
                    committed += pending
                    return result
                }
            }
        val outbox =
            object : OutboxWriter {
                override fun append(
                    events: List<OutboundEvent>,
                    context: EventContext,
                ): List<Envelope> {
                    pending += events.map { context.tenant to it.type }
                    return emptyList()
                }
            }
    }

    private fun version(entity: String) =
        BacklogVersion(
            "doc-$entity",
            1,
            entity,
            "2026Q1",
            LifecycleState.QUEUED,
            now.minusSeconds(60),
            LocalDate.parse("2026-12-01"),
        )

    private fun job(
        store: Store,
        tenants: List<TenantId>,
        source: BacklogVersionSource,
    ) = BacklogEvaluationJob(
        BacklogEvaluationPorts(ComplianceTenantSource { tenants }, source, store.outbox),
        TenantTransactions(store.tenants, store.runner, WorkloadPrincipal("submission-svc")),
        clock,
    )

    private fun source(
        skipped: Int = 0,
        failing: TenantId? = null,
    ) = object : BacklogVersionSource {
        override fun versions(tenant: TenantId): BacklogRead {
            check(tenant != failing) { "secret detail" }
            return BacklogRead(listOf(version("E-${tenant.value}")), skipped)
        }
    }

    private fun captured(block: () -> Unit): List<LogRecord> {
        val logger = Logger.getLogger(BacklogEvaluationJob::class.java.name)
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        logger.addHandler(handler)
        val old = logger.level
        logger.level = Level.ALL
        try {
            block()
        } finally {
            logger.removeHandler(handler)
            logger.level = old
        }
        return records
    }

    private fun LogRecord.text() = java.text.MessageFormat.format(message, *parameters.orEmpty())

    @Test
    fun `INV-AC-20583 second tenant failing gives the first and third events and a failure after the last`() {
        val store = Store()
        shouldThrow<IllegalStateException> { job(store, listOf(t1, t2, t3), source(failing = t2)).run() }
        store.committed.map { it.first } shouldBe listOf(t1, t3)
    }

    @Test
    fun `INV-AC-20583 a clean run returns the number of events written`() {
        val store = Store()
        job(store, listOf(t1, t2, t3), source()).run() shouldBe 3
        store.committed.size shouldBe 3
    }

    @Test
    fun `INV-AC-20583 the failure log line has the tenant id and exception class and no message`() {
        val store = Store()
        val records =
            captured { shouldThrow<IllegalStateException> { job(store, listOf(t2), source(failing = t2)).run() } }
        val line = records.single { it.level == Level.SEVERE }.text()
        line shouldBe "backlog evaluation of tenant ${t2.value} failed: java.lang.IllegalStateException"
        (line.contains("secret detail")) shouldBe false
    }

    @Test
    fun `INV-AC-20583 the job definition carries the name owner scope and the bound tolerance`() {
        val thirty = Duration.ofMinutes(30)
        val d = BacklogEvaluationJob.definition("0 */15 * * * *", thirty)
        d.name shouldBe "backlog-evaluation"
        (d.name == "compliance-evaluation") shouldBe false
        d.ownerService shouldBe "submission-svc"
        d.entityScope shouldBe JobEntityScope.PLATFORM
        d.criticality shouldBe JobCriticality.STANDARD
        d.tolerance shouldBe thirty
        (d.tolerance == Duration.ofHours(1)) shouldBe false
        BacklogEvaluationJob.definition("0 */15 * * * *", Duration.ofHours(1)).tolerance shouldBe Duration.ofHours(1)
    }

    @Test
    fun `INV-AC-20583 two skipped versions log one warning with the tenant id and the number`() {
        val store = Store()
        val records = captured { job(store, listOf(t1), source(skipped = 2)).run() shouldBe 1 }
        val warnings = records.filter { it.level == Level.WARNING }
        warnings.size shouldBe 1
        warnings.single().text() shouldBe "backlog evaluation of tenant ${t1.value} skipped 2 version(s)"
    }

    @Test
    fun `INV-AC-20583 a tenant with nothing skipped logs no warning`() {
        val store = Store()
        val records = captured { job(store, listOf(t1), source()).run() }
        records.none { it.level == Level.WARNING } shouldBe true
    }
}
