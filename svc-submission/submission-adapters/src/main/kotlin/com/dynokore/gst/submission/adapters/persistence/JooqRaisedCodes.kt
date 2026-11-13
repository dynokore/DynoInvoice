package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.application.arrival.RaisedCode
import com.dynokore.gst.submission.application.arrival.RaisedCodes
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.Instant
import java.time.ZoneOffset
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.RaisedCode as RaisedCodeTable

/**
 * [RaisedCodes] over the append-only `submission.raised_code` (INV-AC-21088), in the open tenant transaction. A repeat
 * of (tenant, doc, version, code) inserts nothing. `raised_at` is the instant given by the caller, never SQL `now()`;
 * the tenant is in the statement as well as in row level security.
 */
class JooqRaisedCodes(
    private val dsl: DSLContext,
) : RaisedCodes {
    override fun append(
        tenant: TenantId,
        raised: RaisedCode,
        raisedAt: Instant,
    ): Boolean {
        val r = RaisedCodeTable.RAISED_CODE
        return dsl
            .insertInto(r)
            .set(r.TENANT_ID, tenant.value)
            .set(r.DOC_UUID, raised.docUuid)
            .set(r.VERSION, raised.version)
            .set(r.ENTITY_REF, raised.entityRef)
            .set(r.PERIOD_REF, raised.periodRef)
            .set(r.CODE, raised.code)
            .set(r.RAISED_AT, raisedAt.atOffset(ZoneOffset.UTC))
            .onConflict(r.TENANT_ID, r.DOC_UUID, r.VERSION, r.CODE)
            .doNothing()
            .execute() == 1
    }
}
