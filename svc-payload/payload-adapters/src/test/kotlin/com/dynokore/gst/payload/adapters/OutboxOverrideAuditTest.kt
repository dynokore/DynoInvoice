package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.audit.OutboxOverrideAudit
import com.dynokore.gst.payload.adapters.persistence.GstCodeCatalogue
import com.dynokore.gst.payload.adapters.persistence.JooqOverrideStore
import com.dynokore.gst.payload.adapters.persistence.StoredBlockingFindings
import com.dynokore.gst.payload.application.finding.FindingsReader
import com.dynokore.gst.payload.application.override.ApproveOverride
import com.dynokore.gst.payload.application.override.AuditTrail
import com.dynokore.gst.payload.application.override.DocumentVersionFacts
import com.dynokore.gst.payload.application.override.DocumentVersions
import com.dynokore.gst.payload.application.override.OverrideAuthority
import com.dynokore.gst.payload.application.override.OverrideResult
import com.dynokore.gst.payload.application.override.ReleaseDecisions
import com.dynokore.gst.payload.application.override.RequestOverride
import com.dynokore.gst.payload.application.override.StoredOverride
import com.dynokore.gst.payload.domain.finding.StoredFinding
import com.dynokore.gst.payload.domain.override.ReleaseOutcome
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriteFailed
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.auditlog.OutboxAuditWriter
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

/** The audit adapter against the real outbox and override store as `payload_runtime` (INV-AC-11513, INV-Q-17172 A). */
class OutboxOverrideAuditTest {
    private companion object {
        /** The platform audit event every service may emit (topics.yaml, producers `every service`). */
        val AUDIT_CATALOGUE =
            EventCatalogue(
                listOf(
                    CatalogueEntry(
                        "com.dynokore.gst.platform.AuditEntryRecorded.v1",
                        "gst.platform.audit-entry-recorded.v1",
                        setOf(CatalogueEntry.EVERY_SERVICE),
                        contentTopic = false,
                        recordTopic = true,
                        retainedWhileResolvable = false,
                    ),
                ),
            )
    }

    private val tenant = UUID.randomUUID()
    private val doc = UUID.randomUUID()
    private val entity = "E-001"

    private val versions =
        DocumentVersions { d, v ->
            if (d == doc && v == 1) DocumentVersionFacts(entity, BigDecimal("100.00")) else null
        }
    private val authority = OverrideAuthority { _, _, _ -> true }
    private val findings =
        StoredBlockingFindings(
            object : FindingsReader {
                override fun blocking(
                    docUuid: UUID,
                    version: Int,
                ) = listOf(
                    StoredFinding("PAY-011", "", "m", true),
                    StoredFinding("PAY-001", "", "m", true),
                    StoredFinding("PAY-008", "", "m", true),
                )
            },
            GstCodeCatalogue(),
        )

    private class TenantFixture(
        val id: UUID,
    ) : TenantContext {
        override fun current() = TenantId(id)

        override fun currentActor(): String? = "test"

        override fun <T> bind(
            tenant: TenantId,
            actor: String,
            block: () -> T,
        ): T = block()
    }

    private fun <T> inTransaction(block: (JooqOverrideStore, AuditWriter, OutboxOverrideAudit) -> T): T =
        PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                val dsl = DSL.using(c, SQLDialect.POSTGRES)
                val real =
                    OutboxAuditWriter(
                        JdbcOutboxWriter(
                            dsl,
                            "payload",
                            "payload-svc",
                            "test-1",
                            AUDIT_CATALOGUE,
                            Clock.systemUTC(),
                        ),
                    )
                block(JooqOverrideStore(dsl), real, OutboxOverrideAudit(real, TenantFixture(tenant), versions))
            } finally {
                c.commit()
            }
        }

    private fun decisions(outcome: ReleaseOutcome) = ReleaseDecisions { outcome }

    private fun request(
        outcome: ReleaseOutcome,
        user: String,
        code: String = "PAY-011",
        reason: String? = "register lag",
    ): OverrideResult =
        inTransaction { store, _, audit ->
            RequestOverride(versions, authority, findings, decisions(outcome), store, audit)
                .execute(user, doc, 1, code, reason)
        }

    private fun approve(user: String): OverrideResult =
        inTransaction { store, _, audit ->
            ApproveOverride(versions, authority, decisions(ReleaseOutcome.Released), store, audit)
                .execute(user, doc, 1, "PAY-011")
        }

    private fun audits(action: String? = null): Int =
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select count(*) from payload.outbox where tenant_id = ? " +
                        "and type = 'com.dynokore.gst.platform.AuditEntryRecorded.v1' " +
                        "and (?::text is null or data->>'action' = ?)",
                ).use { p ->
                    p.setObject(1, tenant)
                    p.setString(2, action)
                    p.setString(3, action)
                    p.executeQuery().use { r -> r.next().let { r.getInt(1) } }
                }
        }

    @Test
    fun `INV-AC-11513 the override of an OVERRIDABLE code is audited by user ID and MANUAL and TERMINAL are refused`() {
        request(ReleaseOutcome.Released, "U-1").shouldBeInstanceOf<OverrideResult.Released>()
        audits("requested") shouldBe 1
        audits("released") shouldBe 1
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select data->>'actor', data->>'reason', data->>'scope' from payload.outbox " +
                        "where tenant_id = ? and data->>'action' = 'released'",
                ).use { p ->
                    p.setObject(1, tenant)
                    p.executeQuery().use { r ->
                        r.next()
                        listOf(r.getString(1), r.getString(2), r.getString(3)) shouldBe
                            listOf("U-1", "register lag", "$doc:1")
                    }
                }
        }
        val before = audits()
        request(ReleaseOutcome.Released, "U-1", code = "PAY-001") shouldBe OverrideResult.NotOverridable
        request(ReleaseOutcome.Released, "U-1", code = "PAY-008") shouldBe OverrideResult.NotOverridable
        audits() shouldBe before
        inTransaction { store, _, _ -> store.find(doc, 1, "PAY-001") } shouldBe null
        inTransaction { store, _, _ -> store.find(doc, 1, "PAY-008") } shouldBe null
    }

    @Test
    fun `a request counts one audit entry and a release counts two`() {
        request(ReleaseOutcome.AwaitsSecond, "U-1").shouldBeInstanceOf<OverrideResult.AwaitsSecond>()
        audits() shouldBe 1
        audits("requested") shouldBe 1
        approve("U-2").shouldBeInstanceOf<OverrideResult.Released>()
        audits() shouldBe 2
        audits("released") shouldBe 1
    }

    @Test
    fun `the release entry names the first approver in the prior state and the request entry has none`() {
        request(ReleaseOutcome.AwaitsSecond, "U-1")
        approve("U-2")
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select data->>'action', data->>'actor', data->'priorState' " +
                        "from payload.outbox where tenant_id = ?",
                ).use { p ->
                    p.setObject(1, tenant)
                    p.executeQuery().use { r ->
                        val rows = mutableMapOf<String, Pair<String, String?>>()
                        while (r.next()) rows[r.getString(1)] = r.getString(2) to r.getString(3)
                        rows.getValue("requested").first shouldBe "U-1"
                        rows.getValue("released").first shouldBe "U-2"
                        val prior = rows.getValue("released").second.orEmpty()
                        prior.contains("\"firstApprover\"") shouldBe true
                        prior.contains("U-1") shouldBe true
                    }
                }
        }
    }

    @Test
    fun `a refused approval leaves the count unchanged`() {
        request(ReleaseOutcome.AwaitsSecond, "U-1")
        val before = audits()
        val refused =
            inTransaction { store, _, audit ->
                ApproveOverride(versions, authority, decisions(ReleaseOutcome.Denied("X")), store, audit)
                    .execute("U-2", doc, 1, "PAY-011")
            }
        refused.shouldBeInstanceOf<OverrideResult.Refused>()
        audits() shouldBe before
    }

    @Test
    fun `a release in one step writes one requested and one released entry for the same user`() {
        request(ReleaseOutcome.Released, "U-1")
        audits("requested") shouldBe 1
        audits("released") shouldBe 1
        PayloadDb.superuser().use { c ->
            val sql = "select count(distinct data->>'actor') from payload.outbox where tenant_id = ?"
            c.prepareStatement(sql).use { p ->
                p.setObject(1, tenant)
                p.executeQuery().use { r -> r.next().let { r.getInt(1) } shouldBe 1 }
            }
        }
    }

    @Test
    fun `a failing writer leaves no override row`() {
        val failing =
            object : AuditWriter {
                override fun record(
                    entry: AuditEntry,
                    context: EventContext,
                ): Envelope = throw AuditWriteFailed(IllegalStateException("outbox down"))
            }
        shouldThrow<AuditWriteFailed> {
            PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { c ->
                c.autoCommit = false
                SignedBindings.bind(c, tenant)
                try {
                    val audit = OutboxOverrideAudit(failing, TenantFixture(tenant), versions)
                    RequestOverride(
                        versions,
                        authority,
                        findings,
                        decisions(ReleaseOutcome.Released),
                        JooqOverrideStore(DSL.using(c, SQLDialect.POSTGRES)),
                        audit,
                    ).execute("U-1", doc, 1, "PAY-011", "r")
                    c.commit()
                } catch (e: Exception) {
                    c.rollback()
                    throw e
                }
            }
        }
        inTransaction { store, _, _ -> store.find(doc, 1, "PAY-011") } shouldBe null
        audits() shouldBe 0
    }

    @Test
    fun `a version without stored facts is refused and writes nothing`() {
        val writer =
            object : AuditWriter {
                override fun record(
                    entry: AuditEntry,
                    context: EventContext,
                ): Envelope = error("must not be called")
            }
        val audit = OutboxOverrideAudit(writer, TenantFixture(tenant), versions)
        val row =
            StoredOverride(
                UUID.randomUUID(),
                1,
                "PAY-011",
                "r",
                com.dynokore.gst.payload.domain.override.OverrideStatus.PENDING,
                "U-1",
                java.time.Instant.now(),
                null,
                null,
            )
        shouldThrow<IllegalStateException> { audit.requested("U-1", row) }
    }
}
