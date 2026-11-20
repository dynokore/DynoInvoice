package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.NetDerivationRefusedReader
import com.dynokore.gst.submission.adapters.events.OutboxNetDerivationRequestPublisher
import com.dynokore.gst.submission.adapters.persistence.JooqCreditNoteCorrectionPorts
import com.dynokore.gst.submission.adapters.persistence.JooqSubmissionStore
import com.dynokore.gst.submission.application.correction.CreditNoteCorrectionHandler
import com.dynokore.gst.submission.application.queue.NewReadySubmission
import com.dynokore.gst.submission.domain.correction.CorrectionReason
import com.dynokore.gst.submission.domain.listing.SubmissionKind
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
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * The adapters of the net derivation over PostgreSQL as `submission_runtime` (INV-AC-25344): the request publisher,
 * the refusal reader, the register ports and the reason constraint. The outbox is a fake that records the event and
 * writes a probe row in the same transaction, so a rollback shows whether the request shared the decision's
 * transaction.
 */
class NetDerivationAdaptersDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val tenant = UUID.randomUUID()
    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(tenant)

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val staged = mutableListOf<Pair<OutboundEvent, EventContext>>()
    private val outbox =
        object : OutboxWriter {
            override fun append(
                events: List<OutboundEvent>,
                context: EventContext,
            ) = events
                .map {
                    staged += it to context
                    dsl.execute("insert into submission.net_outbox_probe values (?)", context.aggregateKey)
                    null
                }.filterNotNull()
        }
    private val ports = JooqCreditNoteCorrectionPorts(dsl, tenants)
    private val handler =
        CreditNoteCorrectionHandler(
            ports,
            ports,
            ports,
            OutboxNetDerivationRequestPublisher(outbox, tenants, dsl),
            ports,
            ports,
            { true },
        )
    private val original = UUID.randomUUID().toString()
    private val creditNote = UUID.randomUUID().toString()

    @AfterEach
    fun close() = connection.close()

    private fun bind() = SignedBindings.bind(connection, tenant)

    private fun seed() {
        bind()
        dsl.execute(
            "insert into submission.obligation_register_entry (tenant_id, doc_uuid, entity_ref, period_ref, " +
                "due_date, " +
                "in_scope, kind, source_version, scope_verdict) values (?, ?, 'E1', 'p1', date '2026-11-30', true, " +
                "'INVOICE', 2, 'SUBMITTABLE')",
            tenant,
            original,
        )
        dsl.execute(
            "insert into submission.obligation_register_entry (tenant_id, doc_uuid, entity_ref, period_ref, " +
                "due_date, " +
                "in_scope, kind, source_version, scope_verdict, preceding_doc_uuid) values (?, ?, 'E1', 'p1', " +
                "date '2026-11-30', true, 'CREDIT_NOTE', 3, 'SUBMITTABLE', ?)",
            tenant,
            creditNote,
            original,
        )
        JooqSubmissionStore(dsl).insertReady(
            TenantId(tenant),
            NewReadySubmission(original, 2, "E1", null, byteArrayOf(1), "sha-$original", SubmissionKind.TYPE_1A),
        )
        connection.commit()
    }

    private fun probeRows(): Int =
        SubmissionDb.superuser().use { c ->
            c.createStatement().use { s ->
                s.executeQuery("select count(*) from submission.net_outbox_probe where doc_uuid = '$original'").use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun schemaErrors(
        service: String,
        schema: String,
        data: String,
    ): List<String> {
        val file = File("../submission-contract/build/contracts-repo/.bundle/$service/schemas/$schema")
        check(file.isFile) { "the pinned payload schema is missing: $file" }
        val registry =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }
        return registry
            .getSchema(file.readText())
            .validate(data, InputFormat.JSON)
            .map { it.message }
    }

    private fun refused(data: String) =
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
            dataJson = data,
        )

    @Test
    fun `INV-AC-25344 the request row validates against the schema and is written in the decision transaction`() {
        seed()
        bind()
        handler.decide(creditNote)
        staged.size shouldBe 1
        val (event, context) = staged.single()
        event.type shouldBe "com.dynokore.gst.submission.NetDerivationRequested.v1"
        context.aggregateKey shouldBe original
        schemaErrors("submission", "net-derivation-requested-payload.schema.json", event.dataJson).shouldBeEmpty()
        val tree = ObjectMapper().readTree(event.dataJson)
        tree.get("expectedBaseVersion").asInt() shouldBe 2
        tree
            .get("creditNotes")
            .single()
            .get("docUuid")
            .asString() shouldBe creditNote
        tree
            .get("creditNotes")
            .single()
            .get("version")
            .asInt() shouldBe 3
        connection.rollback()
        probeRows() shouldBe 0
        bind()
        handler.decide(creditNote)
        connection.commit()
        probeRows() shouldBe 1
    }

    @Test
    fun `INV-AC-25344 a refusal reaches the handler with its fields and marks the credit note`() {
        seed()
        val data =
            """{"originalDocUuid":"$original","requestEventId":"evt-1","reason":"NET_NOT_POSITIVE",""" +
                """"creditNotes":[{"docUuid":"$creditNote","version":3}]}"""
        schemaErrors("canonical", "net-derivation-refused-payload.schema.json", data).shouldBeEmpty()
        bind()
        ports.isRefused(creditNote) shouldBe false
        NetDerivationRefusedReader(handler, ports).handle(refused(data)) shouldBe HandleOutcome.APPLIED
        ports.isRefused(creditNote) shouldBe true
        staged.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-25344 a refusal that does not validate is a ContractViolation and changes nothing`() {
        seed()
        bind()
        val reader = NetDerivationRefusedReader(handler, ports)
        val bad =
            listOf(
                "not json {",
                "[]",
                """{"requestEventId":"e","reason":"BASE_CHANGED"}""",
                """{"originalDocUuid":"$original","reason":"BASE_CHANGED"}""",
                """{"originalDocUuid":"$original","requestEventId":"e"}""",
                """{"originalDocUuid":"x","requestEventId":"e","reason":"BASE_CHANGED"}""",
                """{"originalDocUuid":"$original","requestEventId":"e","reason":"BASE_CHANGED","creditNotes":[]}""",
                """{"originalDocUuid":"$original","requestEventId":"e","reason":"BASE_CHANGED",""" +
                    """"creditNotes":[{"docUuid":"$creditNote","version":0}]}""",
            )
        bad.forEach { shouldThrow<ContractViolation> { reader.handle(refused(it)) } }
        ports.isRefused(creditNote) shouldBe false
    }

    @Test
    fun `INV-AC-25344 the reason NETTED_BY_SYSTEM is stored and an unknown reason is refused by the constraint`() {
        seed()
        bind()
        ports.notSubmittable(creditNote, CorrectionReason.NETTED_BY_SYSTEM)
        ports.nettedBySystem(original) shouldBe listOf(creditNote)
        dsl
            .fetchOne(
                "select not_submittable_reason from submission.obligation_register_entry where doc_uuid = ?",
                creditNote,
            )!!
            .get(0, String::class.java) shouldBe "NETTED_BY_SYSTEM"
        ports.lift(creditNote)
        ports.nettedBySystem(original) shouldBe emptyList()
        shouldThrow<DataAccessException> {
            dsl.execute(
                "update submission.obligation_register_entry set scope_verdict = 'NOT_SUBMITTABLE', " +
                    "not_submittable_reason = 'NETTED_BY_SYSTEMS' where doc_uuid = ?",
                creditNote,
            )
        }
        connection.rollback()
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun installProbe() {
            SubmissionDb.superuser().use { c ->
                c.autoCommit = true
                c.createStatement().use {
                    it.execute("create table if not exists submission.net_outbox_probe (doc_uuid text)")
                    it.execute("grant insert on submission.net_outbox_probe to submission_runtime")
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun removeProbe() {
            SubmissionDb.superuser().use { c ->
                c.autoCommit = true
                c.createStatement().use { it.execute("drop table if exists submission.net_outbox_probe") }
            }
        }
    }
}
