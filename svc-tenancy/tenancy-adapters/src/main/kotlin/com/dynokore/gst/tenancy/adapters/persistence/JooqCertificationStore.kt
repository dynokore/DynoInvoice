package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.CERTIFICATION_STATUS
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.SANDBOX_CERTIFICATION
import com.dynokore.gst.tenancy.application.certification.CertifiableEntities
import com.dynokore.gst.tenancy.application.certification.CertificationRecord
import com.dynokore.gst.tenancy.application.certification.CertificationStore
import com.dynokore.gst.tenancy.application.certification.CertifiedEntity
import com.dynokore.gst.tenancy.application.certification.CertifiedEntityDirectory
import com.dynokore.gst.tenancy.domain.CertificationOutcome
import com.dynokore.gst.tenancy.domain.CertificationStatus
import com.dynokore.gst.tenancy.domain.StaleTrigger
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The certification records and the stale state of the bound tenant's entities (jOOQ only, CS-18). Forced row-level
 * security shows only the bound tenant's rows; [clock] stamps the status change.
 */
class JooqCertificationStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: () -> Instant,
) : CertificationStore,
    CertifiableEntities {
    override fun exists(entityRef: String): Boolean = entityId(entityRef) != null

    override fun append(record: CertificationRecord) {
        dsl
            .insertInto(SANDBOX_CERTIFICATION)
            .set(SANDBOX_CERTIFICATION.ID, UUID.randomUUID())
            .set(SANDBOX_CERTIFICATION.TENANT_ID, tenants.current().value)
            .set(SANDBOX_CERTIFICATION.ENTITY_ID, checkNotNull(entityId(record.entityRef)) { "no such entity" })
            .set(SANDBOX_CERTIFICATION.OUTCOME, record.outcome.name)
            .set(SANDBOX_CERTIFICATION.SUITE_VERSION, record.suiteVersion)
            .set(SANDBOX_CERTIFICATION.FAILED_SCENARIOS, record.failedScenarios?.toTypedArray())
            .set(SANDBOX_CERTIFICATION.RECORDED_BY, record.recordedBy)
            .set(SANDBOX_CERTIFICATION.RECORDED_AT, OffsetDateTime.ofInstant(record.recordedAt, ZoneOffset.UTC))
            .execute()
    }

    override fun latest(entityRef: String): CertificationRecord? =
        dsl
            .select(
                SANDBOX_CERTIFICATION.OUTCOME,
                SANDBOX_CERTIFICATION.SUITE_VERSION,
                SANDBOX_CERTIFICATION.FAILED_SCENARIOS,
                SANDBOX_CERTIFICATION.RECORDED_BY,
                SANDBOX_CERTIFICATION.RECORDED_AT,
            ).from(SANDBOX_CERTIFICATION)
            .join(ENTITY)
            .on(
                ENTITY.TENANT_ID
                    .eq(SANDBOX_CERTIFICATION.TENANT_ID)
                    .and(ENTITY.ID.eq(SANDBOX_CERTIFICATION.ENTITY_ID)),
            ).where(ENTITY.REF.eq(entityRef))
            .orderBy(SANDBOX_CERTIFICATION.RECORDED_AT.desc(), SANDBOX_CERTIFICATION.ID.desc())
            .limit(1)
            .fetchOne()
            ?.let {
                CertificationRecord(
                    entityRef,
                    CertificationOutcome.valueOf(it[SANDBOX_CERTIFICATION.OUTCOME].column("outcome")),
                    it[SANDBOX_CERTIFICATION.SUITE_VERSION].column("suite_version"),
                    it[SANDBOX_CERTIFICATION.FAILED_SCENARIOS]?.filterNotNull(),
                    it[SANDBOX_CERTIFICATION.RECORDED_BY].column("recorded_by"),
                    it[SANDBOX_CERTIFICATION.RECORDED_AT].column("recorded_at").toInstant(),
                )
            }

    override fun hasPassed(entityRef: String): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(SANDBOX_CERTIFICATION)
                .join(ENTITY)
                .on(
                    ENTITY.TENANT_ID
                        .eq(SANDBOX_CERTIFICATION.TENANT_ID)
                        .and(ENTITY.ID.eq(SANDBOX_CERTIFICATION.ENTITY_ID)),
                ).where(ENTITY.REF.eq(entityRef).and(SANDBOX_CERTIFICATION.OUTCOME.eq(CertificationOutcome.PASSED.name))),
        )

    override fun status(entityRef: String): CertificationStatus? {
        val id = entityId(entityRef) ?: return null
        return dsl
            .select(CERTIFICATION_STATUS.STATUS)
            .from(CERTIFICATION_STATUS)
            .where(CERTIFICATION_STATUS.ENTITY_ID.eq(id))
            .fetchOne()
            ?.let { CertificationStatus.valueOf(it[CERTIFICATION_STATUS.STATUS].column("status")) }
    }

    override fun setStatus(
        entityRef: String,
        status: CertificationStatus,
        trigger: StaleTrigger?,
    ) {
        val at = OffsetDateTime.ofInstant(clock(), ZoneOffset.UTC)
        dsl
            .insertInto(CERTIFICATION_STATUS)
            .set(CERTIFICATION_STATUS.TENANT_ID, tenants.current().value)
            .set(CERTIFICATION_STATUS.ENTITY_ID, checkNotNull(entityId(entityRef)) { "no such entity" })
            .set(CERTIFICATION_STATUS.STATUS, status.name)
            .set(CERTIFICATION_STATUS.STALE_TRIGGER, trigger?.name)
            .set(CERTIFICATION_STATUS.CHANGED_AT, at)
            .onConflict(CERTIFICATION_STATUS.TENANT_ID, CERTIFICATION_STATUS.ENTITY_ID)
            .doUpdate()
            .set(CERTIFICATION_STATUS.STATUS, status.name)
            .set(CERTIFICATION_STATUS.STALE_TRIGGER, trigger?.name)
            .set(CERTIFICATION_STATUS.CHANGED_AT, at)
            .execute()
    }

    private fun entityId(entityRef: String): UUID? =
        dsl.select(ENTITY.ID).from(ENTITY).where(ENTITY.REF.eq(entityRef)).fetchOne()?.get(ENTITY.ID)
}

/**
 * Every entity with a certification status row (it has a PASSED record) in every tenant. The tenant ids come from the
 * definer function `tenancy.list_tenant_ids()` (INV-Q-11717 A); each tenant's entities are read bound to that tenant
 * (forced RLS), so no read names a tenant it is not bound to.
 */
class JooqCertifiedEntityDirectory(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val platform: PlatformTenantBinding,
    private val tx: TransactionRunner,
    private val actor: String,
) : CertifiedEntityDirectory {
    override fun certifiedEntities(): List<CertifiedEntity> =
        tx.outsideOpenTransaction { tenantIds() }.flatMap { tenant ->
            tenants.bind(tenant, actor) {
                tx.inTransaction {
                    dsl
                        .select(ENTITY.REF)
                        .from(CERTIFICATION_STATUS)
                        .join(ENTITY)
                        .on(
                            ENTITY.TENANT_ID
                                .eq(CERTIFICATION_STATUS.TENANT_ID)
                                .and(ENTITY.ID.eq(CERTIFICATION_STATUS.ENTITY_ID)),
                        ).orderBy(ENTITY.REF)
                        .fetch()
                        .map { CertifiedEntity(tenant, it[ENTITY.REF].column("ref")) }
                }
            }
        }

    private fun tenantIds(): List<TenantId> =
        platform.bindPlatform(actor) {
            tx.inTransaction {
                dsl
                    .select(DSL.function(DSL.name("tenancy", "list_tenant_ids"), SQLDataType.UUID))
                    .fetch()
                    .map { TenantId(it.value1().column("tenant_id")) }
            }
        }
}
