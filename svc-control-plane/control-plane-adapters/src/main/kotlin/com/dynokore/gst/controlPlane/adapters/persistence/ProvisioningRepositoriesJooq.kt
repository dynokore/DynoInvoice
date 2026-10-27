package com.dynokore.gst.controlPlane.adapters.persistence

import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.LICENCE
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.REGION
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.REGION_STATEMENT
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.TENANT_PROVISIONING_REQUEST
import com.dynokore.gst.controlPlane.application.commercial.LicenceRepository
import com.dynokore.gst.controlPlane.application.commercial.ProvisioningRecord
import com.dynokore.gst.controlPlane.application.commercial.ProvisioningRepository
import com.dynokore.gst.controlPlane.application.commercial.RegionList
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.Licence
import com.dynokore.gst.controlPlane.domain.commercial.PlanRef
import com.dynokore.gst.controlPlane.domain.commercial.ProvisioningStatus
import com.dynokore.gst.controlPlane.domain.commercial.RegionKey
import com.dynokore.gst.controlPlane.domain.commercial.TenantProvisioning
import org.jooq.DSLContext
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The region list and its statements in the `controlplane` schema through jOOQ (TPL-11); read only. */
class RegionListJooq(
    private val dsl: DSLContext,
) : RegionList {
    override fun contains(key: RegionKey): Boolean = dsl.fetchExists(REGION, REGION.REGION_KEY.eq(key.value))

    override fun statement(key: RegionKey): String? =
        dsl
            .select(REGION_STATEMENT.STATEMENT)
            .from(REGION_STATEMENT)
            .where(REGION_STATEMENT.REGION_KEY.eq(key.value))
            .fetchOne(REGION_STATEMENT.STATEMENT)
}

/** Provisioning requests through jOOQ (TPL-11), in the open transaction of the command. */
class ProvisioningRepositoryJooq(
    private val dsl: DSLContext,
    private val clock: Clock,
) : ProvisioningRepository {
    override fun findByContract(contractReference: CommercialRef): ProvisioningRecord? =
        dsl
            .selectFrom(TENANT_PROVISIONING_REQUEST)
            .where(TENANT_PROVISIONING_REQUEST.CONTRACT_REFERENCE.eq(contractReference.value))
            .fetchOne()
            ?.let(::recordOf)

    override fun findByKey(
        idempotencyKey: UUID,
        recordedBy: String,
    ): ProvisioningRecord? =
        dsl
            .selectFrom(TENANT_PROVISIONING_REQUEST)
            .where(
                TENANT_PROVISIONING_REQUEST.IDEMPOTENCY_KEY
                    .eq(idempotencyKey)
                    .and(TENANT_PROVISIONING_REQUEST.RECORDED_BY.eq(recordedBy)),
            ).fetchOne()
            ?.let(::recordOf)

    override fun save(
        record: ProvisioningRecord,
        recordedBy: String,
    ) {
        val request = record.provisioning
        dsl
            .insertInto(TENANT_PROVISIONING_REQUEST)
            .set(TENANT_PROVISIONING_REQUEST.CONTRACT_REFERENCE, request.contractReference.value)
            .set(TENANT_PROVISIONING_REQUEST.TENANT_REF, request.tenantRef.value)
            .set(TENANT_PROVISIONING_REQUEST.LEGAL_NAME, request.legalName)
            .set(TENANT_PROVISIONING_REQUEST.PLAN_REF, request.planRef.value)
            .set(TENANT_PROVISIONING_REQUEST.FIRST_ADMINISTRATOR_EMAIL, request.firstAdministratorEmail)
            .set(TENANT_PROVISIONING_REQUEST.DATA_REGION, request.dataRegion.value)
            .set(TENANT_PROVISIONING_REQUEST.STATUS, request.status.name)
            .set(TENANT_PROVISIONING_REQUEST.INVITATION_REF, request.invitationRef)
            .set(
                TENANT_PROVISIONING_REQUEST.INVITATION_EXPIRES_AT,
                OffsetDateTime.ofInstant(request.invitationExpiresAt, ZoneOffset.UTC),
            ).set(TENANT_PROVISIONING_REQUEST.IDEMPOTENCY_KEY, record.idempotencyKey)
            .set(TENANT_PROVISIONING_REQUEST.FINGERPRINT, record.fingerprint)
            .set(TENANT_PROVISIONING_REQUEST.RECORDED_BY, recordedBy)
            .set(TENANT_PROVISIONING_REQUEST.RECORDED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .execute()
    }

    override fun markCreated(contractReference: CommercialRef) {
        dsl
            .update(TENANT_PROVISIONING_REQUEST)
            .set(TENANT_PROVISIONING_REQUEST.STATUS, ProvisioningStatus.CREATED.name)
            .where(TENANT_PROVISIONING_REQUEST.CONTRACT_REFERENCE.eq(contractReference.value))
            .execute()
    }

    private fun recordOf(row: org.jooq.Record): ProvisioningRecord {
        val r = row.into(TENANT_PROVISIONING_REQUEST)
        return ProvisioningRecord(
            TenantProvisioning(
                CommercialRef(checkNotNull(r.contractReference)),
                CommercialRef(checkNotNull(r.tenantRef)),
                checkNotNull(r.legalName),
                PlanRef(checkNotNull(r.planRef)),
                checkNotNull(r.firstAdministratorEmail),
                RegionKey(checkNotNull(r.dataRegion)),
                ProvisioningStatus.valueOf(checkNotNull(r.status)),
                checkNotNull(r.invitationRef),
                checkNotNull(r.invitationExpiresAt).toInstant(),
            ),
            checkNotNull(r.idempotencyKey),
            checkNotNull(r.fingerprint),
        )
    }
}

/** Licences through jOOQ (TPL-11), in the open transaction of the command. */
class LicenceRepositoryJooq(
    private val dsl: DSLContext,
) : LicenceRepository {
    override fun findByRef(licenceRef: CommercialRef): Licence? =
        dsl
            .selectFrom(LICENCE)
            .where(LICENCE.LICENCE_REF.eq(licenceRef.value))
            .fetchOne()
            ?.let(::licenceOf)

    override fun findByTerm(
        installationRef: CommercialRef,
        term: String,
    ): Licence? =
        dsl
            .selectFrom(LICENCE)
            .where(LICENCE.INSTALLATION_REF.eq(installationRef.value).and(LICENCE.TERM.eq(term)))
            .fetchOne()
            ?.let(::licenceOf)

    override fun save(
        licence: Licence,
        issuedBy: String,
    ) {
        dsl
            .insertInto(LICENCE)
            .set(LICENCE.LICENCE_REF, licence.licenceRef.value)
            .set(LICENCE.INSTALLATION_REF, licence.installationRef.value)
            .set(LICENCE.TERM, licence.term)
            .set(LICENCE.ISSUED_AT, OffsetDateTime.ofInstant(licence.issuedAt, ZoneOffset.UTC))
            .set(LICENCE.SIGNED_FILE, licence.signedFile)
            .set(LICENCE.ISSUED_BY, issuedBy)
            .execute()
    }

    private fun licenceOf(row: org.jooq.Record): Licence {
        val r = row.into(LICENCE)
        return Licence(
            CommercialRef(checkNotNull(r.licenceRef)),
            CommercialRef(checkNotNull(r.installationRef)),
            checkNotNull(r.term),
            checkNotNull(r.issuedAt).toInstant(),
            checkNotNull(r.signedFile),
        )
    }
}
