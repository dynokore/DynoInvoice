package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqChainRecordReader
import com.dynokore.gst.evidence.adapters.persistence.JooqLocatorStore
import com.dynokore.gst.evidence.adapters.persistence.JooqSealedPayloadSink
import com.dynokore.gst.evidence.application.lookup.ChainItemKind
import com.dynokore.gst.evidence.application.lookup.ChainRecords
import com.dynokore.gst.evidence.application.lookup.PackVersionRef
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.JdbcEvidenceLedger
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-20779: the DECISION_TRACE items of one version, read from `evidence.decision_trace`. */
class ChainDecisionTraceItemDbTest {
    private val doc = UUID.fromString("0b1f2c3d-0000-4000-8000-0000000000b1")
    private val at = Instant.parse("2026-10-20T10:00:00Z")
    private val m = PackVersionRef("mapping", "2026.3")
    private val s = PackVersionRef("scope-rules", "2026.2")

    private fun <T> tx(
        tenant: UUID,
        body: (DSLContext, TenantId) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            body(DSL.using(c, SQLDialect.POSTGRES), TenantId(tenant)).also { c.commit() }
        }

    private fun reader(dsl: DSLContext) =
        JooqChainRecordReader(
            dsl,
            RecordKind.entries.associateWith { JdbcEvidenceLedger(dsl, ChainWiring.SCHEMA, it.table) },
        )

    private fun locate(
        tenant: UUID,
        version: Int,
    ) = tx(tenant) { dsl, t ->
        JooqLocatorStore(dsl).insert(
            t,
            "L$version",
            DocumentLocator(doc, version, "ENT-1", "2026-Q1", LocalDate.of(2026, 1, 15), "trace/$version", "INV-1"),
            at,
        )
    }

    private fun seal(
        tenant: UUID,
        version: Int,
    ) = tx(tenant) { dsl, t ->
        RetentionPartitionFixture.ensureRetentionPartition(dsl, "evidence_payload", tenant, "ENT-1", "2026-Q1")
        JooqSealedPayloadSink(dsl, ChainWiring.recordAppender(dsl, Clock.fixed(at, ZoneOffset.UTC)))
            .append(t, doc, version, "P$version", "{\"e\":$version}".toByteArray(), at)
    }

    private fun acknowledge(
        tenant: UUID,
        version: Int,
    ) = tx(tenant) { dsl, t ->
        RetentionPartitionFixture.ensureRetentionPartition(dsl, "acknowledgement", tenant, "ENT-1", "2026-Q1")
        ChainWiring
            .acknowledgementRecorder(dsl, Clock.fixed(at, ZoneOffset.UTC))
            .record(t, "ACK-$version", "A$version", doc, version, "{\"ack\":1}".toByteArray(), at)
    }

    private fun trace(
        tenant: UUID,
        version: Int,
        eventId: String,
        content: String,
        appliedAt: Instant = at,
    ) = tx(tenant) { dsl, t ->
        RetentionPartitionFixture.ensureRetentionPartition(dsl, "decision_trace", tenant, "ENT-1", "2026-Q1")
        ChainWiring
            .recordAppender(dsl, Clock.fixed(appliedAt, ZoneOffset.UTC))
            .appendBound(t, RecordKind.DECISION_TRACE, doc, version, eventId, content.toByteArray(), appliedAt)
    }

    private fun decisions(vararg packs: List<PackVersionRef>) =
        """{"decisions":[${packs.joinToString(",") { ps ->
            val pv = ps.joinToString(",") { """{"packIdentifier":"${it.packIdentifier}","version":"${it.version}"}""" }
            """{"code":"SRT-001","ruleId":"R1","packVersions":[$pv],"inputs":[]}"""
        }}]}"""

    private fun read(
        tenant: UUID,
        version: Int,
    ) = tx(tenant) { dsl, t -> reader(dsl).read(t, "A$version", doc, version) }

    private data class Row(
        val seq: Long,
        val hash: String,
        val recordedAt: Instant,
    )

    private fun rows(
        tenant: UUID,
        version: Int,
    ): List<Row> =
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select seq, content_hash, recorded_at from evidence.decision_trace " +
                        "where tenant_id = ? and version = ? order by seq",
                ).use {
                    it.setObject(1, tenant)
                    it.setInt(2, version)
                    it.executeQuery().use { r ->
                        buildList {
                            while (r.next()) {
                                add(
                                    Row(r.getLong(1), r.getString(2), r.getTimestamp(3).toInstant()),
                                )
                            }
                        }
                    }
                }
        }

    /** A trace row written straight (superuser), with or without its `applied_record` row. */
    @Suppress("LongParameterList") // one row's identity
    private fun insertTrace(
        tenant: UUID,
        entity: String,
        period: String,
        seq: Long,
        docUuid: UUID,
        withApplied: Boolean,
    ) = EvidenceDb.superuser().use { c ->
        RetentionPartitionFixture.ensureRetentionPartition(c, "decision_trace", tenant, entity, period)
        c
            .prepareStatement(
                "insert into evidence.decision_trace (id, tenant_id, entity_ref, period_ref, seq, evidence_type, " +
                    "content, content_hash, recorded_at, doc_uuid, version) " +
                    "values (gen_random_uuid(), ?, ?, ?, ?, 'DECISION_TRACE', ?, 'h', now(), ?, 2)",
            ).use {
                it.setObject(1, tenant)
                it.setString(2, entity)
                it.setString(3, period)
                it.setLong(4, seq)
                it.setBytes(5, decisions(listOf(m)).toByteArray())
                it.setObject(6, docUuid)
                it.executeUpdate()
            }
        if (withApplied) {
            c
                .prepareStatement(
                    "insert into evidence.applied_record (tenant_id, source_event_id, kind, entity_ref, " +
                        "period_ref, seq, applied_at) values (?, ?, 'decision_trace', ?, ?, ?, now())",
                ).use {
                    it.setObject(1, tenant)
                    it.setString(2, "T-DIRECT-$seq")
                    it.setString(3, entity)
                    it.setString(4, period)
                    it.setLong(5, seq)
                    it.executeUpdate()
                }
        }
    }

    private fun traceItems(records: ChainRecords) =
        records.shouldBeInstanceOf<ChainRecords.Complete>().items.filter { it.kind == ChainItemKind.DECISION_TRACE }

    private fun fixture(t: UUID) {
        locate(t, 2)
        seal(t, 2)
        acknowledge(t, 2)
    }

    @Test
    fun `INV-AC-20779 two trace rows give two items in seq order with hash reference and distinct pack versions`() {
        val t = UUID.randomUUID()
        fixture(t)
        trace(t, 2, "T1", decisions(listOf(m, s), listOf(m)))
        trace(t, 2, "T2", decisions(listOf(m)))
        val items = read(t, 2).shouldBeInstanceOf<ChainRecords.Complete>().items
        val traces = items.filter { it.kind == ChainItemKind.DECISION_TRACE }
        traces.size shouldBe 2
        val stored = rows(t, 2)
        stored.size shouldBe 2
        traces.map { it.reference } shouldBe stored.map { it.hash }
        traces.map { it.packVersions } shouldBe listOf(listOf(m, s), listOf(m))
        traces.map { it.recordedAt } shouldBe stored.map { it.recordedAt }
        traces.map { it.key.seq } shouldBe stored.map { it.seq }
        items.map { it.kind } shouldBe
            listOf(
                ChainItemKind.PAYLOAD,
                ChainItemKind.ACKNOWLEDGEMENT,
                ChainItemKind.DECISION_TRACE,
                ChainItemKind.DECISION_TRACE,
            )
        items.map { it.sequence } shouldBe listOf(1L, 2L, 3L, 4L)
    }

    @Test
    fun `INV-AC-20779 a trace of another version, a null-version row and another tenant's row are not items`() {
        val t = UUID.randomUUID()
        fixture(t)
        locate(t, 3)
        trace(t, 3, "T-V3", decisions(listOf(s)))
        trace(t, 4, "T-NULL", decisions(listOf(s))) // no locator for version 4: stored with a null version
        trace(t, 2, "T1", decisions(listOf(m)))
        val other = UUID.randomUUID()
        locate(other, 2)
        trace(other, 2, "T-OTHER", decisions(listOf(s)))
        val items = read(t, 2).shouldBeInstanceOf<ChainRecords.Complete>().items
        items.filter { it.kind == ChainItemKind.DECISION_TRACE }.map { it.packVersions } shouldBe listOf(listOf(m))
    }

    @Test
    fun `INV-AC-20779 a row whose content has no decisions member makes the lookup Incomplete`() {
        val t = UUID.randomUUID()
        fixture(t)
        trace(t, 2, "T1", decisions(listOf(m)))
        trace(t, 2, "T2", "{\"other\":1}")
        read(t, 2) shouldBe ChainRecords.Incomplete
    }

    @Test
    fun `INV-AC-20779 a version with no trace row is Found with no DECISION_TRACE item`() {
        val t = UUID.randomUUID()
        fixture(t)
        val items = read(t, 2).shouldBeInstanceOf<ChainRecords.Complete>().items
        items.map { it.kind } shouldBe listOf(ChainItemKind.PAYLOAD, ChainItemKind.ACKNOWLEDGEMENT)
    }

    @Test
    fun `INV-AC-20779 a trace row with no applied_record row makes the lookup Incomplete, with the row it is Found`() {
        val withRow = UUID.randomUUID()
        fixture(withRow)
        insertTrace(withRow, "ENT-1", "2026-Q1", 1, doc, withApplied = true)
        traceItems(read(withRow, 2)).size shouldBe 1
        val without = UUID.randomUUID()
        fixture(without)
        insertTrace(without, "ENT-1", "2026-Q1", 1, doc, withApplied = false)
        read(without, 2) shouldBe ChainRecords.Incomplete
    }

    @Test
    fun `INV-AC-20779 a trace row of another entity or period makes the lookup Incomplete`() {
        for ((entity, period) in listOf("ENT-2" to "2026-Q1", "ENT-1" to "2026-Q2")) {
            val t = UUID.randomUUID()
            fixture(t)
            insertTrace(t, entity, period, 1, doc, withApplied = true)
            read(t, 2) shouldBe ChainRecords.Incomplete
        }
    }

    @Test
    fun `INV-AC-20779 a trace of another document with the same entity and period is no item and not Incomplete`() {
        val t = UUID.randomUUID()
        fixture(t)
        insertTrace(t, "ENT-1", "2026-Q1", 1, UUID.randomUUID(), withApplied = true)
        insertTrace(t, "ENT-9", "2026-Q9", 1, UUID.randomUUID(), withApplied = false)
        val items = read(t, 2).shouldBeInstanceOf<ChainRecords.Complete>().items
        items.map { it.kind } shouldBe listOf(ChainItemKind.PAYLOAD, ChainItemKind.ACKNOWLEDGEMENT)
    }
}
