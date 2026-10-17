package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [EvidenceLedger] through jOOQ's DSL on the module's [DSLContext] (tenancy-jooq's
 * factory, INV-T-4516) — no ORM (STK §3) and no jOOQ codegen, since this kernel
 * module knows no service's real schema (the same reasoning
 * [com.dynokore.platform.tenancykernel.TenantRlsMigration] gives for staying
 * schema-agnostic). [schema] and [table] name the concrete table a
 * consuming service's own migration created with a `CREATE TABLE` matching
 * [COLUMNS] plus [EvidenceLedgerSchema.trailerDdl] appended (TPL-12).
 *
 * Every method assumes it runs inside the caller's
 * [com.dynokore.platform.tenancykernel.TransactionRunner] block (ARC-21): it
 * statements run on the connection that block bound (tenancy-jooq's connection
 * provider) and it never opens, commits or rolls one back itself.
 */
class JdbcEvidenceLedger(
    private val dsl: DSLContext,
    schema: String,
    table: String,
) : EvidenceLedger {
    // INV-REQ-854 b5 (coordinator ruling INV-Q-4036): the names enter SQL only through SqlIdentifier.require.
    private val schemaName = SqlIdentifier.require(schema, "schema")
    private val tableName = SqlIdentifier.require(table, "table")
    private val ledger: Table<*> = DSL.table(DSL.name(schemaName, tableName))

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
    override fun append(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
        type: EvidenceType,
        content: ByteArray,
        index: EvidenceIndex,
    ): EvidenceRecord {
        acquireChainLock(tenant, entity, period)
        val tail = readTail(tenant, entity, period)
        val sequence = (tail?.first ?: -1L) + 1
        val previousHash = tail?.second
        val hash = HashChain.link(previousHash, content)
        // Truncated to microseconds: `timestamptz` stores no finer than that, so the
        // record this method returns matches, field for field, what a later
        // `findByEntityAndPeriod` reads back — not a nanosecond-rounded near-miss.
        val recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        dsl
            .insertInto(ledger)
            .columns(
                ID,
                TENANT_ID,
                ENTITY_REF,
                PERIOD_REF,
                SEQ,
                EVIDENCE_TYPE,
                CONTENT,
                CONTENT_HASH,
                PREVIOUS_HASH,
                RECORDED_AT,
                DOC_UUID,
                INVOICE_NUMBER,
                COUNTERPARTY_UEN,
                CATEGORY_CODE,
            ).values(
                UUID.randomUUID(),
                tenant.value,
                entity.value,
                period.value,
                sequence,
                type.name,
                content,
                hash.hex,
                previousHash?.hex,
                recordedAt.atOffset(ZoneOffset.UTC),
                index.docUuid,
                index.invoiceNumber,
                index.counterpartyUen,
                index.categoryCode,
            ).execute()
        return EvidenceRecord(tenant, entity, period, type, sequence, content, hash, previousHash, recordedAt, index)
    }

    override fun findByEntityAndPeriod(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ): List<EvidenceRecord> =
        dsl
            .select(
                SEQ,
                EVIDENCE_TYPE,
                CONTENT,
                CONTENT_HASH,
                PREVIOUS_HASH,
                RECORDED_AT,
                DOC_UUID,
                INVOICE_NUMBER,
                COUNTERPARTY_UEN,
                CATEGORY_CODE,
            ).from(ledger)
            .where(TENANT_ID.eq(tenant.value), ENTITY_REF.eq(entity.value), PERIOD_REF.eq(period.value))
            .orderBy(SEQ.asc())
            .fetch()
            .map { it.toRecord(tenant, entity, period) }

    override fun bulkExport(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ): Sequence<EvidenceRecord> =
        // A whole audit period's evidence is read once, inside the caller's own
        // transaction, and handed back as a plain list wrapped in a Sequence: an
        // open, forward-only cursor cannot outlive this method (its connection
        // is the caller's and is closed with its transaction), so exporting a lazily
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
     * past the caller's own transaction. A jOOQ function call; both keys are bind values.
     */
    private fun acquireChainLock(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ) {
        val lock =
            DSL.function(
                DSL.name("pg_advisory_xact_lock"),
                SQLDataType.OTHER,
                hashText("${tenant.value}:${entity.value}"),
                hashText(period.value),
            )
        dsl.select(lock).fetch()
    }

    private fun hashText(text: String): Field<Int> =
        DSL.function(DSL.name("hashtext"), SQLDataType.INTEGER, DSL.`val`(text))

    private fun readTail(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ): Pair<Long, ContentHash>? =
        dsl
            .select(SEQ, CONTENT_HASH)
            .from(ledger)
            .where(TENANT_ID.eq(tenant.value), ENTITY_REF.eq(entity.value), PERIOD_REF.eq(period.value))
            .orderBy(SEQ.desc())
            .limit(1)
            .fetchOne()
            ?.let { it.get(SEQ) to ContentHash(it.get(CONTENT_HASH)) }

    private fun Record.toRecord(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ): EvidenceRecord =
        EvidenceRecord(
            tenantId = tenant,
            entityRef = entity,
            periodRef = period,
            evidenceType = EvidenceType.valueOf(get(EVIDENCE_TYPE)),
            sequence = get(SEQ),
            content = get(CONTENT),
            contentHash = ContentHash(get(CONTENT_HASH)),
            previousHash = get(PREVIOUS_HASH)?.let(::ContentHash),
            recordedAt = get(RECORDED_AT).toInstant(),
            index =
                EvidenceIndex(
                    docUuid = get(DOC_UUID),
                    invoiceNumber = get(INVOICE_NUMBER),
                    counterpartyUen = get(COUNTERPARTY_UEN),
                    categoryCode = get(CATEGORY_CODE),
                ),
        )

    companion object {
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

private val ID = DSL.field(DSL.name("id"), SQLDataType.UUID)
private val TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
private val ENTITY_REF = DSL.field(DSL.name("entity_ref"), SQLDataType.CLOB)
private val PERIOD_REF = DSL.field(DSL.name("period_ref"), SQLDataType.CLOB)
private val SEQ = DSL.field(DSL.name("seq"), SQLDataType.BIGINT)
private val EVIDENCE_TYPE = DSL.field(DSL.name("evidence_type"), SQLDataType.CLOB)
private val CONTENT = DSL.field(DSL.name("content"), SQLDataType.BLOB)
private val CONTENT_HASH = DSL.field(DSL.name("content_hash"), SQLDataType.CLOB)
private val PREVIOUS_HASH = DSL.field(DSL.name("previous_hash"), SQLDataType.CLOB)
private val RECORDED_AT = DSL.field(DSL.name("recorded_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
private val DOC_UUID = DSL.field(DSL.name("doc_uuid"), SQLDataType.UUID)
private val INVOICE_NUMBER = DSL.field(DSL.name("invoice_number"), SQLDataType.CLOB)
private val COUNTERPARTY_UEN = DSL.field(DSL.name("counterparty_uen"), SQLDataType.CLOB)
private val CATEGORY_CODE = DSL.field(DSL.name("category_code"), SQLDataType.CLOB)
