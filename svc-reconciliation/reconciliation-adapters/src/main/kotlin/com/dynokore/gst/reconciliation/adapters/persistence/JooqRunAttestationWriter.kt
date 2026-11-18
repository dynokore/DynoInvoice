package com.dynokore.gst.reconciliation.adapters.persistence

import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.WORK_PAPER_ATTESTATION
import com.dynokore.gst.reconciliation.application.workpaper.RunAttestationWriter
import com.dynokore.gst.reconciliation.application.workpaper.StoredAttestation
import org.jooq.DSLContext
import java.time.ZoneOffset
import java.util.UUID

/**
 * The write side of the append-only attestation store (INV-T-24435, INV-AC-24434, INV-REQ-947, INV-REQ-994): one row per
 * run, never updated or deleted (NIST SP 800-53 AU-9). A repeat for the same run stores nothing and answers false; the
 * stored fact is never rewritten. The caller binds the tenant and opens the transaction; the statement names the
 * tenant in its values as well as the bound tenant of the row-level security. The acting user ID is stored, never a
 * role (INV-Q-10647).
 */
class JooqRunAttestationWriter(
    private val dsl: DSLContext,
) : RunAttestationWriter {
    override fun insert(
        tenant: String,
        runId: String,
        stored: StoredAttestation,
    ): Boolean =
        dsl
            .insertInto(WORK_PAPER_ATTESTATION)
            .set(WORK_PAPER_ATTESTATION.TENANT_ID, UUID.fromString(tenant))
            .set(WORK_PAPER_ATTESTATION.RUN_ID, runId)
            .set(WORK_PAPER_ATTESTATION.ATTESTED_BY, stored.attestedBy)
            .set(WORK_PAPER_ATTESTATION.ATTESTED_AT, stored.attestedAt.atOffset(ZoneOffset.UTC))
            .set(WORK_PAPER_ATTESTATION.SOLE_ATTESTER_ACKNOWLEDGED_BY, stored.soleAttester?.acknowledgedBy)
            .set(WORK_PAPER_ATTESTATION.SOLE_ATTESTER_ACKNOWLEDGED_ON, stored.soleAttester?.acknowledgedOn)
            .onConflict(WORK_PAPER_ATTESTATION.TENANT_ID, WORK_PAPER_ATTESTATION.RUN_ID)
            .doNothing()
            .execute() == 1
}
