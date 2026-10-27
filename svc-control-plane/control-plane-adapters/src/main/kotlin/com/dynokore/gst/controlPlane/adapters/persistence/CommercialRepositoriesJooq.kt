package com.dynokore.gst.controlPlane.adapters.persistence

import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.PLAN_VERSION
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.QUALIFICATION
import com.dynokore.gst.controlPlane.application.commercial.PlanRepository
import com.dynokore.gst.controlPlane.application.commercial.QualificationRepository
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.Entitlements
import com.dynokore.gst.controlPlane.domain.commercial.Plan
import com.dynokore.gst.controlPlane.domain.commercial.PlanVersion
import com.dynokore.gst.controlPlane.domain.commercial.Qualification
import com.dynokore.gst.controlPlane.domain.commercial.QualificationOutcome
import org.jooq.DSLContext
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Plan versions in the `controlplane` schema through jOOQ (TPL-11), in the open transaction of the command. */
class PlanRepositoryJooq(
    private val dsl: DSLContext,
    private val clock: Clock,
) : PlanRepository {
    override fun find(
        tenantRef: CommercialRef,
        version: PlanVersion,
    ): Plan? =
        dsl
            .selectFrom(PLAN_VERSION)
            .where(PLAN_VERSION.TENANT_REF.eq(tenantRef.value).and(PLAN_VERSION.PLAN_VERSION_.eq(version.value)))
            .fetchOne()
            ?.let {
                Plan(
                    tenantRef,
                    version,
                    Entitlements(checkNotNull(it.maxEntities), checkNotNull(it.maxDocumentsPerMonth)),
                )
            }

    override fun save(
        plan: Plan,
        recordedBy: String,
    ) {
        dsl
            .insertInto(PLAN_VERSION)
            .set(PLAN_VERSION.TENANT_REF, plan.tenantRef.value)
            .set(PLAN_VERSION.PLAN_VERSION_, plan.version.value)
            .set(PLAN_VERSION.MAX_ENTITIES, plan.entitlements.maxEntities)
            .set(PLAN_VERSION.MAX_DOCUMENTS_PER_MONTH, plan.entitlements.maxDocumentsPerMonth)
            .set(PLAN_VERSION.RECORDED_BY, recordedBy)
            .set(PLAN_VERSION.RECORDED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .execute()
    }
}

/** Qualification outcomes in the `controlplane` schema through jOOQ (TPL-11), in the command transaction. */
class QualificationRepositoryJooq(
    private val dsl: DSLContext,
    private val clock: Clock,
) : QualificationRepository {
    override fun find(
        customerRef: CommercialRef,
        installationRef: CommercialRef,
    ): Qualification? =
        dsl
            .selectFrom(QUALIFICATION)
            .where(
                QUALIFICATION.CUSTOMER_REF
                    .eq(customerRef.value)
                    .and(QUALIFICATION.INSTALLATION_REF.eq(installationRef.value)),
            ).orderBy(QUALIFICATION.SEQ.desc())
            .limit(1)
            .fetchOne()
            ?.let { qualificationOf(it.customerRef, it.installationRef, it.outcome) }

    override fun latestFor(installationRef: CommercialRef): Qualification? =
        dsl
            .selectFrom(QUALIFICATION)
            .where(QUALIFICATION.INSTALLATION_REF.eq(installationRef.value))
            .orderBy(QUALIFICATION.SEQ.desc())
            .limit(1)
            .fetchOne()
            ?.let { qualificationOf(it.customerRef, it.installationRef, it.outcome) }

    override fun save(
        qualification: Qualification,
        recordedBy: String,
    ) {
        dsl
            .insertInto(QUALIFICATION)
            .set(QUALIFICATION.CUSTOMER_REF, qualification.customerRef.value)
            .set(QUALIFICATION.INSTALLATION_REF, qualification.installationRef.value)
            .set(QUALIFICATION.OUTCOME, qualification.outcome.name)
            .set(QUALIFICATION.RECORDED_BY, recordedBy)
            .set(QUALIFICATION.RECORDED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .execute()
    }

    private fun qualificationOf(
        customerRef: String?,
        installationRef: String?,
        outcome: String?,
    ) = Qualification(
        CommercialRef(checkNotNull(customerRef)),
        CommercialRef(checkNotNull(installationRef)),
        QualificationOutcome.valueOf(checkNotNull(outcome)),
    )
}
