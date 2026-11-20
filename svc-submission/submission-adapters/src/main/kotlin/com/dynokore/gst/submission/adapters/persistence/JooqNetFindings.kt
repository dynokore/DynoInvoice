package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.NetCorrectionFinding
import com.dynokore.gst.submission.application.correction.NetFinding
import com.dynokore.gst.submission.application.correction.NetFindings
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext

/**
 * Stores the net findings of a tenant once per key (kind, credit note, version) in the caller's transaction
 * (INV-REQ-558 behavior 6). `recorded_by` is the acting service identity bound to the tenant context, never a role
 * (INV-Q-10647); with no identity bound the call fails and stores nothing (fail-safe default).
 */
class JooqNetFindings(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : NetFindings {
    private val f = NetCorrectionFinding.NET_CORRECTION_FINDING

    override fun record(finding: NetFinding): Boolean {
        val actor = checkNotNull(tenants.currentActor()) { "no acting service identity is bound" }
        return dsl
            .insertInto(f)
            .columns(
                f.TENANT_ID,
                f.KIND,
                f.CREDIT_NOTE_DOC_UUID,
                f.CREDIT_NOTE_VERSION,
                f.ORIGINAL_DOC_UUID,
                f.RECORDED_BY,
            ).values(
                tenants.current().value,
                finding.kind.name,
                finding.creditNoteDocUuid,
                finding.creditNoteVersion,
                finding.originalDocUuid,
                actor,
            ).onConflictDoNothing()
            .execute() == 1
    }
}
