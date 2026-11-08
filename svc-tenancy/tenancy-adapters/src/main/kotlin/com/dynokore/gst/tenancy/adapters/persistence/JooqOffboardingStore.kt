package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.records.OffboardingRecordRecord
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.OFFBOARDING_RECORD
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.gst.tenancy.application.offboarding.OffboardingRecord
import com.dynokore.gst.tenancy.application.offboarding.OffboardingStore
import com.dynokore.gst.tenancy.domain.ExportContent
import com.dynokore.gst.tenancy.domain.ExportManifest
import com.dynokore.gst.tenancy.domain.ExportState
import com.dynokore.gst.tenancy.domain.OffboardingRules
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.gst.tenancy.domain.OffboardingState
import com.dynokore.gst.tenancy.domain.RetentionCustodyHolder
import com.dynokore.gst.tenancy.domain.UnsubmittedOverride
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.util.UUID

/**
 * The offboarding records of the bound tenant (jOOQ only, CS-18); forced RLS hides every other tenant's rows. A record
 * is inserted once and never updated; a FAILED record stays as history (INV-Q-5025).
 */
class JooqOffboardingStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : OffboardingStore {
    override fun byIdempotencyKey(key: String): OffboardingRecord? =
        dsl
            .selectFrom(OFFBOARDING_RECORD)
            .where(
                OFFBOARDING_RECORD.TENANT_ID
                    .eq(tenants.current().value)
                    .and(OFFBOARDING_RECORD.IDEMPOTENCY_KEY.eq(key)),
            ).fetchOne()
            ?.let(::recordOf)

    override fun blocking(
        scope: OffboardingScope,
        entityRef: String?,
    ): OffboardingRecord? =
        dsl
            .selectFrom(OFFBOARDING_RECORD)
            .where(
                OFFBOARDING_RECORD.TENANT_ID
                    .eq(tenants.current().value)
                    .and(OFFBOARDING_RECORD.SCOPE.eq(scope.name))
                    .and(
                        if (entityRef == null) {
                            OFFBOARDING_RECORD.ENTITY_REF.isNull
                        } else {
                            OFFBOARDING_RECORD.ENTITY_REF.eq(entityRef)
                        },
                    ),
            ).fetch()
            .map(::recordOf)
            .firstOrNull { OffboardingRules.blocksNewRequest(it.state) }

    override fun nextOffboardingRef(): String =
        "OFB-" +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(REF_LENGTH)

    override fun insert(record: OffboardingRecord) {
        dsl
            .insertInto(OFFBOARDING_RECORD)
            .set(OFFBOARDING_RECORD.TENANT_ID, tenants.current().value)
            .set(OFFBOARDING_RECORD.OFFBOARDING_REF, record.offboardingRef)
            .set(OFFBOARDING_RECORD.IDEMPOTENCY_KEY, record.idempotencyKey)
            .set(OFFBOARDING_RECORD.SCOPE, record.scope.name)
            .set(OFFBOARDING_RECORD.ENTITY_REF, record.entityRef)
            .set(OFFBOARDING_RECORD.STATE, record.state.name)
            .set(OFFBOARDING_RECORD.CUSTODY_HOLDER_NAME, record.retentionCustodyHolder.name)
            .set(OFFBOARDING_RECORD.CUSTODY_HOLDER_EMAIL, record.retentionCustodyHolder.contactEmail)
            .set(OFFBOARDING_RECORD.CUSTODY_HOLDER_MOBILE, record.retentionCustodyHolder.contactMobile)
            .set(OFFBOARDING_RECORD.EXPORT_REF, record.export.exportRef)
            .set(OFFBOARDING_RECORD.EXPORT_STATE, record.export.state.name)
            .set(
                OFFBOARDING_RECORD.EXPORT_CONTENTS,
                record.export.contents
                    .map { it.name }
                    .toTypedArray(),
            ).set(OFFBOARDING_RECORD.OVERRIDE_ACKNOWLEDGED_BY, record.unsubmittedOverride?.acknowledgedBy)
            .set(OFFBOARDING_RECORD.OVERRIDE_STATEMENT, record.unsubmittedOverride?.statement)
            .set(OFFBOARDING_RECORD.NOTICE_WINDOW_DAYS, record.noticeWindowDays)
            .set(OFFBOARDING_RECORD.HANDOVER_STATEMENT_REF, record.handoverStatementRef)
            .set(OFFBOARDING_RECORD.REQUESTED_BY, record.requestedBy)
            .set(OFFBOARDING_RECORD.SECOND_CONFIRMATION_BY, record.secondConfirmationBy)
            .execute()
    }

    /** The `ref` of the bound tenant, read from the tenant row (no second mapping of a ref to an id). */
    private fun tenantRef(): String =
        dsl
            .select(TENANT.REF)
            .from(TENANT)
            .where(TENANT.TENANT_ID.eq(tenants.current().value))
            .fetchOne()
            ?.get(TENANT.REF)
            .column("tenant.ref")

    private fun recordOf(row: OffboardingRecordRecord): OffboardingRecord {
        val acknowledgedBy = row.overrideAcknowledgedBy
        val statement = row.overrideStatement
        return OffboardingRecord(
            offboardingRef = row.offboardingRef.column("offboarding_ref"),
            tenantRef = tenantRef(),
            scope = OffboardingScope.valueOf(row.scope.column("scope")),
            entityRef = row.entityRef,
            state = OffboardingState.valueOf(row.state.column("state")),
            retentionCustodyHolder =
                RetentionCustodyHolder(
                    row.custodyHolderName.column("custody_holder_name"),
                    row.custodyHolderEmail.column("custody_holder_email"),
                    row.custodyHolderMobile,
                ),
            export =
                ExportManifest(
                    row.exportRef.column("export_ref"),
                    ExportState.valueOf(row.exportState.column("export_state")),
                    row.exportContents.column("export_contents").map { ExportContent.valueOf(it.column("member")) },
                ),
            unsubmittedOverride =
                if (acknowledgedBy != null &&
                    statement != null
                ) {
                    UnsubmittedOverride(acknowledgedBy, statement)
                } else {
                    null
                },
            noticeWindowDays = row.noticeWindowDays,
            handoverStatementRef = row.handoverStatementRef,
            idempotencyKey = row.idempotencyKey.column("idempotency_key"),
            requestedBy = row.requestedBy,
            secondConfirmationBy = row.secondConfirmationBy,
        )
    }

    private companion object {
        const val REF_LENGTH = 12
    }
}
