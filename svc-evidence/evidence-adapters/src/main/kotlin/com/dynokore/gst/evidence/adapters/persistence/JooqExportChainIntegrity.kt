package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.AppliedRecord.Companion.APPLIED_RECORD
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.IntegrityFailure.Companion.INTEGRITY_FAILURE
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.IntegrityRun.Companion.INTEGRITY_RUN
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PeriodDigest.Companion.PERIOD_DIGEST
import com.dynokore.gst.evidence.application.export.ExportChainIntegrity
import com.dynokore.gst.evidence.domain.integrity.RunResult
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.util.UUID

/**
 * The production [ExportChainIntegrity] (DEP-007): a read-only gate over `integrity_run`, `integrity_failure`,
 * `applied_record` and `period_digest`, read in the caller's transaction. It fails closed (OWASP ASVS V1, NIST SP
 * 800-53 SI-7): the export is allowed only when the connection is bound to the asked tenant, the tenant has no
 * `integrity_failure` row, and every (entity, period) chain known to `applied_record` or `period_digest` has a newest
 * run (`run_at` descending, then `seq` descending, as [JooqIntegrityStore.latestRun]) whose result is VERIFIED. A chain
 * with no run is refused. A tenant with no chain at all is allowed. A database exception propagates.
 */
class JooqExportChainIntegrity(
    private val dsl: DSLContext,
) : ExportChainIntegrity {
    override fun allowsExport(tenant: TenantId): Boolean =
        boundTo(tenant) && !hasOpenFailure(tenant) && !hasUnverifiedChain(tenant)

    /** Forced row-level security would show another tenant zero rows, and an empty answer must never read as clean. */
    private fun boundTo(tenant: TenantId): Boolean {
        val bound =
            dsl
                .select(DSL.function(DSL.name("evidence", "bound_tenant"), SQLDataType.UUID))
                .fetchOne(0, UUID::class.java)
        return bound == tenant.value
    }

    private fun hasOpenFailure(tenant: TenantId): Boolean =
        dsl.fetchExists(INTEGRITY_FAILURE, INTEGRITY_FAILURE.TENANT_ID.eq(tenant.value))

    /** A chain of either table whose newest run is missing or not VERIFIED (a missing run reads as NEVER_RUN). */
    private fun hasUnverifiedChain(tenant: TenantId): Boolean =
        unverified(
            tenant,
            APPLIED_RECORD,
            APPLIED_RECORD.TENANT_ID,
            APPLIED_RECORD.ENTITY_REF,
            APPLIED_RECORD.PERIOD_REF,
        ) ||
            unverified(
                tenant,
                PERIOD_DIGEST,
                PERIOD_DIGEST.TENANT_ID,
                PERIOD_DIGEST.ENTITY_REF,
                PERIOD_DIGEST.PERIOD_REF,
            )

    private fun unverified(
        tenant: TenantId,
        table: Table<*>,
        tenantId: Field<UUID?>,
        entity: Field<String?>,
        period: Field<String?>,
    ): Boolean {
        val newest =
            DSL
                .select(INTEGRITY_RUN.RESULT)
                .from(INTEGRITY_RUN)
                .where(
                    INTEGRITY_RUN.TENANT_ID.eq(tenant.value),
                    INTEGRITY_RUN.ENTITY_REF.eq(entity),
                    INTEGRITY_RUN.PERIOD_REF.eq(period),
                ).orderBy(INTEGRITY_RUN.RUN_AT.desc(), INTEGRITY_RUN.SEQ.desc())
                .limit(1)
        return dsl.fetchExists(
            table,
            tenantId.eq(tenant.value),
            DSL.coalesce(DSL.field(newest), "NEVER_RUN").ne(RunResult.VERIFIED.name),
        )
    }
}
