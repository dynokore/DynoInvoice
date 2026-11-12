package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqLocatorStore
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.EvidenceRecord
import com.dynokore.platform.evidencestore.JdbcEvidenceLedger
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-20748 over PostgreSQL and the TPL-18 context: the DECISION_TRACE rows carry the version of their locator
 * through `ChainWiring.recordAppender`, on both paths (arrival after the locator, and drain of a pending trace), and
 * no other chain table has a version.
 */
class DecisionTraceVersionBootstrapDbTest {
    private val actor = "svc:evidence-svc"
    private val doc = UUID.fromString("0b1f2c3d-0000-4000-8000-0000000000e1")
    private val at = Instant.parse("2026-10-20T10:00:00Z")
    private val content = """{"trace":1}""".toByteArray()

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val appender = ChainWiring.recordAppender(dsl)
    }

    private fun run(
        dir: Path,
        block: Boundary.(TenantId) -> Unit,
    ) {
        EvidenceContext.online(dir).run { context -> Boundary(context).block(TenantId(UUID.randomUUID())) }
    }

    private fun <T> Boundary.tx(
        tenant: TenantId,
        body: () -> T,
    ): T = scope.bind(tenant, actor) { runner.inTransaction { body() } }

    private fun Boundary.locate(
        tenant: TenantId,
        version: Int,
    ) = tx(tenant) {
        JooqLocatorStore(dsl).insert(
            tenant,
            "L$version",
            DocumentLocator(doc, version, "ENT-1", "2026-Q1", LocalDate.of(2026, 1, 15), "trace/$version", "INV-1"),
            at,
        )
    }

    private fun Boundary.chain(
        tenant: TenantId,
        kind: RecordKind,
    ): List<EvidenceRecord> =
        tx(tenant) {
            JdbcEvidenceLedger(dsl, ChainWiring.SCHEMA, kind.table, kind == RecordKind.DECISION_TRACE)
                .findByEntityAndPeriod(tenant, EntityRef("ENT-1"), PeriodRef("2026-Q1"))
        }

    private fun Boundary.versions(tenant: TenantId): List<Int?> =
        chain(tenant, RecordKind.DECISION_TRACE).map { it.index.version }

    private fun Boundary.trace(
        tenant: TenantId,
        version: Int,
        eventId: String,
    ) = tx(tenant) { appender.appendBound(tenant, RecordKind.DECISION_TRACE, doc, version, eventId, content, at) }

    @Test
    fun `INV-AC-20748 the decision_trace rows of versions 1 and 2 of one Doc UUID carry 1 and 2`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        locate(tenant, 1)
        locate(tenant, 2)
        trace(tenant, 1, "T1")
        trace(tenant, 2, "T2")
        versions(tenant) shouldBe listOf(1, 2)
        chain(tenant, RecordKind.DECISION_TRACE).map { it.index.docUuid }.toSet() shouldBe setOf(doc)
    }

    @Test
    fun `INV-AC-20748 a trace that arrived before its locator carries the locator version after drainPending`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        trace(tenant, 3, "T3")
        versions(tenant) shouldBe emptyList()
        locate(tenant, 3)
        tx(tenant) { appender.drainPending(tenant, doc, 3) } shouldBe 1
        versions(tenant) shouldBe listOf(3)
    }

    @Test
    fun `INV-AC-20748 the payload row of version 3 has no version column`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        locate(tenant, 3)
        tx(tenant) {
            appender.appendBound(tenant, RecordKind.EVIDENCE_PAYLOAD, doc, 3, "P3", content, at)
        }
        chain(tenant, RecordKind.EVIDENCE_PAYLOAD).map { it.index.version } shouldBe listOf(null)
        tx(tenant) {
            dsl.fetchCount(
                org.jooq.impl.DSL
                    .table("information_schema.columns"),
                org.jooq.impl.DSL
                    .field("table_schema")
                    .eq("evidence")
                    .and(
                        org.jooq.impl.DSL
                            .field("table_name")
                            .eq("evidence_payload"),
                    ).and(
                        org.jooq.impl.DSL
                            .field("column_name")
                            .eq("version"),
                    ),
            )
        } shouldBe 0
    }

    @Test
    fun `INV-AC-20748 a second tenant reads none of the rows`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        locate(tenant, 3)
        trace(tenant, 3, "T3")
        versions(tenant) shouldBe listOf(3)
        val other = TenantId(UUID.randomUUID())
        chain(other, RecordKind.DECISION_TRACE) shouldBe emptyList()
    }
}
