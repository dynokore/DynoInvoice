package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.application.export.ExportChainIntegrity
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

/** INV-AC-23209: the context holds one export chain-integrity gate, the database adapter, and it fails closed. */
class ExportChainIntegrityWiringTest {
    private fun seed(sql: String) = EvidenceDb.superuser().use { c -> c.createStatement().use { it.execute(sql) } }

    private fun chain(
        tenant: UUID,
        result: String,
    ) {
        seed(
            "insert into evidence.applied_record (tenant_id, source_event_id, kind, entity_ref, period_ref, seq, " +
                "applied_at) values ('$tenant', '${UUID.randomUUID()}', 'evidence_payload', 'E1', '2026-Q1', 1, now())",
        )
        seed(
            "insert into evidence.integrity_run (tenant_id, entity_ref, period_ref, run_id, run_at, result, cause, " +
                "chain_length, period_digest) values ('$tenant', 'E1', '2026-Q1', '${UUID.randomUUID()}', now(), " +
                "'$result', 'VERIFICATION', 1, null)",
        )
    }

    @Test
    fun `INV-AC-23209 the context holds one ExportChainIntegrity, the database adapter`(
        @TempDir dir: Path,
    ) {
        EvidenceContext.online(dir).run { context ->
            val beans = context.getBeansOfType(ExportChainIntegrity::class.java)
            beans.size shouldBe 1
            val gate = beans.values.single()
            gate.javaClass.name shouldBe "com.dynokore.gst.evidence.adapters.persistence.JooqExportChainIntegrity"
            val scope = context.getBean(TenantScope::class.java)
            val runner = context.getBean(TransactionRunner::class.java)
            val failed = UUID.randomUUID()
            val verified = UUID.randomUUID()
            chain(failed, "FAILED")
            chain(verified, "VERIFIED")
            scope.bind(TenantId(failed), "svc:evidence-svc") {
                runner.inTransaction { gate.allowsExport(TenantId(failed)) }
            } shouldBe false
            scope.bind(TenantId(verified), "svc:evidence-svc") {
                runner.inTransaction { gate.allowsExport(TenantId(verified)) }
            } shouldBe true
        }
    }
}
