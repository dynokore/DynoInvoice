package com.dynokore.gst.reconciliation.adapters.persistence

import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.WORK_PAPER_ATTESTATION
import com.dynokore.gst.reconciliation.application.workpaper.RunAttestations
import com.dynokore.gst.reconciliation.application.workpaper.StoredAttestation
import org.jooq.DSLContext
import java.util.UUID

/**
 * The read side of the append-only attestation store (INV-T-24380, INV-AC-24379): who attested the run and when. The
 * insert belongs to the attestation adapters task, which extends this read for the sole-attester columns. The caller
 * binds the tenant; the statement names the tenant as well as the bound tenant of the row-level security.
 */
class JooqRunAttestations(
    private val dsl: DSLContext,
) : RunAttestations {
    override fun get(
        tenant: String,
        runId: String,
    ): StoredAttestation? =
        dsl
            .select(WORK_PAPER_ATTESTATION.ATTESTED_BY, WORK_PAPER_ATTESTATION.ATTESTED_AT)
            .from(WORK_PAPER_ATTESTATION)
            .where(WORK_PAPER_ATTESTATION.TENANT_ID.eq(UUID.fromString(tenant)))
            .and(WORK_PAPER_ATTESTATION.RUN_ID.eq(runId))
            .fetchOne()
            ?.let { StoredAttestation(checkNotNull(it.value1()), checkNotNull(it.value2()).toInstant()) }
}
