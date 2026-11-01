package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.SAMPLE_COPY_APPROVAL
import com.dynokore.gst.tenancy.application.workspace.SampleApprovals
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The sample-copy approvals of the bound tenant (jOOQ only, CS-18); forced RLS hides every other tenant's rows. */
class JooqSampleApprovals(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : SampleApprovals {
    override fun record(
        sampleRef: String,
        approvedBy: String,
    ) {
        dsl
            .insertInto(SAMPLE_COPY_APPROVAL)
            .set(SAMPLE_COPY_APPROVAL.ID, UUID.randomUUID())
            .set(SAMPLE_COPY_APPROVAL.TENANT_ID, tenants.current().value)
            .set(SAMPLE_COPY_APPROVAL.SAMPLE_REF, sampleRef)
            .set(SAMPLE_COPY_APPROVAL.APPROVED_BY, approvedBy)
            .set(SAMPLE_COPY_APPROVAL.APPROVED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    override fun open(sampleRef: String): String? =
        dsl
            .select(SAMPLE_COPY_APPROVAL.APPROVED_BY)
            .from(SAMPLE_COPY_APPROVAL)
            .where(SAMPLE_COPY_APPROVAL.SAMPLE_REF.eq(sampleRef), SAMPLE_COPY_APPROVAL.CONSUMED_AT.isNull)
            .orderBy(SAMPLE_COPY_APPROVAL.APPROVED_AT)
            .limit(1)
            .fetchOne(SAMPLE_COPY_APPROVAL.APPROVED_BY)

    override fun spend(sampleRef: String): Boolean {
        val open =
            dsl
                .select(SAMPLE_COPY_APPROVAL.ID)
                .from(SAMPLE_COPY_APPROVAL)
                .where(SAMPLE_COPY_APPROVAL.SAMPLE_REF.eq(sampleRef), SAMPLE_COPY_APPROVAL.CONSUMED_AT.isNull)
                .orderBy(SAMPLE_COPY_APPROVAL.APPROVED_AT)
                .limit(1)
        return dsl
            .update(SAMPLE_COPY_APPROVAL)
            .set(SAMPLE_COPY_APPROVAL.CONSUMED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(SAMPLE_COPY_APPROVAL.ID.eq(open.asField()), SAMPLE_COPY_APPROVAL.CONSUMED_AT.isNull)
            .execute() == 1
    }
}
