package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.ZoneOffset

/**
 * [LegalHoldRegistry] through jOOQ's DSL on the module's [DSLContext] (tenancy-jooq's factory, INV-T-4516). Unlike
 * [JdbcEvidenceLedger]'s table,
 * this one table IS mutable in place: a hold is a control flag, placed and
 * released (FR-D.4 behaviour 2), not evidence — CS-22 governs evidence rows
 * only.
 *
 * Runs inside the caller's own
 * [com.dynokore.platform.tenancykernel.TransactionRunner] block (ARC-21), the
 * same as [JdbcEvidenceLedger].
 */
class JdbcLegalHoldRegistry(
    private val dsl: DSLContext,
    schema: String,
    table: String,
) : LegalHoldRegistry {
    // INV-REQ-854 b5 (coordinator ruling INV-Q-4036): the names enter SQL only through SqlIdentifier.require.
    private val schemaName = SqlIdentifier.require(schema, "schema")
    private val tableName = SqlIdentifier.require(table, "table")
    private val holds: Table<*> = DSL.table(DSL.name(schemaName, tableName))

    override fun place(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
        hold: LegalHold,
    ) {
        val placedAt = hold.placedAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(holds)
            .columns(HOLD_TENANT_ID, HOLD_ENTITY_REF, HOLD_PERIOD_REF, HOLD_REASON, HOLD_PLACED_AT)
            .values(tenant.value, entity.value, period.value, hold.reason, placedAt)
            .onConflict(HOLD_TENANT_ID, HOLD_ENTITY_REF, HOLD_PERIOD_REF)
            .doUpdate()
            .set(HOLD_REASON, hold.reason)
            .set(HOLD_PLACED_AT, placedAt)
            .execute()
    }

    override fun release(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ) {
        dsl
            .deleteFrom(holds)
            .where(HOLD_TENANT_ID.eq(tenant.value), HOLD_ENTITY_REF.eq(entity.value), HOLD_PERIOD_REF.eq(period.value))
            .execute()
    }

    override fun isHeld(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ): Boolean =
        dsl.fetchExists(
            holds,
            DSL.and(
                HOLD_TENANT_ID.eq(tenant.value),
                HOLD_ENTITY_REF.eq(entity.value),
                HOLD_PERIOD_REF.eq(period.value),
            ),
        )

    companion object {
        /**
         * The columns a service's own `CREATE TABLE` must declare, in order,
         * for this repository to work against it (TPL-12).
         */
        const val COLUMNS =
            "tenant_id uuid not null, entity_ref text not null, period_ref text not null, reason text not null, " +
                "placed_at timestamptz not null, primary key (tenant_id, entity_ref, period_ref)"
    }
}

private val HOLD_TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
private val HOLD_ENTITY_REF = DSL.field(DSL.name("entity_ref"), SQLDataType.CLOB)
private val HOLD_PERIOD_REF = DSL.field(DSL.name("period_ref"), SQLDataType.CLOB)
private val HOLD_REASON = DSL.field(DSL.name("reason"), SQLDataType.CLOB)
private val HOLD_PLACED_AT = DSL.field(DSL.name("placed_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
