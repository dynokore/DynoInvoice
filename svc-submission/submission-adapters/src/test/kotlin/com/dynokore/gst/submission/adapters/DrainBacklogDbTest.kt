package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqDrainBacklog
import com.dynokore.gst.submission.adapters.persistence.JooqDrainResidualSource
import com.dynokore.gst.submission.application.drain.QueuedVersion
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

/**
 * The queued-version source against the test database as `submission_runtime`, tenant bound (INV-AC-20847). Rows are
 * seeded through SQL so that the arrangement does not depend on the code under test.
 */
class DrainBacklogDbTest {
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
    private val source = JooqDrainBacklog(dsl, runner)
    private val t0 = Instant.parse("2026-11-01T01:00:00Z")
    private val s = UUID.randomUUID().toString().take(8)

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

    /** One version row with its register entry and, unless [item] is null, a work-queue item in that state. */
    @Suppress("LongParameterList")
    private fun seed(
        tenant: UUID,
        doc: String,
        entity: String,
        period: String,
        queuedAt: Instant,
        item: String? = "PENDING",
    ) = asTenant(tenant) {
        dsl.execute(
            "insert into submission.obligation_register_entry " +
                "(tenant_id, doc_uuid, entity_ref, period_ref, due_date, " +
                "in_scope, kind) values (?, ?, ?, ?, ?::date, true, 'INVOICE')",
            tenant,
            doc,
            entity,
            period,
            LocalDate.of(2026, 11, 30),
        )
        dsl.execute(
            "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref, queued_at) " +
                "values (?, ?, 1, 'QUEUED', ?, ?::timestamptz)",
            tenant,
            doc,
            entity,
            queuedAt.atOffset(ZoneOffset.UTC),
        )
        if (item != null) {
            dsl.execute(
                "insert into submission.work_queue (tenant_id, partition_key, lane, payload, state) " +
                    "values (?, ?, 'LIVE', ?::jsonb, ?)",
                tenant,
                "P-$doc",
                """{"docUuid":"$doc","version":1}""",
                item,
            )
        }
    }

    private fun read(tenant: UUID): List<QueuedVersion>? {
        bound.set(tenant)
        try {
            return source.queued(TenantId(tenant))
        } finally {
            bound.remove()
        }
    }

    private fun scenario(): Pair<UUID, UUID> {
        val t = UUID.randomUUID()
        val u = UUID.randomUUID()
        seed(t, "q2-$s", "E1-$s", "2026-Q1", t0.plusSeconds(60))
        seed(t, "q1-$s", "E1-$s", "2026-Q1", t0)
        seed(t, "q3-$s", "E1-$s", "2026-Q2", t0.plusSeconds(120))
        seed(t, "q4-$s", "E2-$s", "2026-Q1", t0.plusSeconds(180))
        seed(t, "q5-$s", "E2-$s", "2026-Q3", t0.plusSeconds(240))
        seed(t, "q6-$s", "E1-$s", "2026-Q1", t0.plusSeconds(300), item = "CLAIMED")
        seed(u, "u1-$s", "E1-$s", "2026-Q1", t0)
        return t to u
    }

    @Test
    fun `INV-AC-20847 the queued source returns the unclaimed versions of the tenant, oldest queued first`() {
        val (t, _) = scenario()
        val rows = checkNotNull(read(t))
        rows.map { it.docUuid } shouldBe listOf("q1-$s", "q2-$s", "q3-$s", "q4-$s", "q5-$s")
        rows.map { Triple(it.version, it.entityRef, it.periodRef) } shouldBe
            listOf(
                Triple(1, "E1-$s", "2026-Q1"),
                Triple(1, "E1-$s", "2026-Q1"),
                Triple(1, "E1-$s", "2026-Q2"),
                Triple(1, "E2-$s", "2026-Q1"),
                Triple(1, "E2-$s", "2026-Q3"),
            )
        rows.map { it.queuedAt } shouldBe (0L..4L).map { t0.plusSeconds(it * 60) }
    }

    @Test
    fun `INV-AC-20847 a version of another tenant is never returned`() {
        val (t, u) = scenario()
        read(u)!!.map { it.docUuid } shouldBe listOf("u1-$s")
        read(t)!!.none { it.docUuid.startsWith("u1") } shouldBe true
    }

    @Test
    fun `INV-AC-20847 the statements carry the tenant condition themselves when asked for tenant B under tenant A`() {
        val (t, u) = scenario()
        // The connection is bound to t (row level security shows t rows) while the source is asked for u.
        bound.set(t)
        val rows =
            try {
                runner.inTransaction { source.queued(TenantId(u)) }
            } finally {
                bound.remove()
            }
        checkNotNull(rows) shouldBe emptyList()
    }

    /** A DSL over a connection that is already closed, with a runner that opens no transaction of its own. */
    private fun failingDsl() =
        DSL.using(
            SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.close() },
            SQLDialect.POSTGRES,
        )

    private val passThrough =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    @Test
    fun `INV-AC-20847 with the connection failing the queued source returns null, never an empty list`() {
        val t = UUID.randomUUID()
        seed(t, "f1-$s", "E1-$s", "2026-Q1", t0)
        JooqDrainBacklog(failingDsl(), passThrough).queued(TenantId(t)) shouldBe null
    }

    @Test
    fun `INV-AC-20847 with the connection failing the residual source returns null, never an empty list`() {
        val tenant = TenantId(UUID.randomUUID())
        JooqDrainResidualSource(failingDsl(), passThrough).candidates(tenant, "E1-$s", "2026-Q1") shouldBe null
    }
}
