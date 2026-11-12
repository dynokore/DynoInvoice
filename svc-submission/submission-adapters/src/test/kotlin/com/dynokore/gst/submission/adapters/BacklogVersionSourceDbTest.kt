package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqBacklogVersionSource
import com.dynokore.gst.submission.adapters.persistence.JooqInFlightStore
import com.dynokore.gst.submission.adapters.persistence.JooqReplayRequeue
import com.dynokore.gst.submission.adapters.persistence.JooqSubmissionStore
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.SubmissionAttempt
import com.dynokore.gst.submission.application.backlog.BacklogRead
import com.dynokore.gst.submission.application.queue.NewReadySubmission
import com.dynokore.gst.submission.application.replay.RequeueOutcome
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The backlog version reader against the test database as `submission_runtime`, tenant bound (INV-AC-20581). */
class BacklogVersionSourceDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()
    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, checkNotNull(bound.get()))
                return block().also { connection.commit() }
            }
        }
    private val source = JooqBacklogVersionSource(dsl, runner)
    private val store = JooqSubmissionStore(dsl)
    private val sub = Submission.SUBMISSION_
    private val att = SubmissionAttempt.SUBMISSION_ATTEMPT
    private val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
    private val due = LocalDate.of(2026, 11, 30)
    private val t0 = Instant.parse("2026-11-01T01:00:00Z")
    private val s = UUID.randomUUID().toString().take(8)
    private val e = "e-$s"
    private val p = "p-$s"

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

    private fun read(tenant: UUID): BacklogRead {
        bound.set(tenant)
        try {
            return source.versions(TenantId(tenant))
        } finally {
            bound.remove()
        }
    }

    /** One version row (and its register entry unless [withEntry] is false); [attempts] are attempt instants. */
    @Suppress("LongParameterList")
    private fun seed(
        tenant: UUID,
        doc: String,
        state: LifecycleState,
        queuedAt: Instant? = null,
        attempts: List<Instant> = emptyList(),
        withEntry: Boolean = true,
    ) = asTenant(tenant) {
        if (withEntry) {
            dsl
                .insertInto(entry)
                .set(entry.TENANT_ID, tenant)
                .set(entry.DOC_UUID, doc)
                .set(entry.ENTITY_REF, e)
                .set(entry.PERIOD_REF, p)
                .set(entry.DUE_DATE, due)
                .set(entry.IN_SCOPE, true)
                .set(entry.KIND, "INVOICE")
                .execute()
        }
        val retrying = state == LifecycleState.RETRY
        dsl
            .insertInto(sub)
            .set(sub.TENANT_ID, tenant)
            .set(sub.DOC_UUID, doc)
            .set(sub.VERSION, 1)
            .set(sub.STATE, state.name)
            .set(sub.ENTITY_REF, e)
            .set(sub.QUEUED_AT, queuedAt?.atOffset(ZoneOffset.UTC))
            .set(sub.RETRY_FAILURES, if (retrying) 1 else 0)
            .set(sub.RETRY_FIRST_FAILURE_AT, if (retrying) t0.atOffset(ZoneOffset.UTC) else null)
            .set(sub.NEXT_ATTEMPT_AT, if (retrying) t0.atOffset(ZoneOffset.UTC) else null)
            .execute()
        attempts.forEachIndexed { i, at ->
            dsl
                .insertInto(att)
                .set(att.TENANT_ID, tenant)
                .set(att.DOC_UUID, doc)
                .set(att.VERSION, 1)
                .set(att.ATTEMPT_NUMBER, i + 1)
                .set(att.CORRELATION_ID, UUID.randomUUID())
                .set(att.REQUESTED_AT, at.atOffset(ZoneOffset.UTC))
                .execute()
        }
    }

    private fun queuedAtOf(
        tenant: UUID,
        doc: String,
    ): Instant? =
        asTenant(tenant) {
            dsl
                .select(sub.QUEUED_AT)
                .from(sub)
                .where(sub.DOC_UUID.eq(doc))
                .fetchOne(sub.QUEUED_AT)
                ?.toInstant()
        }

    @Test
    fun `INV-AC-20581 the six states map to the expected versions with their queued instants`() {
        val a = UUID.randomUUID()
        val states =
            listOf(
                LifecycleState.QUEUED,
                LifecycleState.RETRY,
                LifecycleState.DEAD_LETTERED,
                LifecycleState.SENT,
                LifecycleState.SENT_UNCONFIRMED,
                LifecycleState.ACKNOWLEDGED,
            )
        states.forEachIndexed { i, st -> seed(a, "d$i-$s", st, queuedAt = t0.plusSeconds(i * 60L)) }
        val read = read(a)
        read.skipped shouldBe 0
        read.versions.sortedBy { it.docUuid }.map { Triple(it.docUuid, it.state, it.queuedAt) } shouldBe
            states.mapIndexed { i, st -> Triple("d$i-$s", st, t0.plusSeconds(i * 60L)) }
        read.versions.forEach {
            it.entityRef shouldBe e
            it.periodRef shouldBe p
            it.dueDate shouldBe due
            it.version shouldBe 1
        }
    }

    @Test
    fun `INV-AC-20581 a QUEUED version with NULL queued_at takes the earliest attempt requested_at`() {
        val a = UUID.randomUUID()
        seed(a, "n1-$s", LifecycleState.QUEUED, attempts = listOf(t0.plusSeconds(30), t0.plusSeconds(5)))
        val read = read(a)
        read.skipped shouldBe 0
        read.versions.single().queuedAt shouldBe t0.plusSeconds(5)
    }

    @Test
    fun `INV-AC-20581 a QUEUED version with NULL queued_at and no attempt row is skipped and counted`() {
        val a = UUID.randomUUID()
        seed(a, "n2-$s", LifecycleState.QUEUED)
        seed(a, "ok-$s", LifecycleState.QUEUED, queuedAt = t0)
        val read = read(a)
        read.skipped shouldBe 1
        read.versions.map { it.docUuid } shouldBe listOf("ok-$s")
    }

    @Test
    fun `INV-AC-20581 a version without a register entry is skipped and counted`() {
        val a = UUID.randomUUID()
        seed(a, "noentry-$s", LifecycleState.QUEUED, queuedAt = t0, withEntry = false)
        val read = read(a)
        read.skipped shouldBe 1
        read.versions shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20581 a second tenant's rows are never read`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        seed(a, "a1-$s", LifecycleState.QUEUED, queuedAt = t0)
        seed(b, "b1-$s", LifecycleState.QUEUED, queuedAt = t0)
        seed(b, "b2-$s", LifecycleState.SENT, withEntry = false)
        val read = read(a)
        read.versions.map { it.docUuid } shouldBe listOf("a1-$s")
        read.skipped shouldBe 0
    }

    @Test
    fun `INV-AC-20581 moveReadyToQueued sets queued_at and a later RETRY keeps it`() {
        val a = UUID.randomUUID()
        val doc = "mv-$s"
        asTenant(a) {
            store.insertReady(
                TenantId(a),
                NewReadySubmission(doc, 1, e, null, byteArrayOf(1), "sha-$doc", SubmissionKind.TYPE_1A),
            )
        }
        queuedAtOf(a, doc) shouldBe null
        val before = Instant.now().minusSeconds(5)
        asTenant(a) { store.moveReadyToQueued(TenantId(a), doc, 1) } shouldBe 1
        val queued = checkNotNull(queuedAtOf(a, doc))
        (queued.isAfter(before) && !queued.isAfter(Instant.now().plusSeconds(5))) shouldBe true
        asTenant(a) {
            dsl
                .update(sub)
                .set(sub.STATE, LifecycleState.RETRY.name)
                .set(sub.RETRY_FAILURES, 1)
                .set(sub.RETRY_FIRST_FAILURE_AT, t0.atOffset(ZoneOffset.UTC))
                .set(sub.NEXT_ATTEMPT_AT, t0.atOffset(ZoneOffset.UTC))
                .where(sub.DOC_UUID.eq(doc))
                .execute()
        }
        queuedAtOf(a, doc) shouldBe queued
    }

    @Test
    fun `INV-AC-20581 a replay requeue of a DEAD_LETTERED version sets queued_at to the requeue instant`() {
        val a = UUID.randomUUID()
        val doc = "rq-$s"
        val old = t0
        seed(a, doc, LifecycleState.DEAD_LETTERED, queuedAt = old)
        val queue =
            object : WorkQueue {
                override fun enqueue(entry: QueueEntry): Long = 1L

                override fun claimNext(): ClaimedItem? = null

                override fun payloadOf(item: ClaimedItem): String = error("not used")

                override fun complete(item: ClaimedItem) = Unit

                override fun release(item: ClaimedItem) = Unit
            }
        val before = Instant.now().minusSeconds(5)
        asTenant(
            a,
        ) { JooqReplayRequeue(dsl, runner, JooqInFlightStore(dsl), queue).requeue(TenantId(a), doc, 1) } shouldBe
            RequeueOutcome.REQUEUED
        val queued = checkNotNull(queuedAtOf(a, doc))
        (queued.isAfter(before) && queued.isAfter(old)) shouldBe true
        val row =
            asTenant(a) {
                dsl
                    .select(
                        sub.STATE,
                        sub.RETRY_FAILURES,
                        sub.NEXT_ATTEMPT_AT,
                    ).from(sub)
                    .where(sub.DOC_UUID.eq(doc))
                    .fetchOne()
            }
        checkNotNull(row).get(sub.STATE) shouldBe "QUEUED"
        row.get(sub.RETRY_FAILURES) shouldBe 0
        row.get(sub.NEXT_ATTEMPT_AT) shouldBe null
    }
}
