package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.DRY_RUN_DELTA
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_REGISTRY
import com.dynokore.gst.rules.application.DryRunDeltaPort
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext

/**
 * The qualifying COMPLETE dry-run delta of a mapping pack version (INV-Q-14646 A). A delta qualifies when its state is
 * COMPLETE, its tenant, pack type and pack version equal the pack's by equality, and it started after the pack's last
 * content change; the latest by `completed_at`, then `id`, is the one an approval stores. No row, any other state,
 * another version, another tenant and a stale row all give null (fail closed). Read-only; INV-T-4974 writes the rows.
 */
class JooqDryRunDeltaPort(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : DryRunDeltaPort {
    override fun qualifying(
        packType: String,
        packVersion: String,
    ): String? {
        val tenant = tenants.current().value
        return dsl
            .select(DRY_RUN_DELTA.ID)
            .from(DRY_RUN_DELTA)
            .join(PACK_REGISTRY)
            .on(
                PACK_REGISTRY.TENANT_ID
                    .eq(DRY_RUN_DELTA.TENANT_ID)
                    .and(PACK_REGISTRY.PACK_TYPE.eq(DRY_RUN_DELTA.PACK_TYPE))
                    .and(PACK_REGISTRY.PACK_VERSION.eq(DRY_RUN_DELTA.PACK_VERSION)),
            ).where(DRY_RUN_DELTA.TENANT_ID.eq(tenant))
            .and(DRY_RUN_DELTA.PACK_TYPE.eq(packType))
            .and(DRY_RUN_DELTA.PACK_VERSION.eq(packVersion))
            .and(DRY_RUN_DELTA.STATE.eq(COMPLETE))
            .and(DRY_RUN_DELTA.STARTED_AT.gt(PACK_REGISTRY.CONTENT_CHANGED_AT))
            .orderBy(DRY_RUN_DELTA.COMPLETED_AT.desc(), DRY_RUN_DELTA.ID.desc())
            .limit(1)
            .fetchOne(DRY_RUN_DELTA.ID)
            ?.toString()
    }

    private companion object {
        const val COMPLETE = "COMPLETE"
    }
}
