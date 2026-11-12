package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.gst.canonical.adapters.events.OutboxHeldEventPublisher
import com.dynokore.gst.canonical.adapters.events.OutboxReleasedEventPublisher
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentHoldStore
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentReleaseStore
import com.dynokore.gst.canonical.application.document.HoldDocument
import com.dynokore.gst.canonical.application.document.ReleaseMasterDataHold
import com.dynokore.gst.canonical.application.document.ReleaseResult
import com.dynokore.gst.canonical.application.document.ReleasedEvent
import com.dynokore.gst.canonical.application.document.ReleasedEventPublisher
import com.dynokore.gst.canonical.domain.derivation.GateDecision
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.util.UUID

/**
 * The release of held documents against a real database (P109): the use case over the jOOQ store and the
 * OutboxWriter in the full TPL-18 context, as the `canonical_runtime` login bound to a tenant. State, reasons and
 * the DocumentReleased outbox row commit or roll back together (INV-REQ-479, INV-REQ-1221).
 */
class ReleaseDocumentOutboxDbTest {
    private val actor = SignedBindings.ACTOR
    private val held = GateDecision.Held(listOf("MDM-001"))

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
        val releasePublisher =
            OutboxReleasedEventPublisher(outbox, scope.tenants, { CorrelationId("corr-1") }, { CausationId("cause-1") })
        val releaseStore = JooqDocumentReleaseStore(store, dsl, scope.tenants)
        val release = ReleaseMasterDataHold(releaseStore, releasePublisher, runner)
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
    ) = scope.bind(TenantId(tenant), actor) { hold.hold(doc, held) }

    private fun Boundary.releaseAs(
        tenant: UUID,
        docs: List<UUID>,
        use: ReleaseMasterDataHold = release,
    ) = scope.bind(TenantId(tenant), actor) { use.release(docs) }

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
        "select type, topic, subject, docsequence::text, data::text, data->>'docsequence' " +
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

    private fun insert(
        tenant: UUID,
        state: String = "INGESTED",
        reasons: String? = null,
    ): UUID {
        val doc = UUID.randomUUID()
        CanonicalDb.superuser().use {
            it.createStatement().use { s ->
                s.execute(
                    "insert into canonical.document (doc_uuid, tenant_id, entity_id, direction, document_type_code, " +
                        "document_number, issuer_party_key, fiscal_year, partition_date, transaction_date, " +
                        "current_version, state, entity_ref, document_date, prescribed_period_ref, hold_reasons) " +
                        "values ('$doc', '$tenant', '${UUID.randomUUID()}', 'SALE', 380, 'N-$doc', 'issuer', 2026, " +
                        "'2026-03-25', '2026-03-25', 1, '$state', 'E-001', '2026-03-20', '2026-Q1', " +
                        "${reasons?.let { "'$it'" } ?: "null"})",
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

    private fun ownerRead(doc: UUID): List<String?> =
        CanonicalDb.superuser().use {
            it.createStatement().use { s ->
                val sql = "select state, hold_reasons::text from canonical.document where doc_uuid = '$doc'"
                s.executeQuery(sql).use { r ->
                    r.next()
                    listOf(r.getString(1), r.getString(2))
                }
            }
        }

    @Test
    fun `INV-AC-21248 the release commits state, cleared reasons and one DocumentReleased row, a failure rolls back`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val d1 = insert(t1)
        val d2 = insert(t1)
        val d3 = insert(t2)
        b.holdAs(t1, d1)
        b.holdAs(t2, d3)
        val n = b.rowsOf(t1, d1).single()[3]!!.toLong()
        val d3Before = b.rowsOf(t2, d3)

        b.releaseAs(t1, listOf(d1, d2, d3)) shouldBe
            mapOf(d1 to ReleaseResult.RELEASED, d2 to ReleaseResult.NOT_HELD, d3 to ReleaseResult.NOT_FOUND)
        b.releaseAs(t1, listOf(d1)) shouldBe mapOf(d1 to ReleaseResult.NOT_HELD)

        b.stateOf(t1, d1) shouldBe listOf("INGESTED", null)
        val rows = b.rowsOf(t1, d1)
        rows.size shouldBe 2
        val released = rows.single { it[0] == "com.dynokore.gst.canonical.DocumentReleased.v1" }
        released[1] shouldBe "gst.canonical.document-released.v1"
        released[2] shouldBe d1.toString()
        // the column is jsonb: Postgres renders the stored payload with a space after ':' and ','
        released[4] shouldBe """{"docUuid": "$d1", "docsequence": ${n + 1}, "releasedHoldReasons": ["MDM-001"]}"""
        released[5] shouldBe released[3]
        released[3]!!.toLong() shouldBe n + 1
        rows.single { it[0]!!.endsWith("DocumentHeld.v1") }[3]!!.toLong() shouldBe n

        b.stateOf(t1, d2) shouldBe listOf("INGESTED", null)
        b.rowsOf(t1, d2).size shouldBe 0
        b.stateOf(t2, d3) shouldBe listOf("HELD", "{MDM-001}")
        b.rowsOf(t2, d3) shouldBe d3Before
        ownerRead(d3) shouldBe listOf("HELD", "{MDM-001}")
    }

    @Test
    fun `INV-AC-21248 a release followed by a failure after the stage leaves the document HELD and no row`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        val d4 = insert(t1)
        b.holdAs(t1, d4)
        val failing =
            ReleasedEventPublisher { event: ReleasedEvent ->
                b.releasePublisher.stage(event)
                error("write after the stage fails")
            }
        shouldThrow<IllegalStateException> {
            b.releaseAs(t1, listOf(d4), ReleaseMasterDataHold(b.releaseStore, failing, b.runner))
        }

        b.stateOf(t1, d4) shouldBe listOf("HELD", "{MDM-001}")
        b.rowsOf(t1, d4).map { it[0] } shouldBe listOf("com.dynokore.gst.canonical.DocumentHeld.v1")
    }

    @Test
    fun `INV-AC-21248 the runtime login may not update document_date`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t = UUID.randomUUID()
        val d = insert(t)
        b.holdAs(t, d)
        b.releaseAs(t, listOf(d)) shouldBe mapOf(d to ReleaseResult.RELEASED)
        runtimeRefusal(t, "update canonical.document set document_date = current_date") shouldBe "42501"
    }
}
