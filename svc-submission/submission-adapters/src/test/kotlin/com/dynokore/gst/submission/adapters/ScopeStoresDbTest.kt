package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqScopeBlocks
import com.dynokore.gst.submission.adapters.persistence.JooqScopeFacts
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.arrival.CodeRaising
import com.dynokore.gst.submission.application.arrival.CodeSignals
import com.dynokore.gst.submission.application.arrival.RaisedCode
import com.dynokore.gst.submission.application.arrival.RaisedCodes
import com.dynokore.gst.submission.application.arrival.ScopeCheck
import com.dynokore.gst.submission.application.arrival.ScopeEntry
import com.dynokore.gst.submission.application.arrival.ScopeOutcome
import com.dynokore.gst.submission.application.queue.CatalogueEntry
import com.dynokore.gst.submission.application.queue.ErrorCatalogue
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.scope.NotSubmittableReason
import com.dynokore.gst.submission.domain.scope.ScopeVerdict
import com.dynokore.gst.submission.domain.scope.Verdict
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The scope stores and the scope check against the test database as `submission_runtime` (INV-AC-24504). */
class ScopeStoresDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
    private val sub = Submission.SUBMISSION_
    private val facts = JooqScopeFacts(dsl)
    private val blocks = JooqScopeBlocks(dsl)
    private val s = UUID.randomUUID().toString().take(8)
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val raised = mutableListOf<RaisedCode>()
    private var blockCalls = 0

    private val countingBlocks =
        object : com.dynokore.gst.submission.application.arrival.ScopeBlocks {
            override fun blockReady(
                tenant: TenantId,
                docUuid: String,
                version: Int,
            ): Int {
                blockCalls++
                return blocks.blockReady(tenant, docUuid, version)
            }
        }
    private val check =
        ScopeCheck(
            facts,
            countingBlocks,
            CodeRaising(
                ErrorCatalogue { if (it == "SUB-013") CatalogueEntry(it, "Tax operations", "MANUAL") else null },
                object : RaisedCodes {
                    override fun append(
                        tenant: TenantId,
                        raised: RaisedCode,
                        raisedAt: Instant,
                    ): Boolean = this@ScopeStoresDbTest.raised.add(raised)
                },
                object : CodeSignals {
                    override fun stage(raised: RaisedCode) = Unit
                },
                Clock.fixed(Instant.parse("2026-11-18T03:00:00Z"), ZoneOffset.UTC),
            ),
        )

    @AfterEach
    fun close() {
        connection.close()
        SubmissionDb.superuser().use { c ->
            c.createStatement().use {
                it.execute("delete from submission.submission where doc_uuid like '%-$s'")
                it.execute("delete from submission.obligation_register_entry where doc_uuid like '%-$s'")
            }
        }
    }

    private fun doc(name: String) = "$name-$s"

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        SignedBindings.bind(connection, tenant)
        return try {
            block()
        } finally {
            connection.rollback()
        }
    }

    /** Seeds one register entry (and a READY version 1 row when [version] is set) through a committed transaction. */
    @Suppress("LongParameterList")
    private fun seed(
        tenant: UUID,
        name: String,
        kind: String,
        verdict: Verdict?,
        preceding: String? = null,
        state: String? = "READY",
    ) {
        SignedBindings.bind(connection, tenant)
        dsl
            .insertInto(entry)
            .set(entry.TENANT_ID, tenant)
            .set(entry.DOC_UUID, doc(name))
            .set(entry.ENTITY_REF, "E-001")
            .set(entry.PERIOD_REF, "2026-Q1")
            .set(entry.DUE_DATE, LocalDate.of(2026, 4, 30))
            .set(entry.IN_SCOPE, true)
            .set(entry.KIND, kind)
            .set(entry.SCOPE_VERDICT, verdict?.scopeVerdict?.name)
            .set(entry.NOT_SUBMITTABLE_REASON, verdict?.notSubmittableReason?.name)
            .set(entry.PRECEDING_DOC_UUID, preceding?.let { doc(it) })
            .execute()
        if (state != null) {
            dsl
                .insertInto(sub)
                .set(sub.TENANT_ID, tenant)
                .set(sub.DOC_UUID, doc(name))
                .set(sub.VERSION, 1)
                .set(sub.STATE, state)
                .set(sub.ENTITY_REF, "E-001")
                .execute()
        }
        connection.commit()
    }

    private fun stateOf(
        tenant: UUID,
        name: String,
    ): String? =
        asTenant(tenant) {
            dsl
                .select(sub.STATE)
                .from(sub)
                .where(sub.DOC_UUID.eq(doc(name)))
                .and(sub.VERSION.eq(1))
                .fetchOne(sub.STATE)
        }

    private fun pair(
        name: String,
        note: Verdict?,
        pre: Verdict?,
        kind: String = "CREDIT_NOTE",
    ) {
        seed(t1, "P$name", "INVOICE", pre, state = null)
        seed(t1, name, kind, note, "P$name")
    }

    private val sub1 = Verdict(ScopeVerdict.SUBMITTABLE)
    private val notSub = Verdict(ScopeVerdict.NOT_SUBMITTABLE)

    private fun run(name: String): ScopeOutcome = asTenant(t1) { check.check(TenantId(t1), doc(name), 1) }

    private fun assertBlocked(name: String) {
        run(name) shouldBe ScopeOutcome.Blocked
        blockCalls shouldBe 1
        raised.size shouldBe 1
        raised.single().code shouldBe "SUB-013"
        raised.single().entityRef shouldBe "E-001"
        raised.single().periodRef shouldBe "2026-Q1"
    }

    private fun assertUntouched(name: String) {
        run(name) shouldBe ScopeOutcome.Proceed
        blockCalls shouldBe 0
        raised shouldBe emptyList()
        stateOf(t1, name) shouldBe "READY"
    }

    @Test
    fun `INV-AC-24504 C1 block persists once committed`() {
        pair("C1", sub1, notSub)
        SignedBindings.bind(connection, t1)
        check.check(TenantId(t1), doc("C1"), 1) shouldBe ScopeOutcome.Blocked
        connection.commit()
        blockCalls shouldBe 1
        stateOf(t1, "C1") shouldBe "BLOCKED"
    }

    @Test
    fun `INV-AC-24504 C5 the reverse pair is blocked and stored BLOCKED`() {
        pair("C5", notSub, sub1)
        SignedBindings.bind(connection, t1)
        check.check(TenantId(t1), doc("C5"), 1) shouldBe ScopeOutcome.Blocked
        connection.commit()
        blockCalls shouldBe 1
        raised.single().code shouldBe "SUB-013"
        stateOf(t1, "C5") shouldBe "BLOCKED"
    }

    @Test
    fun `INV-AC-24504 C2 C3 C4 C6 C7 C8 and I1 proceed untouched`() {
        pair("C2", sub1, sub1)
        pair("C3", sub1, Verdict(ScopeVerdict.NOT_SUBMITTABLE, NotSubmittableReason.BUYER_REJECTED))
        pair("C4", sub1, Verdict(ScopeVerdict.PRE_OBLIGATION))
        seed(t1, "C6", "CREDIT_NOTE", sub1, "GONE")
        pair("C7", null, notSub)
        seed(t1, "C8", "CREDIT_NOTE", sub1, null)
        pair("I1", sub1, notSub, "INVOICE")
        listOf("C2", "C3", "C4", "C6", "C7", "C8", "I1").forEach { assertUntouched(it) }
    }

    @Test
    fun `INV-AC-24504 a QUEUED version makes blockReady return 0 and the check throws`() {
        seed(t1, "PC1", "INVOICE", notSub, state = null)
        seed(t1, "C1", "CREDIT_NOTE", sub1, "PC1", state = "QUEUED")
        shouldThrow<IllegalStateException> { run("C1") }
        raised shouldBe emptyList()
        stateOf(t1, "C1") shouldBe "QUEUED"
    }

    @Test
    fun `INV-AC-24504 a second check of the blocked version throws and raises once`() {
        pair("C1", sub1, notSub)
        SignedBindings.bind(connection, t1)
        check.check(TenantId(t1), doc("C1"), 1) shouldBe ScopeOutcome.Blocked
        connection.commit()
        SignedBindings.bind(connection, t1)
        shouldThrow<IllegalStateException> { check.check(TenantId(t1), doc("C1"), 1) }
        connection.rollback()
        raised.size shouldBe 1
    }

    @Test
    fun `INV-AC-24504 the facts of another tenant are null and the preceding row is read with the tenant`() {
        pair("C1", sub1, notSub)
        seed(t2, "X1", "CREDIT_NOTE", sub1, "PC1X", state = null)
        // Bound to T1, asked as T2 or T1: the query's own tenant condition holds even where RLS shows the row.
        asTenant(t2) { facts.of(TenantId(t2), doc("C1")) } shouldBe null
        asTenant(t1) { facts.of(TenantId(t2), doc("C1")) } shouldBe null
        asTenant(t1) { facts.of(TenantId(t1), doc("C1")) } shouldBe
            ScopeEntry("E-001", "2026-Q1", RowKind.CREDIT_NOTE, sub1, doc("PC1"))
        asTenant(t1) { facts.of(TenantId(t1), doc("PC1")) }!!.verdict shouldBe notSub
        asTenant(t2) { facts.of(TenantId(t2), doc("PC1")) } shouldBe null
        // A preceding entry that exists only under the other tenant is not compared: C1 stays untouched.
        seed(t1, "C9", "CREDIT_NOTE", sub1, "PC9X")
        seed(t2, "PC9X", "INVOICE", notSub, state = null)
        assertUntouched("C9")
    }

    @Test
    fun `INV-AC-24504 a verdict text that is not a ScopeVerdict name throws in the facts read`() {
        fun stubbed(
            kind: String,
            verdict: String?,
            reason: String?,
        ): JooqScopeFacts {
            val create = DSL.using(SQLDialect.POSTGRES)
            val names =
                listOf(
                    "entity_ref",
                    "period_ref",
                    "kind",
                    "scope_verdict",
                    "not_submittable_reason",
                    "preceding_doc_uuid",
                )
            val fields = names.map { DSL.field(it, String::class.java) }
            val result = create.newResult(fields)
            result.add(
                create.newRecord(fields).also { r ->
                    listOf("E", "P", kind, verdict, reason, null).forEachIndexed { i, v -> r.set(fields[i], v) }
                },
            )
            return JooqScopeFacts(
                DSL.using(
                    MockConnection { _ ->
                        arrayOf(MockResult(1, result))
                    },
                    SQLDialect.POSTGRES,
                ),
            )
        }
        val tenant = TenantId(t1)
        stubbed("CREDIT_NOTE", "SUBMITTABLE", null).of(tenant, "d")!!.verdict shouldBe sub1
        shouldThrow<IllegalStateException> { stubbed("CREDIT_NOTE", "MAYBE", null).of(tenant, "d") }
        shouldThrow<IllegalStateException> { stubbed("CREDIT_NOTE", "SUBMITTABLE", "OTHER").of(tenant, "d") }
        shouldThrow<IllegalStateException> { stubbed("CREDIT_NOTE", "NOT_SUBMITTABLE", "OTHER").of(tenant, "d") }
        shouldThrow<IllegalStateException> { stubbed("OTHER", "SUBMITTABLE", null).of(tenant, "d") }
    }
}
