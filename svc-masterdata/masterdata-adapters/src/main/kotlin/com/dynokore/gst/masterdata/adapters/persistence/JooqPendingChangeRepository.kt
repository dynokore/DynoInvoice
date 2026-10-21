package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.records.IdentityTripleChangeRecord
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.IDENTITY_TRIPLE_CHANGE
import com.dynokore.gst.masterdata.application.identity.PendingChangeRepository
import com.dynokore.gst.masterdata.domain.identity.ChangeState
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.IdentityTriple
import com.dynokore.gst.masterdata.domain.identity.PendingChange
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.gst.masterdata.domain.identity.TripleVersion
import com.dynokore.platform.outboxandqueue.UuidV7
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset

/** TPL-11. The DSLContext is service-runtime's: the tenancy-jooq listeners scope each statement to the bound tenant. */
class JooqPendingChangeRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : PendingChangeRepository {
    override fun propose(
        version: TripleVersion,
        proposedBy: PersonRef,
    ): PendingChange {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        dsl
            .update(IDENTITY_TRIPLE_CHANGE)
            .set(IDENTITY_TRIPLE_CHANGE.STATE, ChangeState.SUPERSEDED.name)
            .set(IDENTITY_TRIPLE_CHANGE.UPDATED_AT, now)
            .set(IDENTITY_TRIPLE_CHANGE.VERSION, IDENTITY_TRIPLE_CHANGE.VERSION.plus(1L))
            .where(
                IDENTITY_TRIPLE_CHANGE.ENTITY_REF.eq(version.entity.value),
                IDENTITY_TRIPLE_CHANGE.CONSTITUTION.eq(version.constitution.code),
                IDENTITY_TRIPLE_CHANGE.EFFECTIVE_FROM.eq(version.effectiveFrom),
                IDENTITY_TRIPLE_CHANGE.STATE.eq(ChangeState.PENDING_CONFIRMATION.name),
            ).execute()
        val id = ids.next(clock.instant())
        val changeRef = "ITC-$id"
        dsl
            .insertInto(IDENTITY_TRIPLE_CHANGE)
            .set(IDENTITY_TRIPLE_CHANGE.ID, id)
            .set(IDENTITY_TRIPLE_CHANGE.CHANGE_REF, changeRef)
            .set(IDENTITY_TRIPLE_CHANGE.ENTITY_REF, version.entity.value)
            .set(IDENTITY_TRIPLE_CHANGE.CONSTITUTION, version.constitution.code)
            .set(IDENTITY_TRIPLE_CHANGE.SENDER_ID, version.triple.senderId)
            .set(IDENTITY_TRIPLE_CHANGE.SUPPLIER_ID, version.triple.supplierId)
            .set(IDENTITY_TRIPLE_CHANGE.SUPPLIER_GSTN, version.triple.supplierGstn)
            .set(IDENTITY_TRIPLE_CHANGE.EFFECTIVE_FROM, version.effectiveFrom)
            .set(IDENTITY_TRIPLE_CHANGE.STATE, ChangeState.PENDING_CONFIRMATION.name)
            .set(IDENTITY_TRIPLE_CHANGE.PROPOSED_BY, proposedBy.value)
            .set(IDENTITY_TRIPLE_CHANGE.CREATED_AT, now)
            .set(IDENTITY_TRIPLE_CHANGE.UPDATED_AT, now)
            .set(IDENTITY_TRIPLE_CHANGE.VERSION, 0L)
            .execute()
        return PendingChange(changeRef, version, proposedBy, ChangeState.PENDING_CONFIRMATION)
    }

    override fun find(
        entity: EntityRef,
        changeRef: String,
    ): PendingChange? =
        dsl
            .selectFrom(IDENTITY_TRIPLE_CHANGE)
            .where(
                IDENTITY_TRIPLE_CHANGE.ENTITY_REF.eq(entity.value),
                IDENTITY_TRIPLE_CHANGE.CHANGE_REF.eq(changeRef),
            ).fetchOne()
            ?.let(::changeOf)

    override fun markConfirmed(
        change: PendingChange,
        confirmedBy: PersonRef,
        confirmedRole: String,
    ): Boolean =
        dsl
            .update(IDENTITY_TRIPLE_CHANGE)
            .set(IDENTITY_TRIPLE_CHANGE.STATE, ChangeState.CONFIRMED.name)
            .set(IDENTITY_TRIPLE_CHANGE.CONFIRMED_BY, confirmedBy.value)
            .set(IDENTITY_TRIPLE_CHANGE.CONFIRMED_ROLE, confirmedRole)
            .set(IDENTITY_TRIPLE_CHANGE.UPDATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .set(IDENTITY_TRIPLE_CHANGE.VERSION, IDENTITY_TRIPLE_CHANGE.VERSION.plus(1L))
            .where(
                IDENTITY_TRIPLE_CHANGE.ENTITY_REF.eq(change.version.entity.value),
                IDENTITY_TRIPLE_CHANGE.CHANGE_REF.eq(change.changeRef),
                IDENTITY_TRIPLE_CHANGE.STATE.eq(ChangeState.PENDING_CONFIRMATION.name),
            ).execute() == 1

    private fun changeOf(row: IdentityTripleChangeRecord): PendingChange =
        PendingChange(
            row.changeRef.column("change_ref"),
            TripleVersion(
                EntityRef(row.entityRef.column("entity_ref")),
                Constitution(row.constitution.column("constitution")),
                IdentityTriple(
                    row.senderId.column("sender_id"),
                    row.supplierId.column("supplier_id"),
                    row.supplierGstn.column("supplier_gstn"),
                ),
                row.effectiveFrom.column("effective_from"),
            ),
            PersonRef(row.proposedBy.column("proposed_by")),
            ChangeState.valueOf(row.state.column("state")),
        )
}
