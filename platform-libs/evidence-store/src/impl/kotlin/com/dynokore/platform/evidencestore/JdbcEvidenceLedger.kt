package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [EvidenceLedger] over plain JDBC — no ORM (STK §3) and no jOOQ codegen
 * either, since this kernel module knows no service's real schema (the same
 * reasoning [com.dynokore.platform.tenancykernel.TenantRlsMigration] gives for
 * staying schema-agnostic). [schema] and [table] name the concrete table a
 * consuming service's own migration created with a `CREATE TABLE` matching
 * [COLUMNS] plus [EvidenceLedgerSchema.trailerDdl] appended (TPL-12).
 *
 * Every method assumes it runs inside the caller's
 * [com.dynokore.platform.tenancykernel.TransactionRunner] block (ARC-21): it
 * reads [connections] for the already-bound connection and never opens,
 * commits or rolls one back itself.
 */
class JdbcEvidenceLedger(
    private val connections: CurrentConnection,
    private val schema: String,
    private val table: String,
) : EvidenceLedger {
    init {
        require(IDENTIFIER.matches(schema)) { "schema must be a plain lower-case identifier, was '$schema'" }
        require(IDENTIFIER.matches(table)) { "table must be a plain lower-case identifier, was '$table'" }
    }

    private val qualified = "$schema.$table"

    // No `for update`: CS-22 revokes UPDATE from the application role
    // altogether (EvidenceLedgerSchema), and PostgreSQL's row-locking clauses
    // (`FOR UPDATE`/`FOR NO KEY UPDATE`) themselves require the UPDATE
    // privilege on the locked table, not just SELECT — a table with no
    // UPDATE grant at all cannot use them, by the same database-level
    // mechanism that makes the table append-only in the first place. Two
    // concurrent appenders on the same (tenant, entity, period) are
    // serialised instead with a transaction-scoped advisory lock
    // (`acquireChainLock`) — CS-21's own pattern for a claim that needs no
    // table privilege of its own.
    private val tailSql =
        """
        select seq, content_hash from $qualified
        where tenant_id = ? and entity_ref = ? and period_ref = ?
        order by seq desc limit 1
        """.trimIndent()

    private val advisoryLockSql = "select pg_advisory_xact_lock(hashtext(?), hashtext(?))"

    private val insertSql =
        """
        insert into $qualified
            (id, tenant_id, entity_ref, period_ref, seq, evidence_type, content, content_hash, previous_hash,
             recorded_at, doc_uuid, invoice_number, counterparty_uen, category_code)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

    private val selectSql =
        """
        select seq, evidence_type, content, content_hash, previous_hash, recorded_at,
               doc_uuid, invoice_number, counterparty_uen, category_code
        from $qualified
        where tenant_id = ? and entity_ref = ? and period_ref = ?
        order by seq asc
        """.trimIndent()

    override fun append(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
        type: EvidenceType,
        content: ByteArray,
        index: EvidenceIndex,
    ): EvidenceRecord {
        val connection = connections.require()
        acquireChainLock(connection, tenant, entity, period)
        val tail = readTail(connection, tenant, entity, period)
        val sequence = (tail?.first ?: -1L) + 1
        val previousHash = tail?.second
        val hash = HashChain.link(previousHash, content)
        // Truncated to microseconds: `timestamptz` stores no finer than that, so the
        // record this method returns matches, field for field, what a later
        // `findByEntityAndPeriod` reads back — not a nanosecond-rounded near-miss.
        val recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        connection.prepareStatement(insertSql).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, tenant.value)
            statement.setString(3, entity.value)
            statement.setString(4, period.value)
            statement.setLong(5, sequence)
            statement.setString(6, type.name)
            statement.setBytes(7, content)
            statement.setString(8, hash.hex)
            statement.setString(9, previousHash?.hex)
            statement.setTimestamp(10, Timestamp.from(recordedAt))
            statement.setObject(11, index.docUuid)
            statement.setString(12, index.invoiceNumber)
            statement.setString(13, index.counterpartyUen)
            statement.setString(14, index.categoryCode)
            statement.executeUpdate()
        }
        return EvidenceRecord(tenant, entity, period, type, sequence, content, hash, previousHash, recordedAt, index)
    }

    override fun findByEntityAndPeriod(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ): List<EvidenceRecord> {
        val connection = connections.require()
        connection.prepareStatement(selectSql).use { statement ->
            statement.setObject(1, tenant.value)
            statement.setString(2, entity.value)
            statement.setString(3, period.value)
            statement.executeQuery().use { rs ->
                val records = mutableListOf<EvidenceRecord>()
                while (rs.next()) records += rs.toRecord(tenant, entity, period)
                return records
            }
        }
    }

    override fun bulkExport(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ): Sequence<EvidenceRecord> = // A whole audit period's evidence is read once, inside the caller's own
        // transaction, and handed back as a plain list wrapped in a Sequence: an
        // open, forward-only ResultSet cannot outlive this method (its Connection
        // and PreparedStatement are closed on return), so exporting a lazily
        // streamed cursor past this call would leak state ARC-21 does not allow.
        findByEntityAndPeriod(tenant, entity, period).asSequence()

    override fun verifyChain(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ): ChainVerificationResult {
        val records = findByEntityAndPeriod(tenant, entity, period)
        return HashChain.verify(records.map { it.content }, records.map { it.contentHash })
    }

    /**
     * Serialises concurrent `append` calls for the same (tenant, entity,
     * period) without any table-level lock (CS-21's advisory-lock pattern,
     * scoped to two keys the same way `pg_try_advisory_xact_lock(hash(principal, key))`
     * is there): released automatically at commit or rollback, never held
     * past the caller's own transaction.
     */
    private fun acquireChainLock(
        connection: Connection,
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ) {
        connection.prepareStatement(advisoryLockSql).use { statement ->
            statement.setString(1, "${tenant.value}:${entity.value}")
            statement.setString(2, period.value)
            statement.executeQuery().use { it.next() }
        }
    }

    private fun readTail(
        connection: Connection,
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ): Pair<Long, ContentHash>? {
        connection.prepareStatement(tailSql).use { statement ->
            statement.setObject(1, tenant.value)
            statement.setString(2, entity.value)
            statement.setString(3, period.value)
            statement.executeQuery().use { rs ->
                if (!rs.next()) return null
                return rs.getLong("seq") to ContentHash(rs.getString("content_hash"))
            }
        }
    }

    private fun ResultSet.toRecord(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ): EvidenceRecord = EvidenceRecord(
        tenantId = tenant,
        entityRef = entity,
        periodRef = period,
        evidenceType = EvidenceType.valueOf(getString("evidence_type")),
        sequence = getLong("seq"),
        content = getBytes("content"),
        contentHash = ContentHash(getString("content_hash")),
        previousHash = getString("previous_hash")?.let(::ContentHash),
        recordedAt = getTimestamp("recorded_at").toInstant(),
        index = EvidenceIndex(
            docUuid = getObject("doc_uuid", UUID::class.java),
            invoiceNumber = getString("invoice_number"),
            counterpartyUen = getString("counterparty_uen"),
            categoryCode = getString("category_code"),
        ),
    )

    companion object {
        private val IDENTIFIER = Regex("^[a-z_][a-z0-9_]*$")

        /**
         * The columns a service's own `CREATE TABLE` must declare, in order,
         * for this repository to work against it (TPL-12).
         */
        const val COLUMNS =
            "id uuid primary key, tenant_id uuid not null, entity_ref text not null, " +
                "period_ref text not null, seq bigint not null, evidence_type text not null, " +
                "content bytea not null, content_hash text not null, previous_hash text, " +
                "recorded_at timestamptz not null, doc_uuid uuid, invoice_number text, " +
                "counterparty_uen text, category_code text, unique (tenant_id, entity_ref, period_ref, seq)"
    }
}
