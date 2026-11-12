package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqInFlightStore
import com.dynokore.gst.submission.adapters.persistence.JooqReplayCandidateSource
import com.dynokore.gst.submission.adapters.persistence.JooqReplayRequeue
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.SubmissionAttempt
import com.dynokore.gst.submission.application.replay.RequeueOutcome
import com.dynokore.gst.submission.domain.replay.ReplayScope
import com.dynokore.gst.submission.domain.replay.ReplayScopeSelector
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The replay candidate source and requeue adapter against the test database as `submission_runtime`. */
class ReplayAdaptersDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()
    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, checkNotNull(bound.get()))
                return try {
                    block().also { connection.commit() }
                } catch (e: Throwable) {
                    connection.rollback()
                    throw e
                }
            }
        }
    private val entries = mutableListOf<QueueEntry>()
    private val queue =
        object : WorkQueue {
            override fun enqueue(entry: QueueEntry): Long = entries.add(entry).let { entries.size.toLong() }

            override fun claimNext(): ClaimedItem? = null

            override fun payloadOf(item: ClaimedItem): String = error("not used")

            override fun complete(item: ClaimedItem) = Unit

            override fun release(item: ClaimedItem) = Unit
        }
    private val source = JooqReplayCandidateSource(dsl, runner)
    private val requeue = JooqReplayRequeue(dsl, runner, JooqInFlightStore(dsl), queue)
    private val sub = Submission.SUBMISSION_
    private val att = SubmissionAttempt.SUBMISSION_ATTEMPT
    private val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val s = UUID.randomUUID().toString().take(8)
    private val e = "e-$s"
    private val p = "p-$s"
    private val t0 = Instant.parse("2026-11-01T01:00:00Z")

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        bound.set(tenant)
        try {
            return runner.inTransaction(block)
        } finally {
            bound.remove()
        }
    }

    /** A version row of [doc]; [classes] are the failure classes of attempts 1, 2, ... (null = no class stored). */
    @Suppress("LongParameterList")
    private fun seed(
        tenant: UUID,
        doc: String,
        version: Int,
        state: String,
        classes: List<String?> = emptyList(),
        withEntry: Boolean = true,
        retrying: Boolean = false,
    ) = asTenant(tenant) {
        if (withEntry && version == 1) {
            dsl
                .insertInto(entry)
                .set(entry.TENANT_ID, tenant)
                .set(entry.DOC_UUID, doc)
                .set(entry.ENTITY_REF, e)
                .set(entry.PERIOD_REF, p)
                .set(entry.DUE_DATE, LocalDate.of(2026, 11, 30))
                .set(entry.IN_SCOPE, true)
                .set(entry.KIND, "INVOICE")
                .execute()
        }
        dsl
            .insertInto(sub)
            .set(sub.TENANT_ID, tenant)
            .set(sub.DOC_UUID, doc)
            .set(sub.VERSION, version)
            .set(sub.STATE, state)
            .set(sub.ENTITY_REF, e)
            .set(sub.RETRY_FAILURES, if (retrying) 2 else 0)
            .set(sub.RETRY_FIRST_FAILURE_AT, if (retrying) t0.atOffset(ZoneOffset.UTC) else null)
            .set(sub.NEXT_ATTEMPT_AT, if (retrying) t0.atOffset(ZoneOffset.UTC) else null)
            .execute()
        classes.forEachIndexed { i, c ->
            dsl
                .insertInto(att)
                .set(att.TENANT_ID, tenant)
                .set(att.DOC_UUID, doc)
                .set(att.VERSION, version)
                .set(att.ATTEMPT_NUMBER, i + 1)
                .set(att.CORRELATION_ID, UUID.randomUUID())
                .set(att.REQUESTED_AT, t0.plusSeconds(i.toLong()).atOffset(ZoneOffset.UTC))
                .set(att.FAILURE_CLASS, c)
                .execute()
        }
    }

    private fun stateOf(
        tenant: UUID,
        doc: String,
        version: Int,
    ): String? =
        asTenant(tenant) {
            dsl
                .select(sub.STATE)
                .from(sub)
                .where(sub.DOC_UUID.eq(doc))
                .and(sub.VERSION.eq(version))
                .fetchOne(sub.STATE)
        }

    private fun candidates(tenant: UUID) = asTenant(tenant) { source.candidates(TenantId(tenant), e) }

    private fun requeue(
        tenant: UUID,
        doc: String,
        version: Int = 1,
    ): RequeueOutcome = asTenant(tenant) { requeue.requeue(TenantId(tenant), doc, version) }

    @Suppress("LongParameterList")
    private class Docs(
        val a: String,
        val b: String,
        val c: String,
        val d: String,
        val noEntry: String,
        val sent: String,
        val other: String,
    )

    private fun seedAll(): Docs {
        val docs = Docs("a-$s", "b-$s", "c-$s", "d-$s", "ne-$s", "sent-$s", "b2-$s")
        seed(tenantA, docs.a, 1, "DEAD_LETTERED", listOf("SUB-002", "SUB-001"), retrying = true)
        seed(tenantA, docs.b, 1, "DEAD_LETTERED", listOf("SUB-001", "SUB-002"))
        seed(tenantA, docs.c, 1, "DEAD_LETTERED", listOf(null))
        seed(tenantA, docs.d, 1, "DEAD_LETTERED", listOf("SUB-002"))
        seed(tenantA, docs.d, 2, "SENT")
        seed(tenantA, docs.noEntry, 1, "DEAD_LETTERED", listOf("SUB-001"), withEntry = false)
        seed(tenantA, docs.sent, 1, "SENT", listOf("SUB-001"))
        seed(tenantB, docs.other, 1, "DEAD_LETTERED", listOf("SUB-001"))
        return docs
    }

    private fun scope(failureClass: String?) = ReplayScope(e, p, null, failureClass)

    private fun selected(
        all: List<com.dynokore.gst.submission.domain.replay.ReplayCandidate>,
        failureClass: String?,
    ) = ReplayScopeSelector
        .select(all, scope(failureClass), 100)
        .selected
        .map { it.docUuid }
        .toSet()

    @Test
    fun `INV-AC-20586 R1 the candidate carries the class of its highest attempt and an older class is ignored`() {
        val d = seedAll()
        val all = candidates(tenantA)

        all.associate { it.docUuid to it.failureClass } shouldBe
            mapOf(d.a to "SUB-001", d.b to "SUB-002", d.c to null, d.d to "SUB-002")
        all.first { it.docUuid == d.a }.queuedAt shouldBe t0
        all.first { it.docUuid == d.a }.periodRef shouldBe p
        all.first { it.docUuid == d.a }.dueDate shouldBe LocalDate.of(2026, 11, 30)
    }

    @Test
    fun `INV-AC-20586 R2 the SUB-001 scope selects a only and the scope without a class selects a, b, c and d`() {
        val d = seedAll()
        val all = candidates(tenantA)

        selected(all, "SUB-001") shouldBe setOf(d.a)
        selected(all, null) shouldBe setOf(d.a, d.b, d.c, d.d)
    }

    @Test
    fun `INV-AC-20586 R3 requeue twice gives REQUEUED then ALREADY_QUEUED with one item and the series cleared`() {
        val d = seedAll()

        requeue(tenantA, d.a) shouldBe RequeueOutcome.REQUEUED
        requeue(tenantA, d.a) shouldBe RequeueOutcome.ALREADY_QUEUED

        entries.size shouldBe 1
        entries[0].lane shouldBe Lane.REPLAY
        entries[0].partitionKey shouldBe d.a
        entries[0].payloadJson shouldBe """{"docUuid":"${d.a}","version":1}"""
        stateOf(tenantA, d.a, 1) shouldBe "QUEUED"
        asTenant(tenantA) {
            val r = dsl.selectFrom(sub).where(sub.DOC_UUID.eq(d.a)).fetchSingle()
            Triple(r.retryFailures, r.retryFirstFailureAt, r.nextAttemptAt) shouldBe Triple(0, null, null)
        }
    }

    @Test
    fun `INV-AC-20586 R4 d answers IN_FLIGHT_CONFLICT and keeps its state, writing nothing`() {
        val d = seedAll()

        requeue(tenantA, d.d) shouldBe RequeueOutcome.IN_FLIGHT_CONFLICT

        stateOf(tenantA, d.d, 1) shouldBe "DEAD_LETTERED"
        stateOf(tenantA, d.d, 2) shouldBe "SENT"
        entries.size shouldBe 0
    }

    @Test
    fun `INV-AC-20586 R5 a SENT version and a version that does not exist make requeue throw and write nothing`() {
        val d = seedAll()

        shouldThrow<IllegalStateException> { requeue(tenantA, d.sent) }
        shouldThrow<IllegalStateException> { requeue(tenantA, d.a, 7) }
        shouldThrow<IllegalStateException> { requeue(tenantA, "missing-$s") }

        stateOf(tenantA, d.sent, 1) shouldBe "SENT"
        stateOf(tenantA, d.a, 1) shouldBe "DEAD_LETTERED"
        entries.size shouldBe 0
    }

    @Test
    fun `INV-AC-20586 R6 a second tenant's rows are never read or written`() {
        val d = seedAll()

        candidates(tenantA).none { it.docUuid == d.other } shouldBe true
        candidates(tenantB).map { it.docUuid } shouldBe listOf(d.other)
        shouldThrow<IllegalStateException> { requeue(tenantA, d.other) }
        requeue(tenantA, d.a) shouldBe RequeueOutcome.REQUEUED

        stateOf(tenantB, d.other, 1) shouldBe "DEAD_LETTERED"
    }

    @Test
    fun `INV-AC-20586 R7 a dead-lettered version without a register entry is not a candidate`() {
        val d = seedAll()

        candidates(tenantA).none { it.docUuid == d.noEntry } shouldBe true
    }
}
