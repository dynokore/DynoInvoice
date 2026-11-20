package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqCreditNoteVersions
import com.dynokore.gst.submission.adapters.persistence.JooqNetFindings
import com.dynokore.gst.submission.application.correction.NetFinding
import com.dynokore.gst.submission.application.correction.NetFindingKind
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The net findings and the registered credit note version over PostgreSQL as `submission_runtime` (INV-AC-25528).
 * Every test works in one transaction that is rolled back when the connection closes.
 */
class NetFindingsDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private var bound = t1
    private var actor: String? = SignedBindings.ACTOR

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(bound)

            override fun currentActor(): String? = actor

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val findings = JooqNetFindings(dsl, tenants)
    private val versions = JooqCreditNoteVersions(dsl, tenants)
    private val note = UUID.randomUUID().toString()
    private val original = UUID.randomUUID().toString()

    @AfterEach
    fun close() = connection.close()

    private fun bind(id: UUID) {
        bound = id
        SignedBindings.bind(connection, id)
    }

    private fun amended(version: Int? = 2) =
        NetFinding(NetFindingKind.AMENDED_AFTER_TRANSMISSION, note, version, original)

    private fun count(): Int =
        dsl.fetchOne("select count(*) from submission.net_correction_finding")!!.get(0, Int::class.java)

    @Test
    fun `INV-AC-25528 the first record stores the row, the repeat returns false and the count stays one`() {
        bind(t1)
        findings.record(amended()) shouldBe true
        findings.record(amended()) shouldBe false
        count() shouldBe 1
        val row =
            dsl.fetchOne(
                "select tenant_id, kind, credit_note_doc_uuid, credit_note_version, original_doc_uuid, " +
                    "recorded_by, recorded_at is not null from submission.net_correction_finding",
            )!!
        row.intoArray().toList() shouldBe
            listOf(t1, "AMENDED_AFTER_TRANSMISSION", note, 2, original, SignedBindings.ACTOR, true)
    }

    @Test
    fun `INV-AC-25528 a finding of another version of the same note is new`() {
        bind(t1)
        findings.record(amended(2)) shouldBe true
        findings.record(amended(3)) shouldBe true
        count() shouldBe 2
    }

    @Test
    fun `INV-AC-25528 NET_NOT_POSITIVE stores a row without a version and a repeat stores none`() {
        bind(t1)
        val f = NetFinding(NetFindingKind.NET_NOT_POSITIVE, note, null, null)
        findings.record(f) shouldBe true
        findings.record(f) shouldBe false
        count() shouldBe 1
        dsl
            .fetchOne("select credit_note_version, original_doc_uuid from submission.net_correction_finding")!!
            .intoArray()
            .toList() shouldBe listOf(null, null)
    }

    @Test
    fun `INV-AC-25528 the constraint admits exactly the two kinds, NETTED and a look-alike are refused`() {
        bind(t1)
        listOf("NETTED", "AMENDED_AFTER_TRANSMISSIONS", "amended_after_transmission").forEach { kind ->
            shouldThrow<DataAccessException> { insertKind(kind) }
            connection.rollback()
            bind(t1)
        }
        count() shouldBe 0
        insertKind("AMENDED_AFTER_TRANSMISSION")
        insertKind("NET_NOT_POSITIVE")
        count() shouldBe 2
    }

    @Test
    fun `INV-AC-25528 the constraint error names the kind check`() {
        bind(t1)
        val e = shouldThrow<DataAccessException> { insertKind("NETTED") }
        (e.message!!.contains("net_correction_finding_kind_check")) shouldBe true
    }

    @Test
    fun `INV-AC-25528 tenant T2 stores its own row and T1 cannot read it`() {
        bind(t1)
        findings.record(amended()) shouldBe true
        bind(t2)
        count() shouldBe 0
        findings.record(amended()) shouldBe true
        count() shouldBe 1
        dsl.fetchOne("select tenant_id from submission.net_correction_finding")!!.get(0, UUID::class.java) shouldBe t2
        bind(t1)
        count() shouldBe 1
        dsl.fetchOne("select tenant_id from submission.net_correction_finding")!!.get(0, UUID::class.java) shouldBe t1
    }

    @Test
    fun `INV-AC-25528 no bound service identity stores nothing`() {
        bind(t1)
        actor = null
        shouldThrow<IllegalStateException> { findings.record(amended()) }
        count() shouldBe 0
    }

    @Test
    fun `INV-AC-25528 registeredVersionOf returns 3 for the entry and null for the missing one`() {
        bind(t1)
        entry(t1, note, 3)
        versions.registeredVersionOf(note) shouldBe 3
        versions.registeredVersionOf(UUID.randomUUID().toString()) shouldBe null
        bind(t2)
        versions.registeredVersionOf(note) shouldBe null
    }

    private fun insertKind(kind: String) {
        dsl.execute(
            "insert into submission.net_correction_finding (tenant_id, kind, credit_note_doc_uuid, recorded_by) " +
                "values (?, ?, ?, 'svc')",
            bound,
            kind,
            note,
        )
    }

    private fun entry(
        tenant: UUID,
        doc: String,
        version: Int,
    ) {
        dsl.execute(
            "insert into submission.obligation_register_entry (tenant_id, doc_uuid, entity_ref, period_ref, " +
                "due_date, in_scope, kind, source_version, scope_verdict, document_number, document_type_code, " +
                "direction, counterparty_name, counterparty_uen, taxable_amount, gst_amount, category_codes) " +
                "values (?, ?, 'E1', 'p1', date '2026-11-30', true, 'CREDIT_NOTE', ?, 'SUBMITTABLE', 'N-1', '381', " +
                "'PURCHASE', 'c', 'u', 1.00, 0.09, array['TX'])",
            tenant,
            doc,
            version,
        )
    }
}
