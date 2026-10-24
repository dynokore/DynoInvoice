package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.PERIOD_RECORD
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.PROPOSED_PERIOD
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.RAISED_ITEM
import com.dynokore.gst.tenancy.application.period.ConfirmationState
import com.dynokore.gst.tenancy.application.period.ItemState
import com.dynokore.gst.tenancy.application.period.PeriodRecordStore
import com.dynokore.gst.tenancy.application.period.ProposalStore
import com.dynokore.gst.tenancy.application.period.RaisedCode
import com.dynokore.gst.tenancy.application.period.RaisedItem
import com.dynokore.gst.tenancy.application.period.StoredPeriod
import com.dynokore.platform.calendarandperiods.PeriodKind
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private const val IN_FORCE = "IN_FORCE"
private const val SUPERSEDED = "SUPERSEDED"

private fun kindsText(kinds: Set<PeriodKind>): String = kinds.map { it.name }.sorted().joinToString(",")

private fun kindsOf(text: String): Set<PeriodKind> =
    if (text.isEmpty()) emptySet() else text.split(',').map(PeriodKind::valueOf).toSet()

/** The period records of the bound tenant's entities (jOOQ only, CS-18). */
class JooqPeriodRecordStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : PeriodRecordStore {
    override fun inForce(entityId: UUID): List<StoredPeriod> =
        dsl
            .selectFrom(PERIOD_RECORD)
            .where(PERIOD_RECORD.ENTITY_ID.eq(entityId).and(PERIOD_RECORD.RECORD_STATE.eq(IN_FORCE)))
            .orderBy(PERIOD_RECORD.PERIOD_START)
            .fetch()
            .map {
                StoredPeriod(
                    it.periodRef.column("period_ref"),
                    it.periodStart.column("period_start"),
                    it.periodEnd.column("period_end"),
                    it.returnForm.column("return_form"),
                    it.dueDate.column("due_date"),
                    kindsOf(it.kinds.column("kinds")),
                    ConfirmationState.valueOf(it.confirmationState.column("confirmation_state")),
                )
            }

    override fun insert(
        entityId: UUID,
        period: StoredPeriod,
    ) {
        dsl
            .insertInto(PERIOD_RECORD)
            .set(PERIOD_RECORD.ID, UUID.randomUUID())
            .set(PERIOD_RECORD.TENANT_ID, tenants.current().value)
            .set(PERIOD_RECORD.ENTITY_ID, entityId)
            .set(PERIOD_RECORD.PERIOD_REF, period.periodRef)
            .set(PERIOD_RECORD.PERIOD_START, period.start)
            .set(PERIOD_RECORD.PERIOD_END, period.end)
            .set(PERIOD_RECORD.RETURN_FORM, period.returnForm)
            .set(PERIOD_RECORD.DUE_DATE, period.dueDate)
            .set(PERIOD_RECORD.KINDS, kindsText(period.kinds))
            .set(PERIOD_RECORD.IRREGULAR, period.irregular)
            .set(PERIOD_RECORD.CONFIRMATION_STATE, period.confirmation.name)
            .set(PERIOD_RECORD.RECORD_STATE, IN_FORCE)
            .set(PERIOD_RECORD.RECORDED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    override fun supersedeFrom(
        entityId: UUID,
        start: LocalDate,
    ) {
        dsl
            .update(PERIOD_RECORD)
            .set(PERIOD_RECORD.RECORD_STATE, SUPERSEDED)
            .where(
                PERIOD_RECORD.ENTITY_ID
                    .eq(entityId)
                    .and(PERIOD_RECORD.RECORD_STATE.eq(IN_FORCE))
                    .and(PERIOD_RECORD.PERIOD_START.ge(start)),
            ).execute()
    }

    override fun confirm(
        entityId: UUID,
        periodRef: String,
    ) {
        val updated =
            dsl
                .update(PERIOD_RECORD)
                .set(PERIOD_RECORD.CONFIRMATION_STATE, ConfirmationState.CONFIRMED.name)
                .where(
                    PERIOD_RECORD.ENTITY_ID
                        .eq(entityId)
                        .and(PERIOD_RECORD.PERIOD_REF.eq(periodRef))
                        .and(PERIOD_RECORD.RECORD_STATE.eq(IN_FORCE)),
                ).execute()
        check(updated == 1) { "period $periodRef of the entity is not in force" }
    }
}

/** The proposals of frequency changes and their raised items of the bound tenant's entities (jOOQ only, CS-18). */
class JooqProposalStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : ProposalStore {
    override fun proposedPeriods(
        entityId: UUID,
        historyId: UUID,
    ): List<StoredPeriod> =
        dsl
            .selectFrom(PROPOSED_PERIOD)
            .where(PROPOSED_PERIOD.ENTITY_ID.eq(entityId).and(PROPOSED_PERIOD.HISTORY_ID.eq(historyId)))
            .orderBy(PROPOSED_PERIOD.PERIOD_START)
            .fetch()
            .map {
                StoredPeriod(
                    it.periodRef.column("period_ref"),
                    it.periodStart.column("period_start"),
                    it.periodEnd.column("period_end"),
                    it.returnForm.column("return_form"),
                    it.dueDate.column("due_date"),
                    kindsOf(it.kinds.column("kinds")),
                )
            }

    override fun insertProposed(
        entityId: UUID,
        historyId: UUID,
        periods: List<StoredPeriod>,
    ) {
        periods.forEach {
            dsl
                .insertInto(PROPOSED_PERIOD)
                .set(PROPOSED_PERIOD.ID, UUID.randomUUID())
                .set(PROPOSED_PERIOD.TENANT_ID, tenants.current().value)
                .set(PROPOSED_PERIOD.ENTITY_ID, entityId)
                .set(PROPOSED_PERIOD.HISTORY_ID, historyId)
                .set(PROPOSED_PERIOD.PERIOD_REF, it.periodRef)
                .set(PROPOSED_PERIOD.PERIOD_START, it.start)
                .set(PROPOSED_PERIOD.PERIOD_END, it.end)
                .set(PROPOSED_PERIOD.RETURN_FORM, it.returnForm)
                .set(PROPOSED_PERIOD.DUE_DATE, it.dueDate)
                .set(PROPOSED_PERIOD.KINDS, kindsText(it.kinds))
                .set(PROPOSED_PERIOD.IRREGULAR, it.irregular)
                .execute()
        }
    }

    override fun items(entityId: UUID): List<RaisedItem> =
        dsl
            .selectFrom(RAISED_ITEM)
            .where(RAISED_ITEM.ENTITY_ID.eq(entityId))
            .orderBy(RAISED_ITEM.RAISED_AT, RAISED_ITEM.CODE)
            .fetch()
            .map {
                RaisedItem(
                    it.historyId.column("history_id"),
                    RaisedCode.entries.single { code -> code.code == it.code.column("code") },
                    ItemState.valueOf(it.state.column("state")),
                )
            }

    override fun raise(
        entityId: UUID,
        historyId: UUID,
        code: RaisedCode,
    ) {
        dsl
            .insertInto(RAISED_ITEM)
            .set(RAISED_ITEM.ID, UUID.randomUUID())
            .set(RAISED_ITEM.TENANT_ID, tenants.current().value)
            .set(RAISED_ITEM.ENTITY_ID, entityId)
            .set(RAISED_ITEM.HISTORY_ID, historyId)
            .set(RAISED_ITEM.CODE, code.code)
            .set(RAISED_ITEM.STATE, ItemState.OPEN.name)
            .set(RAISED_ITEM.RAISED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    override fun moveItems(
        entityId: UUID,
        historyId: UUID,
        to: ItemState,
    ) {
        dsl
            .update(RAISED_ITEM)
            .set(RAISED_ITEM.STATE, to.name)
            .where(
                RAISED_ITEM.ENTITY_ID
                    .eq(entityId)
                    .and(RAISED_ITEM.HISTORY_ID.eq(historyId))
                    .and(RAISED_ITEM.STATE.eq(ItemState.OPEN.name)),
            ).execute()
    }
}
