package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqDrainResidualSource
import com.dynokore.gst.submission.application.drain.ResidualKind
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

/** The residual source against the test database as `submission_runtime`, tenant bound (INV-AC-20847). */
class DrainResidualSourceDbTest {
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
    private val source = JooqDrainResidualSource(dsl, runner)
    private val t0 = Instant.parse("2026-11-01T01:00:00Z")
    private val s = UUID.randomUUID().toString().take(8)
    private val e1 = "E1-$s"
    private val e2 = "E2-$s"
    private val q1 = "2026-Q1"
    private val q2 = "2026-Q2"

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

    private fun provisional(
        tenant: UUID,
        doc: String,
        entity: String,
        period: String,
        reason: String?,
    ) = asTenant(tenant) {
        dsl.execute(
            "insert into submission.provisional_register_entry " +
                "(tenant_id, doc_uuid, entity_ref, period_ref, direction, " +
                "document_date, transaction_date, reason_code, docsequence, held_at) " +
                "values (?, ?, ?, ?, 'OUTBOUND', ?::date, ?::date, ?, 1, ?::timestamptz)",
            tenant,
            doc,
            entity,
            period,
            LocalDate.of(2026, 1, 5),
            LocalDate.of(2026, 1, 5),
            reason,
            t0.atOffset(ZoneOffset.UTC),
        )
    }

    /** A version in [state] with a register entry and one attempt per entry of [codes] (null = no failure code). */
    @Suppress("LongParameterList")
    private fun version(
        tenant: UUID,
        doc: String,
        entity: String,
        period: String,
        state: String,
        vararg codes: String?,
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
        val number = if (codes.size >= 2) 2 else 1
        dsl.execute(
            "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref) " +
                "values (?, ?, ?, ?, ?)",
            tenant,
            doc,
            number,
            state,
            entity,
        )
        codes.forEachIndexed { i, code ->
            dsl.execute(
                "insert into submission.submission_attempt (tenant_id, doc_uuid, version, attempt_number, " +
                    "correlation_id, requested_at, failure_class) values (?, ?, ?, ?, ?::uuid, ?::timestamptz, ?)",
                tenant,
                doc,
                number,
                i + 1,
                UUID.randomUUID(),
                t0.plusSeconds(i * 10L).atOffset(ZoneOffset.UTC),
                code,
            )
        }
    }

    private fun scenario(): Pair<UUID, UUID> {
        val t = UUID.randomUUID()
        val u = UUID.randomUUID()
        provisional(t, "p1-$s", e1, q1, "MDM-001")
        provisional(t, "p2-$s", e1, q1, null)
        provisional(t, "p3-$s", e1, q2, "MDM-002")
        version(t, "va-$s", e1, q1, "DEAD_LETTERED", "PAY-001", "PAY-014")
        version(t, "vb-$s", e1, q1, "DEAD_LETTERED", null)
        for (state in listOf("SENT", "REJECTED", "BLOCKED", "HELD")) {
            version(t, "${state.lowercase()}-$s", e1, q1, state, "PAY-099")
        }
        version(t, "e2-$s", e2, q1, "DEAD_LETTERED", "PAY-020")
        version(t, "q2-$s", e1, q2, "DEAD_LETTERED", "PAY-021")
        provisional(u, "up-$s", e1, q1, "MDM-001")
        version(u, "ud-$s", e1, q1, "DEAD_LETTERED", "PAY-014")
        return t to u
    }

    private fun read(
        tenant: UUID,
        entity: String,
    ) = asTenant(tenant) { source.candidates(TenantId(tenant), entity, q1) }

    @Test
    fun `INV-AC-20847 the residual source returns the provisional entries and the dead letters of the entity`() {
        val (t, _) = scenario()
        val rows = checkNotNull(read(t, e1)).sortedBy { it.docUuid }
        rows.map { listOf(it.docUuid, it.version, it.kind, it.reasonCode) } shouldBe
            listOf(
                listOf("p1-$s", 1, ResidualKind.PROVISIONAL, "MDM-001"),
                listOf("p2-$s", 1, ResidualKind.PROVISIONAL, null),
                listOf("va-$s", 2, ResidualKind.DEAD_LETTERED, "PAY-014"),
                listOf("vb-$s", 1, ResidualKind.DEAD_LETTERED, null),
            )
    }

    @Test
    fun `INV-AC-20847 the code of a dead letter is that of the last attempt, never the first`() {
        val (t, _) = scenario()
        val va = checkNotNull(read(t, e1)).single { it.docUuid == "va-$s" }
        va.reasonCode shouldBe "PAY-014"
        va.recordedAt shouldBe t0.plusSeconds(10)
    }

    @Test
    fun `INV-AC-20847 no row of another tenant is returned`() {
        val (t, u) = scenario()
        checkNotNull(read(t, e1)).none { it.docUuid.startsWith("u") } shouldBe true
        checkNotNull(read(u, e1)).map { it.docUuid }.sorted() shouldBe listOf("ud-$s", "up-$s")
    }

    @Test
    fun `INV-AC-20847 two entities of one tenant never mix`() {
        val (t, _) = scenario()
        checkNotNull(read(t, e2)).map { it.docUuid to it.reasonCode } shouldBe listOf("e2-$s" to "PAY-020")
        checkNotNull(read(t, e1)).none { it.docUuid.startsWith("e2") } shouldBe true
    }

    @Test
    fun `INV-AC-20847 the statements carry the tenant condition themselves when asked for tenant B under tenant A`() {
        val (t, u) = scenario()
        // The connection is bound to t (row level security shows t rows) while the source is asked for u.
        val rows = asTenant(t) { source.candidates(TenantId(u), e1, q1) }
        checkNotNull(rows) shouldBe emptyList()
    }
}
