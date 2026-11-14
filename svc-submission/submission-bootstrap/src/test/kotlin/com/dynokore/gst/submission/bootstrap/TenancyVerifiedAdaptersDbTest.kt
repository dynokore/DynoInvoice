package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.SignedBindings
import com.dynokore.gst.submission.adapters.SubmissionDb
import com.dynokore.gst.submission.adapters.persistence.JooqDrainResidualSource
import com.dynokore.gst.submission.adapters.persistence.JooqReplayCandidateSource
import com.dynokore.gst.submission.application.drain.ResidualCandidate
import com.dynokore.gst.submission.application.drain.ResidualKind
import com.dynokore.platform.tenancyjooq.TenantPredicateViolation
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The drain residual source and the replay candidate source run through the tenancy verifier (INV-T-20853): the
 * `DSLContext` is the bean of the service context, which the service runtime builds with `TenancyJooq.dslContext`
 * over the declared `submission` tables. The rows are seeded through a plain connection as `submission_runtime`,
 * tenant bound by the signed binding, because the seed is not under test.
 */
class TenancyVerifiedAdaptersDbTest {
    private val actor = "svc:submission-svc"
    private val t0 = Instant.parse("2026-11-01T01:00:00Z")
    private val s = UUID.randomUUID().toString().take(8)
    private val e1 = "E1-$s"
    private val q1 = "2026-Q1"

    private fun seed(block: (org.jooq.DSLContext, (UUID) -> Unit) -> Unit) {
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            val plain = DSL.using(connection, SQLDialect.POSTGRES)
            block(plain) { tenant ->
                SignedBindings.bind(connection, tenant)
            }
            connection.commit()
        }
    }

    private fun provisional(
        dsl: DSLContext,
        tenant: UUID,
        doc: String,
        reason: String?,
    ) = dsl.execute(
        "insert into submission.provisional_register_entry (tenant_id, doc_uuid, entity_ref, period_ref, " +
            "direction, document_date, transaction_date, reason_code, docsequence, held_at) " +
            "values (?, ?, ?, ?, 'OUTBOUND', ?::date, ?::date, ?, 1, ?::timestamptz)",
        tenant,
        doc,
        e1,
        q1,
        LocalDate.of(2026, 1, 5),
        LocalDate.of(2026, 1, 5),
        reason,
        t0.atOffset(ZoneOffset.UTC),
    )

    private fun version(
        dsl: DSLContext,
        tenant: UUID,
        doc: String,
        state: String,
        vararg codes: String?,
    ) {
        dsl.execute(
            "insert into submission.obligation_register_entry (tenant_id, doc_uuid, entity_ref, period_ref, " +
                "due_date, in_scope, kind) values (?, ?, ?, ?, ?::date, true, 'INVOICE')",
            tenant,
            doc,
            e1,
            q1,
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
            e1,
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

    private fun seedDrain(
        t: UUID,
        u: UUID,
    ) = seed { dsl, bind ->
        bind(t)
        provisional(dsl, t, "p1-$s", "MDM-001")
        provisional(dsl, t, "p2-$s", null)
        version(dsl, t, "va-$s", "DEAD_LETTERED", "PAY-001", "PAY-014")
        version(dsl, t, "vb-$s", "DEAD_LETTERED", null)
        version(dsl, t, "sent-$s", "SENT", "PAY-099")
    }.also {
        seed { dsl, bind ->
            bind(u)
            provisional(dsl, u, "up-$s", "MDM-001")
            version(dsl, u, "ud-$s", "DEAD_LETTERED", "PAY-014")
        }
    }

    private inner class Kit(
        context: ApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)

        fun <T> asTenant(
            tenant: UUID,
            block: () -> T,
        ): T = tenants.bind(TenantId(tenant), actor) { runner.inTransaction(block) }
    }

    private fun run(
        dir: Path,
        block: Kit.() -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> Kit(context).block() }
    }

    private fun Kit.carriesTenancyListeners() {
        // The test gate: a context that is not tenancy verified (DSL.using on a connection) would run a read of a
        // table outside the declared schema; the verifier refuses it before any SQL reaches the server.
        shouldThrow<TenantPredicateViolation> {
            asTenant(UUID.randomUUID()) {
                dsl.selectFrom(DSL.table(DSL.name("submission", "not_a_declared_table_$s"))).fetch()
            }
        }
    }

    private fun expected() =
        listOf(
            ResidualCandidate("p1-$s", 1, ResidualKind.PROVISIONAL, "MDM-001", t0),
            ResidualCandidate("p2-$s", 1, ResidualKind.PROVISIONAL, null, t0),
            ResidualCandidate("va-$s", 2, ResidualKind.DEAD_LETTERED, "PAY-014", t0.plusSeconds(10)),
            ResidualCandidate("vb-$s", 1, ResidualKind.DEAD_LETTERED, null, t0),
        )

    @Test
    fun `the drain residual source returns its candidates through the tenancy verifier`(
        @TempDir dir: Path,
    ) = run(dir) {
        carriesTenancyListeners()
        val t = UUID.randomUUID()
        seedDrain(t, UUID.randomUUID())
        val rows = asTenant(t) { JooqDrainResidualSource(dsl, runner).candidates(TenantId(t), e1, q1) }
        checkNotNull(rows).sortedBy { it.docUuid } shouldBe expected()
    }

    @Test
    fun `the drain residual source returns nothing of another tenant through the tenancy verifier`(
        @TempDir dir: Path,
    ) = run(dir) {
        carriesTenancyListeners()
        val t = UUID.randomUUID()
        val other = UUID.randomUUID()
        seedDrain(t, UUID.randomUUID())
        val rows = asTenant(t) { JooqDrainResidualSource(dsl, runner).candidates(TenantId(other), e1, q1) }
        checkNotNull(rows).shouldBeEmpty()
        val own = asTenant(t) { JooqDrainResidualSource(dsl, runner).candidates(TenantId(t), e1, q1) }
        checkNotNull(own) shouldNotBe emptyList<ResidualCandidate>()
    }

    @Test
    fun `the replay candidate source runs through the tenancy verifier`(
        @TempDir dir: Path,
    ) = run(dir) {
        carriesTenancyListeners()
        val t = UUID.randomUUID()
        seed { dsl, bind ->
            bind(t)
            version(dsl, t, "a-$s", "DEAD_LETTERED", "SUB-002", "SUB-001")
            version(dsl, t, "c-$s", "DEAD_LETTERED", null)
            version(dsl, t, "sent-$s", "SENT", "SUB-001")
        }
        val rows = asTenant(t) { JooqReplayCandidateSource(dsl, runner).candidates(TenantId(t), e1) }
        rows.associate { it.docUuid to it.failureClass } shouldBe mapOf("a-$s" to "SUB-001", "c-$s" to null)
        rows.first { it.docUuid == "a-$s" }.queuedAt shouldBe t0
        rows.first { it.docUuid == "a-$s" }.periodRef shouldBe q1
    }
}
