package com.dynokore.gst.evidence.adapters

import org.jooq.DSLContext
import java.sql.Connection
import java.util.UUID

/**
 * Gives a test the retention partition of a chain table for a (tenant, entity, period) before it inserts there
 * (INV-T-23797, the expand step of the partitioning of INV-T-22969). While the chain tables are not partitioned the
 * call writes nothing. Once a chain table is partitioned the call fails closed, so the contract step of INV-T-22969
 * must replace that branch and it is never skipped silently.
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

    fun ensureRetentionPartition(
        dsl: DSLContext,
        chainTable: String,
        tenantId: UUID,
        entityRef: String,
        periodRef: String,
    ) {
        dsl.connection { ensureRetentionPartition(it, chainTable, tenantId, entityRef, periodRef) }
    }

    @Suppress("UNUSED_PARAMETER")
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
        check(!partitioned) {
            "evidence.$chainTable is partitioned: the retention partition for the tenant, entity and period " +
                "is not created by this fixture yet (INV-T-22969 replaces this branch)"
        }
    }
}
