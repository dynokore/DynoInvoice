package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.gst.canonical.adapters.events.OutboxHeldEventPublisher
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentHoldStore
import com.dynokore.gst.canonical.application.document.HeldEvent
import com.dynokore.gst.canonical.application.document.HeldEventPublisher
import com.dynokore.gst.canonical.application.document.HoldDocument
import com.dynokore.gst.canonical.domain.derivation.GateDecision
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The hold of a document against a real database (P109): the use case over the jOOQ store and the OutboxWriter in the
 * full TPL-18 context, as the `canonical_runtime` login bound to a tenant. State, reasons and the DocumentHeld outbox
 * row commit or roll back together (INV-REQ-479, INV-REQ-1221).
 */
class HoldDocumentOutboxDbTest {
    private val actor = SignedBindings.ACTOR
    private val reasons = listOf("ING-016", "PER-009")
    private val held = GateDecision.Held(reasons)

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val outbox: OutboxWriter = context.getBean(OutboxWriter::class.java)
        val store = JooqDocumentHoldStore(dsl, scope.tenants)
        val publisher =
            OutboxHeldEventPublisher(outbox, scope.tenants, { CorrelationId("corr-1") }, { CausationId("cause-1") })
        val hold = HoldDocument(store, publisher, runner)
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        CanonicalContext.online(dir).run { block(Boundary(it)) }
    }

    private fun Boundary.holdAs(
        tenant: UUID,
        doc: UUID,
        use: HoldDocument = hold,
    ) = scope.bind(TenantId(tenant), actor) { use.hold(doc, held) }

    /** Reads as the canonical_runtime login bound to the tenant (row security applies). */
    @Suppress("UnusedReceiverParameter")
    private fun Boundary.read(
        tenant: UUID,
        sql: String,
        vararg args: Any,
    ): List<List<String?>> =
        CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            try {
                connection.prepareStatement(sql).use { statement ->
                    args.forEachIndexed { index, arg -> statement.setObject(index + 1, arg) }
                    statement.executeQuery().use { rows ->
                        val width = rows.metaData.columnCount
                        buildList { while (rows.next()) add((1..width).map { rows.getString(it) }) }
                    }
                }
            } finally {
                connection.rollback()
            }
        }

    private fun Boundary.rowsOf(
        tenant: UUID,
        doc: UUID,
    ) = read(
        tenant,
        "select type, topic, subject, docsequence::text, data->>'docsequence', data->>'docUuid', " +
            "data->'holdReasons', data->>'entityRef', data->>'direction', data->>'documentDate', " +
            "data->>'prescribedPeriodRef', data->>'postingTimestamp', data->>'invoiceIssuedOrReceived', " +
            "jsonb_exists(data, 'postingTimestamp')::text, jsonb_exists(data, 'invoiceIssuedOrReceived')::text " +
            "from canonical.outbox where doc_uuid = ?::uuid order by seq",
        doc.toString(),
    )

    private fun Boundary.stateOf(
        tenant: UUID,
        doc: UUID,
    ) = read(
        tenant,
        "select state, hold_reasons::text from canonical.document where doc_uuid = ?::uuid",
        doc.toString(),
    ).single()

    @Suppress("LongParameterList") // a fixture row: one argument per stored column
    private fun insert(
        tenant: UUID,
        entityRef: String? = "E-001",
        documentDate: String? = "2026-03-20",
        period: String? = "2026-Q1",
        posting: String? = null,
        invoice: Boolean? = null,
    ): UUID {
        val doc = UUID.randomUUID()

        fun lit(value: String?) = value?.let { "'$it'" } ?: "null"
        CanonicalDb.superuser().use {
            it.createStatement().use { s ->
                s.execute(
                    "insert into canonical.document (doc_uuid, tenant_id, entity_id, direction, document_type_code, " +
                        "document_number, issuer_party_key, fiscal_year, partition_date, transaction_date, " +
                        "current_version, state, entity_ref, document_date, prescribed_period_ref, " +
                        "posting_timestamp, invoice_issued_or_received) values ('$doc', '$tenant', " +
                        "'${UUID.randomUUID()}', 'SALE', 380, 'N-$doc', 'issuer', 2026, '2026-03-25', '2026-03-25', " +
                        "1, 'INGESTED', ${lit(entityRef)}, ${lit(documentDate)}, ${lit(period)}, ${lit(posting)}, " +
                        "${invoice ?: "null"})",
                )
            }
        }
        return doc
    }

    private fun runtimeRefusal(
        tenant: UUID,
        statement: String,
    ): String? =
        CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            try {
                connection.prepareStatement(statement).use { it.executeUpdate() }
                null
            } catch (refused: java.sql.SQLException) {
                refused.sqlState
            } finally {
                connection.rollback()
            }
        }

    @Test
    fun `INV-AC-20653 the hold commits state, reasons and one DocumentHeld row, and a replay adds none`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val d = insert(t, posting = "2026-03-21T09:30:00+08:00", invoice = true)
        val other = insert(t2)

        b.holdAs(t, d)
        b.holdAs(t, d)

        b.stateOf(t, d) shouldBe listOf("HELD", "{ING-016,PER-009}")
        val rows = b.rowsOf(t, d)
        rows.size shouldBe 1
        val row = rows.single()
        row[0] shouldBe "com.dynokore.gst.canonical.DocumentHeld.v1"
        row[1] shouldBe "gst.canonical.document-held.v1"
        row[2] shouldBe d.toString()
        row[3] shouldBe "1"
        row[4] shouldBe row[3]
        row[5] shouldBe d.toString()
        row[6] shouldBe """["ING-016", "PER-009"]"""
        row[7] shouldBe "E-001"
        row[8] shouldBe "SALE"
        row[9] shouldBe "2026-03-20"
        row[10] shouldBe "2026-Q1"
        OffsetDateTime.parse(row[11]!!).toInstant() shouldBe
            OffsetDateTime.parse("2026-03-21T09:30:00+08:00").toInstant()
        row[12] shouldBe "true"
        b.stateOf(t2, other) shouldBe listOf("INGESTED", null)
        b.rowsOf(t2, other).size shouldBe 0
    }

    @Test
    fun `INV-AC-20653 the payload docsequence is the one reserved for the row, also after a seeded counter`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t = UUID.randomUUID()
        val d6 = insert(t)
        CanonicalDb.superuser().use {
            it.createStatement().use { s ->
                s.execute("insert into canonical.outbox_sequence values ('$t', '$d6', 6)")
            }
        }
        b.holdAs(t, d6)
        val row = b.rowsOf(t, d6).single()
        row[3] shouldBe "7"
        row[4] shouldBe "7"
    }

    @Test
    fun `INV-AC-20653 a document without posting facts leaves both optional members out of the payload`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t = UUID.randomUUID()
        val d2 = insert(t)
        b.holdAs(t, d2)
        val row = b.rowsOf(t, d2).single()
        row[11] shouldBe null
        row[12] shouldBe null
        row[13] shouldBe "false"
        row[14] shouldBe "false"
    }

    @Test
    fun `INV-AC-20653 a document with a null prescribed_period_ref is refused and nothing is written`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t = UUID.randomUUID()
        val d5 = insert(t, period = null)
        shouldThrow<IllegalStateException> { b.holdAs(t, d5) }
        b.stateOf(t, d5) shouldBe listOf("INGESTED", null)
        b.rowsOf(t, d5).size shouldBe 0
    }

    @Test
    fun `INV-AC-20653 a failure after the event is appended rolls back state, reasons and the row`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t = UUID.randomUUID()
        val d4 = insert(t)
        val failing =
            object : HeldEventPublisher {
                override fun stage(event: HeldEvent) {
                    b.publisher.stage(event)
                    error("write after the append fails")
                }
            }
        shouldThrow<IllegalStateException> { b.holdAs(t, d4, HoldDocument(b.store, failing, b.runner)) }
        b.stateOf(t, d4) shouldBe listOf("INGESTED", null)
        b.rowsOf(t, d4).size shouldBe 0
    }

    @Test
    fun `INV-AC-20653 the runtime login may update hold_reasons only, of the new columns`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t = UUID.randomUUID()
        val d = insert(t)
        b.holdAs(t, d)
        b.stateOf(t, d)[1] shouldBe "{ING-016,PER-009}"
        runtimeRefusal(t, "update canonical.document set hold_reasons = hold_reasons") shouldBe null
        runtimeRefusal(t, "update canonical.document set posting_timestamp = now()") shouldBe "42501"
        runtimeRefusal(t, "update canonical.document set document_date = current_date") shouldBe "42501"
    }

    private fun heldEvent(
        doc: UUID,
        partyRef: String?,
    ) = HeldEvent(
        docUuid = doc,
        holdReasons = reasons,
        entityRef = "E-001",
        direction = com.dynokore.gst.canonical.domain.document.Direction.SALE,
        documentDate = java.time.LocalDate.parse("2026-03-20"),
        prescribedPeriodRef = "2026-Q1",
        postingTimestamp = null,
        invoiceIssuedOrReceived = null,
        partyRef = partyRef,
    )

    private fun Boundary.stage(
        tenant: UUID,
        event: HeldEvent,
    ) = scope.bind(TenantId(tenant), actor) { runner.inTransaction { publisher.stage(event) } }

    private fun Boundary.payloadsOf(
        tenant: UUID,
        doc: UUID,
    ) = read(tenant, "select data::text from canonical.outbox where doc_uuid = ?::uuid order by seq", doc.toString())
        .map { mapper.readTree(it.single()!!) }

    private val mapper = ObjectMapper()

    private fun schemaText(): String {
        val dir = File(checkNotNull(System.getProperty("gst.contract.dir")) { "gst.contract.dir is not set" })
        return File(dir, "schemas/document-held-payload.schema.json").readText()
    }

    private fun schemaErrors(data: String): List<String> {
        val schema = schemaText()
        val registry =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }
        return registry.getSchema(schema).validate(mapper.readTree(data)).map { it.toString() }
    }

    @Test
    fun `INV-AC-21342 the payload carries partyRef exactly when the event has one and both validate`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t = UUID.randomUUID()
        val withRef = insert(t)
        val without = insert(t)
        b.stage(t, heldEvent(withRef, "P1"))
        b.stage(t, heldEvent(without, null))

        val declared = mapper.readTree(schemaText()).get("properties").get("partyRef")
        declared.get("maxLength").intValue() shouldBe 40
        val first = b.payloadsOf(t, withRef).single()
        first.get("partyRef").asString() shouldBe "P1"
        first.get("partyRef").isString shouldBe true
        first.get("docUuid").asString() shouldBe withRef.toString()
        first.get("entityRef").asString() shouldBe "E-001"
        first.get("direction").asString() shouldBe "SALE"
        first.get("documentDate").asString() shouldBe "2026-03-20"
        first.get("prescribedPeriodRef").asString() shouldBe "2026-Q1"
        first.get("holdReasons").values().map { it.asString() } shouldBe reasons
        first.has("postingTimestamp") shouldBe false
        schemaErrors(first.toString()) shouldBe emptyList()

        val second = b.payloadsOf(t, without).single()
        second.has("partyRef") shouldBe false
        schemaErrors(second.toString()) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21342 a partyRef of 40 characters is written and one of 41 is refused with no row`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t = UUID.randomUUID()
        val ok = insert(t)
        val tooLong = insert(t)
        b.stage(t, heldEvent(ok, "x".repeat(40)))
        b
            .payloadsOf(t, ok)
            .single()
            .get("partyRef")
            .asString() shouldBe "x".repeat(40)
        shouldThrow<IllegalArgumentException> { b.stage(t, heldEvent(tooLong, "x".repeat(41))) }
        b.payloadsOf(t, tooLong).size shouldBe 0
    }

    @Test
    fun `INV-AC-21342 a blank partyRef is refused before any row is appended`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t = UUID.randomUUID()
        listOf("", " ", "   ").forEach { blank ->
            val d = insert(t)
            shouldThrow<IllegalArgumentException> { b.stage(t, heldEvent(d, blank)) }
            b.payloadsOf(t, d).size shouldBe 0
        }
    }
}
