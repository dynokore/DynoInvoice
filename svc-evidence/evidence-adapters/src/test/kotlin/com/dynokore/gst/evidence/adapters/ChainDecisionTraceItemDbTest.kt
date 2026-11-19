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

    private fun contentHashes(
        tenant: UUID,
        version: Int,
    ): List<String> =
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select content_hash from evidence.decision_trace where tenant_id = ? and version = ? order by seq",
                ).use {
                    it.setObject(1, tenant)
                    it.setInt(2, version)
                    it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                }
        }

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
        traces.map { it.reference } shouldBe contentHashes(t, 2)
        traces.map { it.packVersions } shouldBe listOf(listOf(m, s), listOf(m))
        traces.map { it.recordedAt } shouldBe listOf(at, at)
        traces.map { it.key.seq } shouldBe listOf(0L, 1L)
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
}
