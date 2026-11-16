package com.dynokore.gst.evidence.adapters

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.sql.Connection
import java.util.UUID

/**
 * Gives a test the retention partition of a chain table for a (tenant, entity, period) before it inserts there
 * (INV-T-23797, the expand step of the partitioning of INV-T-22969). While the chain tables are not partitioned the
 * call writes nothing. Once a chain table is partitioned the call creates the retention partition through
 * evidence.create_retention_partition (INV-T-22969), which returns the existing one when the unit is already there.
 */
object RetentionPartitionFixture {
    /** The chain tables of schema `evidence`, the only names the fixture accepts (exact string equality). */
    val CHAIN_TABLES: List<String> = listOf("decision_trace", "evidence_payload", "acknowledgement", "audit_log")

    fun ensureRetentionPartitions(
        connection: Connection,
        tenantId: UUID,
        entityRef: String,
        periodRef: String,
    ) {
        CHAIN_TABLES.forEach { ensureRetentionPartition(connection, it, tenantId, entityRef, periodRef) }
    }

    /**
     * The partition of the unit created from a tenant transaction already bound to [tenantId] (the runtime login of
     * a tenancy-jooq context), through the same jOOQ SELECT of the function as the append wiring of `ChainWiring`.
     */
    fun createInBoundTransaction(
        dsl: DSLContext,
        chainTable: String,
        tenantId: UUID,
        entityRef: String,
        periodRef: String,
    ) {
        require(chainTable in CHAIN_TABLES) { "not a chain table: $chainTable" }
        dsl
            .select(
                DSL.function(
                    DSL.name(EvidenceDb.SCHEMA, "create_retention_partition"),
                    SQLDataType.VARCHAR,
                    DSL.inline("${EvidenceDb.SCHEMA}.$chainTable"),
                    DSL.`val`(tenantId),
                    DSL.`val`(entityRef),
                    DSL.`val`(periodRef),
                    DSL.`val`(periodRef + "\u0001"),
                ),
            ).fetch()
    }

    fun ensureRetentionPartition(
        dsl: DSLContext,
        chainTable: String,
        tenantId: UUID,
        entityRef: String,
        periodRef: String,
    ) {
        dsl.connection { ensureRetentionPartition(it, chainTable, tenantId, entityRef, periodRef) }
    }

    fun ensureRetentionPartition(
        connection: Connection,
        chainTable: String,
        tenantId: UUID,
        entityRef: String,
        periodRef: String,
    ) {
        require(chainTable in CHAIN_TABLES) { "not a chain table: $chainTable" }
        val partitioned =
            connection
                .prepareStatement(
                    "select exists (select 1 from pg_catalog.pg_partitioned_table " +
                        "where partrelid = to_regclass(?))",
                ).use { statement ->
                    statement.setString(1, "${EvidenceDb.SCHEMA}.$chainTable")
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getBoolean(1)
                    }
                }
        if (!partitioned) return
        // The function runs only in the tenant's own transaction: bind the tenant (signed, transaction-local) and call
        // it in one transaction, then restore the caller's commit mode. The superuser connection of the fixture reads
        // and writes whatever the caller left in a transaction it already owns.
        val autoCommit = connection.autoCommit
        if (autoCommit) connection.autoCommit = false
        try {
            SignedBindings.bind(connection, tenantId)
            connection
                .prepareStatement(
                    "select evidence.create_retention_partition(cast(? as regclass), ?, ?, ?, ? || chr(1))",
                ).use { statement ->
                    statement.setString(1, "${EvidenceDb.SCHEMA}.$chainTable")
                    statement.setObject(2, tenantId)
                    statement.setString(3, entityRef)
                    statement.setString(4, periodRef)
                    statement.setString(5, periodRef)
                    statement.executeQuery().close()
                }
            if (autoCommit) connection.commit()
        } catch (e: java.sql.SQLException) {
            if (autoCommit) connection.rollback()
            throw e
        } finally {
            if (autoCommit) connection.autoCommit = true
        }
    }
}
