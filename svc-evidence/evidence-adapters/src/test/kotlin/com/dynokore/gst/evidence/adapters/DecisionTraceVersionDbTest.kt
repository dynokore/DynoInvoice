package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqLocatorStore
import com.dynokore.gst.evidence.application.chain.RecordAppender
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.EvidenceIndex
import com.dynokore.platform.evidencestore.EvidenceLedger
import com.dynokore.platform.evidencestore.EvidenceType
import com.dynokore.platform.evidencestore.JdbcEvidenceLedger
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** INV-AC-20715: the nullable `version` of `evidence.decision_trace`, written only by the ledger built for it. */
class DecisionTraceVersionDbTest {
    private val doc = UUID.fromString("0b1f2c3d-0000-4000-8000-0000000000d1")
    private val at = Instant.parse("2026-10-20T10:00:00Z")
    private val content = "{\"trace\":1}".toByteArray()

    private fun <T> tx(
        tenant: UUID,
        body: (DSLContext, TenantId) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            body(DSL.using(c, SQLDialect.POSTGRES), TenantId(tenant)).also { c.commit() }
        }

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

    private fun versions(
        tenant: UUID,
        table: String = "decision_trace",
    ): List<Int?> =
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement("select version from evidence.$table where tenant_id = ? order by seq")
                .use {
                    it.setObject(1, tenant)
                    it.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(rows.getObject(1) as Int?) }
                    }
                }
        }

    private fun traceLedger(dsl: DSLContext) = JdbcEvidenceLedger(dsl, ChainWiring.SCHEMA, "decision_trace", true)

    @Test
    fun `INV-AC-20715 a DECISION_TRACE record through ChainWiring recordAppender has a null version, read back`() {
        val t = UUID.randomUUID()
        locate(t, 3)
        tx(t) { dsl, tenant ->
            ChainWiring
                .recordAppender(dsl)
                .appendBound(tenant, RecordKind.DECISION_TRACE, doc, 3, "E1", content, at)
        }
        versions(t) shouldBe listOf(null)
        tx(t) { dsl, tenant ->
            val read = traceLedger(dsl).findByEntityAndPeriod(tenant, EntityRef("ENT-1"), PeriodRef("2026-Q1"))
            read.size shouldBe 1
            read.single().index.version shouldBe null
            read.single().index.docUuid shouldBe doc
        }
    }

    @Test
    fun `INV-AC-20715 a pending DECISION_TRACE record drained after the locator has a null version`() {
        val t = UUID.randomUUID()
        tx(t) { dsl, tenant ->
            ChainWiring
                .recordAppender(dsl)
                .appendBound(tenant, RecordKind.DECISION_TRACE, doc, 3, "E1", content, at)
        }
        versions(t) shouldBe emptyList()
        locate(t, 3)
        tx(t) { dsl, tenant -> ChainWiring.recordAppender(dsl).drainPending(tenant, doc, 3) } shouldBe 1
        versions(t) shouldBe listOf(null)
    }

    @Test
    fun `INV-AC-20715 an append whose index carries version 3 stores 3 and reads it back`() {
        val t = UUID.randomUUID()
        tx(t) { dsl, tenant ->
            traceLedger(dsl).append(
                tenant,
                EntityRef("ENT-1"),
                PeriodRef("2026-Q1"),
                EvidenceType.DECISION_TRACE,
                content,
                EvidenceIndex(docUuid = doc, invoiceNumber = "INV-1", version = 3),
            )
        }
        versions(t) shouldBe listOf(3)
        tx(t) { dsl, tenant ->
            traceLedger(dsl)
                .findByEntityAndPeriod(tenant, EntityRef("ENT-1"), PeriodRef("2026-Q1"))
                .single()
                .index.version shouldBe 3
        }
    }

    private fun append(
        ledger: JdbcEvidenceLedger,
        tenant: TenantId,
        type: EvidenceType,
    ) = ledger.append(
        tenant,
        EntityRef("ENT-1"),
        PeriodRef("2026-Q1"),
        type,
        content,
        EvidenceIndex(docUuid = doc, version = 3),
    )

    private fun countOf(
        table: String,
        tenant: UUID,
    ): Int =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement("select count(*) from evidence.$table where tenant_id = ?").use {
                it.setObject(1, tenant)
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    @Test
    fun `INV-AC-20715 the other three chain tables have no version column`() {
        EvidenceDb.superuser().use { c ->
            RecordKind.entries.filter { it != RecordKind.DECISION_TRACE }.forEach { kind ->
                c
                    .prepareStatement(
                        "select count(*) from information_schema.columns where table_schema = 'evidence' " +
                            "and table_name = ? and column_name = 'version'",
                    ).use {
                        it.setString(1, kind.table)
                        it.executeQuery().use { rows ->
                            rows.next()
                            rows.getInt(1) shouldBe 0
                        }
                    }
            }
        }
    }

    @Test
    fun `INV-AC-20715 a ledger built without the version column refuses a version`() {
        tx(UUID.randomUUID()) { dsl, tenant ->
            // the wiring builds the DECISION_TRACE ledger alone with the column
            shouldThrow<IllegalArgumentException> {
                append(JdbcEvidenceLedger(dsl, ChainWiring.SCHEMA, "evidence_payload"), tenant, EvidenceType.PAYLOAD)
            }
            shouldThrow<IllegalArgumentException> {
                append(
                    JdbcEvidenceLedger(dsl, ChainWiring.SCHEMA, "decision_trace"),
                    tenant,
                    EvidenceType.DECISION_TRACE,
                )
            }
            shouldThrow<IllegalArgumentException> { append(traceLedger(dsl), tenant, EvidenceType.PAYLOAD) }
        }
    }

    @Test
    fun `INV-AC-20715 an EVIDENCE_PAYLOAD record for the same version is stored through the wiring`() {
        val t = UUID.randomUUID()
        locate(t, 3)
        tx(t) { dsl, tenant ->
            ChainWiring
                .recordAppender(dsl)
                .appendBound(tenant, RecordKind.EVIDENCE_PAYLOAD, doc, 3, "P1", content, at)
        }
        countOf("evidence_payload", t) shouldBe 1
    }

    private fun refusesVersion(
        ledgers: Map<RecordKind, EvidenceLedger>,
        kind: RecordKind,
        type: EvidenceType,
        tenant: TenantId,
    ): Boolean =
        runCatching {
            checkNotNull(ledgers[kind]).append(
                tenant,
                EntityRef("ENT-1"),
                PeriodRef("2026-Q1"),
                type,
                content,
                EvidenceIndex(docUuid = doc, version = 3),
            )
        }.exceptionOrNull() is IllegalArgumentException

    private fun types() =
        mapOf(
            RecordKind.DECISION_TRACE to EvidenceType.DECISION_TRACE,
            RecordKind.EVIDENCE_PAYLOAD to EvidenceType.PAYLOAD,
            RecordKind.ACKNOWLEDGEMENT to EvidenceType.ACKNOWLEDGEMENT,
            RecordKind.AUDIT_LOG to EvidenceType.AUDIT_LOG,
        )

    @Test
    fun `INV-AC-20715 ChainWiring builds the version column for the DECISION_TRACE ledger only`() {
        tx(UUID.randomUUID()) { dsl, tenant ->
            val ledgers = ChainWiring.ledgers(dsl)
            val refused = types().mapValues { (kind, type) -> refusesVersion(ledgers, kind, type, tenant) }
            refused shouldBe
                mapOf(
                    RecordKind.DECISION_TRACE to false,
                    RecordKind.EVIDENCE_PAYLOAD to true,
                    RecordKind.ACKNOWLEDGEMENT to true,
                    RecordKind.AUDIT_LOG to true,
                )
        }
    }

    @Test
    fun `INV-AC-20715 recordAppender ledgers have the column on DECISION_TRACE only`() {
        tx(UUID.randomUUID()) { dsl, tenant ->
            val field = RecordAppender::class.java.getDeclaredField("ledgers").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val inside = field.get(ChainWiring.recordAppender(dsl)) as Map<RecordKind, EvidenceLedger>
            inside.keys shouldBe RecordKind.entries.toSet()
            val refused = types().mapValues { (kind, type) -> refusesVersion(inside, kind, type, tenant) }
            refused.filterValues { !it }.keys shouldBe setOf(RecordKind.DECISION_TRACE)
        }
    }

    private fun ownerInsert(
        c: Connection,
        tenant: UUID,
        version: Int,
    ) = c
        .prepareStatement(
            "insert into evidence.decision_trace (id, tenant_id, entity_ref, period_ref, seq, evidence_type, " +
                "content, content_hash, recorded_at, version) " +
                "values (gen_random_uuid(), ?, 'E', 'P', 0, 'T', '\\x00', 'h', now(), ?)",
        ).use {
            it.setObject(1, tenant)
            it.setInt(2, version)
            it.executeUpdate()
        }

    @Test
    fun `INV-AC-20715 inserting version 0 through the owner connection is refused by the check`() {
        val t = UUID.randomUUID()
        EvidenceDb.connect(EvidenceDb.MIGRATE, EvidenceDb.MIGRATE_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, t)
            shouldThrow<SQLException> { ownerInsert(c, t, 0) }.sqlState shouldBe "23514"
            c.rollback()
        }
    }

    @Test
    fun `INV-AC-20715 UPDATE and DELETE of decision_trace rows are still refused with CS-22`() {
        val t = UUID.randomUUID()
        tx(t) { dsl, tenant ->
            traceLedger(dsl).append(
                tenant,
                EntityRef("ENT-1"),
                PeriodRef("2026-Q1"),
                EvidenceType.DECISION_TRACE,
                content,
                EvidenceIndex(docUuid = doc, version = 3),
            )
        }
        EvidenceDb.connect(EvidenceDb.MIGRATE, EvidenceDb.MIGRATE_PASSWORD).use { c ->
            c.autoCommit = false
            listOf("update evidence.decision_trace set version = 4", "delete from evidence.decision_trace").forEach {
                SignedBindings.bind(c, t)
                val failure = runCatching { c.createStatement().use { s -> s.execute(it) } }.exceptionOrNull()
                (failure as SQLException).message!!.contains("CS-22") shouldBe true
                c.rollback()
            }
        }
    }

    @Test
    fun `INV-AC-20715 evidence_runtime has only SELECT and INSERT on decision_trace`() {
        val privileges =
            EvidenceDb.superuser().use { c ->
                c
                    .prepareStatement(
                        "select privilege_type from information_schema.role_table_grants where table_schema = " +
                            "'evidence' and table_name = 'decision_trace' and grantee = 'evidence_runtime'",
                    ).use {
                        it.executeQuery().use { rows ->
                            generateSequence { if (rows.next()) rows.getString(1) else null }.toSet()
                        }
                    }
            }
        privileges shouldBe setOf("SELECT", "INSERT")
    }

    @Test
    fun `INV-AC-20715 a second tenant reads none of the rows`() {
        val a = UUID.randomUUID()
        tx(a) { dsl, tenant ->
            traceLedger(dsl).append(
                tenant,
                EntityRef("ENT-1"),
                PeriodRef("2026-Q1"),
                EvidenceType.DECISION_TRACE,
                content,
                EvidenceIndex(docUuid = doc, version = 3),
            )
        }
        tx(UUID.randomUUID()) { dsl, tenant ->
            traceLedger(dsl).findByEntityAndPeriod(tenant, EntityRef("ENT-1"), PeriodRef("2026-Q1")) shouldBe
                emptyList()
            dsl.fetchCount(DSL.table(DSL.name("evidence", "decision_trace"))) shouldBe 0
        }
        versions(a) shouldBe listOf(3)
    }
}
