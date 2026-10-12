package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Timestamp

/**
 * [LegalHoldRegistry] over plain JDBC. Unlike [JdbcEvidenceLedger]'s table,
 * this one table IS mutable in place: a hold is a control flag, placed and
 * released (FR-D.4 behaviour 2), not evidence — CS-22 governs evidence rows
 * only.
 *
 * Runs inside the caller's own
 * [com.dynokore.platform.tenancykernel.TransactionRunner] block (ARC-21), the
 * same as [JdbcEvidenceLedger].
 */
class JdbcLegalHoldRegistry(
    private val connections: CurrentConnection,
    schema: String,
    table: String,
) : LegalHoldRegistry {
    // INV-REQ-854 b5 (coordinator ruling INV-Q-4036): the names enter SQL text only through SqlIdentifier.require.
    private val schemaName = SqlIdentifier.require(schema, "schema")
    private val tableName = SqlIdentifier.require(table, "table")

    private val upsertSql =
        """
        insert into $schemaName.$tableName (tenant_id, entity_ref, period_ref, reason, placed_at)
        values (?, ?, ?, ?, ?)
        on conflict (tenant_id, entity_ref, period_ref)
        do update set reason = excluded.reason, placed_at = excluded.placed_at
        """.trimIndent()

    private val deleteSql = "delete from $schemaName.$tableName where tenant_id = ? and entity_ref = ? and period_ref = ?"

    private val existsSql = "select 1 from $schemaName.$tableName where tenant_id = ? and entity_ref = ? and period_ref = ?"

    override fun place(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
        hold: LegalHold,
    ) {
        connections.require().prepareStatement(upsertSql).use { statement ->
            statement.setObject(1, tenant.value)
            statement.setString(2, entity.value)
            statement.setString(3, period.value)
            statement.setString(4, hold.reason)
            statement.setTimestamp(5, Timestamp.from(hold.placedAt))
            statement.executeUpdate()
        }
    }

    override fun release(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ) {
        connections.require().prepareStatement(deleteSql).use { statement ->
            statement.setObject(1, tenant.value)
            statement.setString(2, entity.value)
            statement.setString(3, period.value)
            statement.executeUpdate()
        }
    }

    override fun isHeld(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ): Boolean {
        connections.require().prepareStatement(existsSql).use { statement ->
            statement.setObject(1, tenant.value)
            statement.setString(2, entity.value)
            statement.setString(3, period.value)
            statement.executeQuery().use { rs -> return rs.next() }
        }
    }

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
