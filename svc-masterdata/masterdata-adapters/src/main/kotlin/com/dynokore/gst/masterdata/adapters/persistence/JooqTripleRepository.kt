package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.IDENTITY_TRIPLE
import com.dynokore.gst.masterdata.application.identity.TripleRepository
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.IdentityTriple
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.gst.masterdata.domain.identity.TripleVersion
import com.dynokore.platform.outboxandqueue.UuidV7
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset

/**
 * TPL-11. The DSLContext is service-runtime's, wired with the tenancy-jooq listeners, which add `tenant_id = <bound
 * tenant>` to every statement below or refuse it; forced row-level security stays behind that (INV-REQ-866).
 */
class JooqTripleRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : TripleRepository {
    override fun versions(
        entity: EntityRef,
        constitution: Constitution,
    ): List<TripleVersion> =
        dsl
            .selectFrom(IDENTITY_TRIPLE)
            .where(
                IDENTITY_TRIPLE.ENTITY_REF.eq(entity.value),
                IDENTITY_TRIPLE.CONSTITUTION.eq(constitution.code),
            ).orderBy(IDENTITY_TRIPLE.EFFECTIVE_FROM)
            .fetch()
            .map {
                TripleVersion(
                    entity,
                    constitution,
                    IdentityTriple(
                        it.senderId.column("sender_id"),
                        it.supplierId.column("supplier_id"),
                        it.supplierGstn.column("supplier_gstn"),
                    ),
                    it.effectiveFrom.column("effective_from"),
                )
            }

    override fun save(
        version: TripleVersion,
        proposedBy: PersonRef,
        confirmedBy: PersonRef,
        confirmedRole: String,
    ) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(IDENTITY_TRIPLE)
            .set(IDENTITY_TRIPLE.ID, ids.next(clock.instant()))
            .set(IDENTITY_TRIPLE.ENTITY_REF, version.entity.value)
            .set(IDENTITY_TRIPLE.CONSTITUTION, version.constitution.code)
            .set(IDENTITY_TRIPLE.SENDER_ID, version.triple.senderId)
            .set(IDENTITY_TRIPLE.SUPPLIER_ID, version.triple.supplierId)
            .set(IDENTITY_TRIPLE.SUPPLIER_GSTN, version.triple.supplierGstn)
            .set(IDENTITY_TRIPLE.EFFECTIVE_FROM, version.effectiveFrom)
            .set(IDENTITY_TRIPLE.PROPOSED_BY, proposedBy.value)
            .set(IDENTITY_TRIPLE.CONFIRMED_BY, confirmedBy.value)
            .set(IDENTITY_TRIPLE.CONFIRMED_ROLE, confirmedRole)
            .set(IDENTITY_TRIPLE.CREATED_AT, now)
            .set(IDENTITY_TRIPLE.UPDATED_AT, now)
            .set(IDENTITY_TRIPLE.VERSION, 0L)
            .onConflict(
                IDENTITY_TRIPLE.TENANT_ID,
                IDENTITY_TRIPLE.ENTITY_REF,
                IDENTITY_TRIPLE.CONSTITUTION,
                IDENTITY_TRIPLE.EFFECTIVE_FROM,
            ).doUpdate()
            .set(IDENTITY_TRIPLE.SENDER_ID, version.triple.senderId)
            .set(IDENTITY_TRIPLE.SUPPLIER_ID, version.triple.supplierId)
            .set(IDENTITY_TRIPLE.SUPPLIER_GSTN, version.triple.supplierGstn)
            .set(IDENTITY_TRIPLE.PROPOSED_BY, proposedBy.value)
            .set(IDENTITY_TRIPLE.CONFIRMED_BY, confirmedBy.value)
            .set(IDENTITY_TRIPLE.CONFIRMED_ROLE, confirmedRole)
            .set(IDENTITY_TRIPLE.UPDATED_AT, now)
            .set(IDENTITY_TRIPLE.VERSION, IDENTITY_TRIPLE.VERSION.plus(1L))
            .execute()
    }
}
