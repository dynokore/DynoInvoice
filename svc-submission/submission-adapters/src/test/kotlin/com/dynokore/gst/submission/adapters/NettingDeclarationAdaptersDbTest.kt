package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.NetDerivationRefusedReader
import com.dynokore.gst.submission.adapters.events.OutboxNetDerivationRequestPublisher
import com.dynokore.gst.submission.adapters.persistence.JooqCreditNoteCorrectionPorts
import com.dynokore.gst.submission.application.correction.CreditNoteCorrectionHandler
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The netting declarations and the refusal without a credit note list over PostgreSQL as `submission_runtime`
 * (INV-AC-25651). Every test works in one transaction that is rolled back when the connection closes.
 */
class NettingDeclarationAdaptersDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val tenant = UUID.randomUUID()
    private var bound = tenant

    // when set, the tenant the ports name differs from the one the connection is bound to
    private var named: UUID? = null

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(named ?: bound)

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val ports = JooqCreditNoteCorrectionPorts(dsl, tenants)
    private val noOutbox =
        object : OutboxWriter {
            override fun append(
                events: List<OutboundEvent>,
                context: EventContext,
            ): List<Envelope> = emptyList()
        }
    private val handler =
        CreditNoteCorrectionHandler(
            ports,
            ports,
            ports,
            OutboxNetDerivationRequestPublisher(noOutbox, tenants, dsl),
            ports,
            ports,
        )
    private val reader = NetDerivationRefusedReader(handler, ports)
    private val revised = UUID.randomUUID().toString()
    private val note1 = UUID.randomUUID().toString()
    private val note2 = UUID.randomUUID().toString()
    private val note3 = UUID.randomUUID().toString()

    @AfterEach
    fun close() = connection.close()

    private fun bind(id: UUID = tenant) {
        bound = id
        SignedBindings.bind(connection, id)
    }

    private fun rows(
        revisedDoc: String,
        id: UUID = tenant,
    ): List<String> {
        bind(id)
        return dsl
            .fetch(
                "select credit_note_doc_uuid from submission.netting_declaration where revised_doc_uuid = ? " +
                    "order by 1",
                revisedDoc,
            ).map { it.get(0, String::class.java) }
    }

    @Test
    fun `INV-AC-25651 replaceDeclared and declared over three calls, a repeat and an empty set`() {
        bind()
        ports.declared(note1) shouldBe false
        ports.replaceDeclared(revised, setOf(note1, note2))
        ports.declared(note1) shouldBe true
        ports.declared(note2) shouldBe true
        ports.declared(note3) shouldBe false
        val stamp = declaredAt(note1)
        ports.replaceDeclared(revised, setOf(note1))
        ports.declared(note1) shouldBe true
        ports.declared(note2) shouldBe false
        declaredAt(note1) shouldBe stamp
        ports.replaceDeclared(revised, setOf(note1))
        rows(revised) shouldBe listOf(note1)
        ports.replaceDeclared(revised, emptySet())
        rows(revised) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25651 a replacement leaves the rows of another revised document and of another tenant`() {
        val other = UUID.randomUUID().toString()
        val otherTenant = UUID.randomUUID()
        bind()
        ports.replaceDeclared(revised, setOf(note1))
        ports.replaceDeclared(other, setOf(note1, note2))
        ports.replaceDeclared(revised, setOf(note3))
        rows(revised) shouldBe listOf(note3)
        rows(other) shouldBe listOf(note1, note2).sorted()
        ports.declared(note1) shouldBe true // still declared by the other revised document
        bind(otherTenant)
        ports.declared(note1) shouldBe false
        ports.replaceDeclared(revised, setOf(note1))
        rows(revised, otherTenant) shouldBe listOf(note1)
        bind()
        ports.replaceDeclared(revised, emptySet())
        rows(revised, otherTenant) shouldBe listOf(note1)
        rows(revised) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25651 declared names the tenant of the context besides the row level security`() {
        bind()
        ports.replaceDeclared(revised, setOf(note1))
        ports.declared(note1) shouldBe true
        named = UUID.randomUUID()
        ports.declared(note1) shouldBe false
        ports.replaceDeclared(revised, emptySet())
        named = null
        ports.declared(note1) shouldBe true
    }

    @Test
    fun `INV-AC-25651 a duplicate pair is refused by the primary key and the table is tenant data under forced RLS`() {
        bind()
        dsl.execute(INSERT, tenant, revised, note1)
        shouldThrow<DataAccessException> { dsl.execute(INSERT, tenant, revised, note1) }
        connection.rollback()
        SubmissionDb.superuser().use { c ->
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "select relrowsecurity, relforcerowsecurity from pg_class " +
                            "where oid = 'submission.netting_declaration'::regclass",
                    ).use {
                        it.next()
                        it.getBoolean(1) shouldBe true
                        it.getBoolean(2) shouldBe true
                    }
            }
        }
    }

    @Test
    fun `INV-AC-25651 a refusal without creditNotes is handed exactly the awaiting note over six register states`() {
        val original = UUID.randomUUID().toString()
        bind()
        entry(original, null)
        val awaiting = entry(preceding = original)
        entry(preceding = original, verdict = "NOT_SUBMITTABLE", reason = "BUYER_REJECTED")
        entry(preceding = original, verdict = "NOT_SUBMITTABLE", reason = "NETTED_BY_SYSTEM")
        entry(preceding = original, refused = true)
        entry(preceding = original, direction = "SALE")
        entry(preceding = UUID.randomUUID().toString())
        ports.awaiting(original) shouldBe listOf(awaiting)
        ports.isRefused(awaiting) shouldBe false
        reader.handle(refused(original)) shouldBe HandleOutcome.APPLIED
        ports.isRefused(awaiting) shouldBe true
        refusedCount(original) shouldBe 2 // the awaiting note and the one refused before
    }

    @Test
    fun `INV-AC-25651 a refusal without creditNotes and no awaiting note is a ContractViolation and stores nothing`() {
        val original = UUID.randomUUID().toString()
        bind()
        entry(original, null)
        entry(preceding = original, refused = true)
        entry(preceding = original, direction = "SALE")
        shouldThrow<ContractViolation> { reader.handle(refused(original)) }
        refusedCount(original) shouldBe 1
    }

    private fun declaredAt(note: String): Instant =
        dsl
            .fetchOne(
                "select declared_at from submission.netting_declaration where credit_note_doc_uuid = ? limit 1",
                note,
            )!!
            .get(0, java.sql.Timestamp::class.java)
            .toInstant()

    private fun refusedCount(original: String): Int =
        dsl
            .fetchOne(
                "select count(*) from submission.obligation_register_entry where preceding_doc_uuid = ? " +
                    "and net_refused",
                original,
            )!!
            .get(0, Int::class.java)

    @Suppress("LongParameterList")
    private fun entry(
        doc: String = UUID.randomUUID().toString(),
        preceding: String?,
        verdict: String = "SUBMITTABLE",
        reason: String? = null,
        refused: Boolean = false,
        direction: String = "PURCHASE",
    ): String {
        dsl.execute(
            "insert into submission.obligation_register_entry (tenant_id, doc_uuid, entity_ref, period_ref, " +
                "due_date, in_scope, kind, source_version, scope_verdict, not_submittable_reason, " +
                "preceding_doc_uuid, net_refused, document_number, document_type_code, direction, " +
                "counterparty_name, counterparty_uen, taxable_amount, gst_amount, category_codes) " +
                "values (?, ?, 'E1', 'p1', date '2026-11-30', true, 'CREDIT_NOTE', 1, ?, ?, ?, ?, 'N-1', '381', ?, " +
                "'c', 'u', 1.00, 0.09, array['TX'])",
            tenant,
            doc,
            verdict,
            reason,
            preceding,
            refused,
            direction,
        )
        return doc
    }

    private fun refused(original: String) =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/canonical-svc",
            type = "com.dynokore.gst.canonical.NetDerivationRefused.v1",
            subject = original,
            time = Instant.parse("2026-11-20T09:30:00Z"),
            tenant = TenantId(tenant),
            entity = EntityRef("E1"),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "canonical-svc-0",
            correlation = CorrelationId("c"),
            causation = CausationId("k"),
            dataJson = """{"originalDocUuid":"$original","requestEventId":"evt-2","reason":"NET_NOT_POSITIVE"}""",
        )

    private companion object {
        const val INSERT =
            "insert into submission.netting_declaration (tenant_id, revised_doc_uuid, credit_note_doc_uuid) " +
                "values (?, ?, ?)"
    }
}
