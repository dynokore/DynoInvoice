package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqExportChainIntegrity
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.util.UUID

/** INV-AC-23209: the fail-closed export chain-integrity gate over the real database (DEP-007). */
class JooqExportChainIntegrityDbTest {
    private val digest = "sha256:" + "a".repeat(64)

    private fun seed(sql: String) =
        EvidenceDb.superuser().use { c -> c.createStatement().use { it.execute(sql) } }

    private fun applied(
        tenant: UUID,
        entity: String,
        period: String = "2026-Q1",
    ) = seed(
        "insert into evidence.applied_record (tenant_id, source_event_id, kind, entity_ref, period_ref, seq, applied_at) " +
            "values ('$tenant', '${UUID.randomUUID()}', 'evidence_payload', '$entity', '$period', 1, now())",
    )

    private fun digested(
        tenant: UUID,
        entity: String,
        period: String = "2026-Q1",
    ) = seed(
        "insert into evidence.period_digest (tenant_id, entity_ref, period_ref, digest_seq, return_version, filed_at, " +
            "recorded_at, source_event_id, digest) values ('$tenant', '$entity', '$period', 1, 1, now(), now(), " +
            "'${UUID.randomUUID()}', '$digest')",
    )

    private fun run(
        tenant: UUID,
        entity: String,
        result: String,
        runAt: String = "2026-11-01T00:00:00Z",
        period: String = "2026-Q1",
    ) = seed(
        "insert into evidence.integrity_run (tenant_id, entity_ref, period_ref, run_id, run_at, result, cause, " +
            "chain_length, period_digest) values ('$tenant', '$entity', '$period', '${UUID.randomUUID()}', " +
            "'$runAt', '$result', 'VERIFICATION', 1, null)",
    )

    private fun failure(tenant: UUID) =
        seed(
            "insert into evidence.integrity_failure (tenant_id, entity_ref, period_ref, evidence_type, kind, " +
                "failing_seq, range_from, range_to, run_at, run_id) values ('$tenant', 'E1', '2026-Q1', 'PAYLOAD', " +
                "'DIGEST_MISMATCH', null, now(), now(), now(), '${UUID.randomUUID()}')",
        )

    /** Asks the gate for [asked] on a runtime connection bound to [bound]. */
    private fun allows(
        bound: UUID,
        asked: UUID = bound,
        noIndex: Boolean = false,
    ): Boolean =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, bound)
            // without the index the tie of equal run_at is broken by the ORDER BY alone, not by the index order
            if (noIndex) c.createStatement().use { it.execute("set local enable_indexscan = off; set local enable_bitmapscan = off") }
            JooqExportChainIntegrity(DSL.using(c, SQLDialect.POSTGRES)).allowsExport(TenantId(asked)).also { c.commit() }
        }

    @Test
    fun `INV-AC-23209 every chain latest-VERIFIED and no failure row allows the export`() {
        val t1 = UUID.randomUUID()
        applied(t1, "E1")
        digested(t1, "E2")
        run(t1, "E1", "VERIFIED")
        run(t1, "E2", "VERIFIED")
        allows(t1) shouldBe true
    }

    @Test
    fun `INV-AC-23209 a chain whose latest run is FAILED refuses the export`() {
        val t1 = UUID.randomUUID()
        applied(t1, "E1")
        applied(t1, "E2")
        run(t1, "E1", "VERIFIED")
        run(t1, "E2", "FAILED")
        allows(t1) shouldBe false
    }

    @Test
    fun `INV-AC-23209 an older FAILED run under a newer VERIFIED run allows, equal run_at orders by seq`() {
        val t1 = UUID.randomUUID()
        applied(t1, "E1")
        run(t1, "E1", "FAILED", "2026-11-01T00:00:00Z")
        run(t1, "E1", "VERIFIED", "2026-11-02T00:00:00Z")
        allows(t1) shouldBe true

        val same = UUID.randomUUID()
        applied(same, "E1")
        run(same, "E1", "VERIFIED", "2026-11-02T00:00:00Z")
        run(same, "E1", "FAILED", "2026-11-02T00:00:00Z")
        allows(same) shouldBe false
        allows(same, noIndex = true) shouldBe false
    }

    @Test
    fun `INV-AC-23209 a chain in applied_record with no run refuses the export`() {
        val t1 = UUID.randomUUID()
        applied(t1, "E1")
        applied(t1, "E2")
        run(t1, "E1", "VERIFIED")
        allows(t1) shouldBe false
    }

    @Test
    fun `INV-AC-23209 a chain known only to period_digest with no run refuses the export`() {
        val t1 = UUID.randomUUID()
        applied(t1, "E1")
        run(t1, "E1", "VERIFIED")
        digested(t1, "E2")
        allows(t1) shouldBe false
    }

    @Test
    fun `INV-AC-23209 an open integrity_failure row refuses the export although every latest run is VERIFIED`() {
        val t1 = UUID.randomUUID()
        applied(t1, "E1")
        run(t1, "E1", "VERIFIED")
        failure(t1)
        allows(t1) shouldBe false
    }

    @Test
    fun `INV-AC-23209 a tenant with no chain and no failure row allows the export`() {
        allows(UUID.randomUUID()) shouldBe true
    }

    @Test
    fun `INV-AC-23209 another tenant than the bound one is refused and its rows never change the answer`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        allows(t1, t2) shouldBe false
        applied(t1, "E1")
        run(t1, "E1", "VERIFIED")
        applied(t2, "E1")
        run(t2, "E1", "FAILED")
        failure(t2)
        allows(t1) shouldBe true
        allows(t1, t2) shouldBe false
    }

    @Test
    fun `INV-AC-23209 a closed connection throws and returns no value`() {
        val t1 = UUID.randomUUID()
        val c = EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD)
        SignedBindings.bind(c, t1)
        val gate = JooqExportChainIntegrity(DSL.using(c, SQLDialect.POSTGRES))
        c.close()
        shouldThrow<DataAccessException> { gate.allowsExport(TenantId(t1)) }
    }
}
