package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.Aggregate.Companion.AGGREGATE
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateLine.Companion.AGGREGATE_LINE
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.records.AggregateRecord
import com.dynokore.gst.aggregation.application.AggregateRepository
import com.dynokore.gst.aggregation.application.AggregateRevisions
import com.dynokore.gst.aggregation.application.DuplicateIntakeKey
import com.dynokore.gst.aggregation.application.NewAggregate
import com.dynokore.gst.aggregation.application.StoredAggregate
import com.dynokore.gst.aggregation.domain.AggregateLine
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.WindowState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.postgresql.util.PSQLException
import java.util.UUID

/**
 * The aggregates of the intake in `aggregation.aggregate` (INV-REQ-434). Every method runs in its own tenant-bound
 * transaction, so a unique violation rolls back only the insert it came from and the caller can read the winner.
 */
class JooqAggregateStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val tenants: TenantContext,
    private val usedCodes: JooqUsedShortCodes?,
) : AggregateRepository,
    AggregateRevisions {
    /** A store that only reads and maps rows (the hand-off and revision readers); [store] and [replace] refuse. */
    constructor(dsl: DSLContext, runner: TransactionRunner, tenants: TenantContext) :
        this(dsl, runner, tenants, null)

    private val seen = JooqSeenRevisions(dsl, tenants, runner)

    override fun findByIntakeKey(
        entityRef: String,
        sourceKey: String,
        sourceRevision: Long,
    ): StoredAggregate? =
        runner.inTransaction {
            dsl
                .selectFrom(AGGREGATE)
                .where(AGGREGATE.TENANT_ID.eq(tenants.current().value))
                .and(AGGREGATE.ENTITY_REF.eq(entityRef))
                .and(AGGREGATE.SOURCE_KEY.eq(sourceKey))
                .and(AGGREGATE.SOURCE_REVISION.eq(sourceRevision))
                .fetchOne()
                ?.takeUnless { dsl.lostToLowerRevision(tenants.current().value, it, sourceRevision) }
                ?.let(::stored)
                ?: seen.aggregateId(entityRef, sourceKey, sourceRevision)?.let { id ->
                    dsl
                        .selectFrom(AGGREGATE)
                        .where(AGGREGATE.TENANT_ID.eq(tenants.current().value))
                        .and(AGGREGATE.AGGREGATE_ID.eq(id))
                        .fetchOne()
                        ?.let(::stored)
                }
        }

    override fun highestRevision(
        entityRef: String,
        sourceKey: String,
    ): Long? =
        runner.inTransaction {
            val tenant = tenants.current().value
            val stored =
                dsl
                    .select(DSL.max(AGGREGATE.SOURCE_REVISION))
                    .from(AGGREGATE)
                    .where(AGGREGATE.TENANT_ID.eq(tenant))
                    .and(AGGREGATE.ENTITY_REF.eq(entityRef))
                    .and(AGGREGATE.SOURCE_KEY.eq(sourceKey))
                    .fetchOne()
                    ?.value1()
            listOfNotNull(stored, seen.highest(entityRef, sourceKey)).maxOrNull()
        }

    override fun nextSequence(): Long =
        runner.inTransaction { requireNotNull(dsl.select(SEQUENCE.nextval()).fetchOne()?.value1()) }

    override fun store(aggregate: NewAggregate): StoredAggregate {
        val id = UUID.randomUUID()
        try {
            runner.inTransaction { insert(id, aggregate) }
        } catch (failure: DataAccessException) {
            if (isIntakeKeyViolation(failure)) throw DuplicateIntakeKey()
            throw failure
        }
        return StoredAggregate(aggregate, id)
    }

    override fun current(
        entityRef: String,
        sourceKey: String,
    ): StoredAggregate? =
        runner.inTransaction {
            dsl
                .selectFrom(AGGREGATE)
                .where(AGGREGATE.TENANT_ID.eq(tenants.current().value))
                .and(AGGREGATE.ENTITY_REF.eq(entityRef))
                .and(AGGREGATE.SOURCE_KEY.eq(sourceKey))
                .and(AGGREGATE.SUPERSEDED_BY.isNull)
                .orderBy(AGGREGATE.SOURCE_REVISION.desc())
                .limit(1)
                .fetchOne()
                ?.let(::stored)
        }

    override fun recordedLines(
        entityRef: String,
        sourceKey: String,
        excluding: UUID?,
    ): List<AggregateLine> =
        runner.inTransaction {
            val tenant = tenants.current().value
            dsl
                .select(AGGREGATE_LINE.INTERNAL_TAX_CODE, AGGREGATE_LINE.AMOUNT)
                .from(AGGREGATE_LINE)
                .join(AGGREGATE)
                .on(AGGREGATE.AGGREGATE_ID.eq(AGGREGATE_LINE.AGGREGATE_ID))
                .where(AGGREGATE_LINE.TENANT_ID.eq(tenant))
                .and(AGGREGATE.TENANT_ID.eq(tenant))
                .and(AGGREGATE.ENTITY_REF.eq(entityRef))
                .and(AGGREGATE.SOURCE_KEY.eq(sourceKey))
                .and(AGGREGATE.SUPERSEDED_BY.isNull)
                .and(excluding?.let { AGGREGATE.AGGREGATE_ID.ne(it) } ?: DSL.noCondition())
                .orderBy(AGGREGATE.SOURCE_REVISION, AGGREGATE_LINE.POSITION)
                .fetch { AggregateLine(checkNotNull(it.value1()), checkNotNull(it.value2())) }
        }

    override fun replace(
        old: StoredAggregate,
        replacement: NewAggregate,
    ): StoredAggregate {
        val id = UUID.randomUUID()
        try {
            runner.inTransaction {
                // the old row is marked first: the number is unique among aggregates not superseded
                val marked =
                    dsl
                        .update(AGGREGATE)
                        .set(AGGREGATE.SUPERSEDED_BY, id)
                        .where(AGGREGATE.TENANT_ID.eq(tenants.current().value))
                        .and(AGGREGATE.AGGREGATE_ID.eq(old.aggregateId))
                        .and(AGGREGATE.SUPERSEDED_BY.isNull)
                        .and(AGGREGATE.TRANSMITTED_AT.isNull)
                        .and(AGGREGATE.HANDED_ON_AT.isNull)
                        .execute()
                check(marked == 1) { "the aggregate to replace changed state during the intake" }
                insert(id, replacement)
            }
        } catch (failure: DataAccessException) {
            if (isIntakeKeyViolation(failure)) throw DuplicateIntakeKey()
            throw failure
        }
        return StoredAggregate(replacement, id)
    }

    private fun insert(
        id: UUID,
        aggregate: NewAggregate,
    ) {
        val tenant = tenants.current().value
        dsl
            .insertInto(AGGREGATE)
            .set(AGGREGATE.AGGREGATE_ID, id)
            .set(AGGREGATE.TENANT_ID, tenant)
            .set(AGGREGATE.ENTITY_REF, aggregate.entityRef)
            .set(AGGREGATE.BUSINESS_UNIT_REF, aggregate.businessUnitRef)
            .set(AGGREGATE.AGGREGATE_TYPE, aggregate.aggregateType.name)
            .set(AGGREGATE.PERIOD_START, aggregate.periodStart)
            .set(AGGREGATE.PERIOD_END, aggregate.periodEnd)
            .set(AGGREGATE.SEQUENCE, aggregate.sequence)
            .set(AGGREGATE.SOURCE_KEY, aggregate.sourceKey)
            .set(AGGREGATE.SOURCE_REVISION, aggregate.sourceRevision)
            .set(AGGREGATE.ENTITY_CODE, aggregate.entityCode)
            .set(AGGREGATE.BUSINESS_UNIT_CODE, aggregate.businessUnitCode)
            .set(AGGREGATE.DOCUMENT_NUMBER, aggregate.documentNumber)
            .set(AGGREGATE.ISSUE_DATE, aggregate.issueDate)
            .set(AGGREGATE.TRANSACTION_DATE, aggregate.transactionDate)
            .set(AGGREGATE.WINDOW_START, aggregate.windowStart)
            .set(AGGREGATE.WINDOW_END, aggregate.windowEnd)
            .set(AGGREGATE.WINDOW_STATE, aggregate.windowState?.name)
            .set(AGGREGATE.SUPPLEMENTARY, aggregate.isSupplementary)
            .execute()
        // the first use of a code is recorded and announced in this transaction (INV-AC-2779, INV-Q-11266 A)
        checkNotNull(
            usedCodes,
        ) { "a store without the used-code record cannot store an aggregate" }.record(id, aggregate)
        aggregate.lines.forEachIndexed { position, line ->
            dsl
                .insertInto(AGGREGATE_LINE)
                .set(AGGREGATE_LINE.TENANT_ID, tenant)
                .set(AGGREGATE_LINE.AGGREGATE_ID, id)
                .set(AGGREGATE_LINE.POSITION, position)
                .set(AGGREGATE_LINE.INTERNAL_TAX_CODE, line.internalTaxCode)
                .set(AGGREGATE_LINE.AMOUNT, line.amount)
                .execute()
        }
    }

    internal fun stored(row: AggregateRecord): StoredAggregate =
        StoredAggregate(
            NewAggregate(
                entityRef = row.required(AGGREGATE.ENTITY_REF),
                businessUnitRef = row.required(AGGREGATE.BUSINESS_UNIT_REF),
                aggregateType = AggregateType.valueOf(row.required(AGGREGATE.AGGREGATE_TYPE)),
                periodStart = row.required(AGGREGATE.PERIOD_START),
                periodEnd = row.required(AGGREGATE.PERIOD_END),
                sourceKey = row.required(AGGREGATE.SOURCE_KEY),
                sourceRevision = row.required(AGGREGATE.SOURCE_REVISION),
                sequence = row.required(AGGREGATE.SEQUENCE),
                entityCode = row.required(AGGREGATE.ENTITY_CODE),
                businessUnitCode = row.required(AGGREGATE.BUSINESS_UNIT_CODE),
                documentNumber = row.required(AGGREGATE.DOCUMENT_NUMBER),
                issueDate = row.required(AGGREGATE.ISSUE_DATE),
                transactionDate = row.required(AGGREGATE.TRANSACTION_DATE),
                windowStart = row.get(AGGREGATE.WINDOW_START),
                windowEnd = row.get(AGGREGATE.WINDOW_END),
                windowState = row.get(AGGREGATE.WINDOW_STATE)?.let(WindowState::valueOf),
                lines = linesOf(row.required(AGGREGATE.AGGREGATE_ID)),
                isSupplementary = row.required(AGGREGATE.SUPPLEMENTARY),
            ),
            row.required(AGGREGATE.AGGREGATE_ID),
            row.get(AGGREGATE.TRANSMITTED_AT)?.toInstant(),
            row.get(AGGREGATE.HANDED_ON_AT) != null,
        )

    private fun linesOf(aggregateId: UUID): List<AggregateLine> =
        dsl
            .select(AGGREGATE_LINE.INTERNAL_TAX_CODE, AGGREGATE_LINE.AMOUNT)
            .from(AGGREGATE_LINE)
            .where(AGGREGATE_LINE.TENANT_ID.eq(tenants.current().value))
            .and(AGGREGATE_LINE.AGGREGATE_ID.eq(aggregateId))
            .orderBy(AGGREGATE_LINE.POSITION)
            .fetch { AggregateLine(checkNotNull(it.value1()), checkNotNull(it.value2())) }

    private fun <T : Any> AggregateRecord.required(field: Field<T?>): T =
        checkNotNull(get(field)) { "${field.name} is not null in the table" }

    companion object {
        const val SCHEMA = "aggregation"
        const val SEQUENCE_NAME = "aggregate_sequence"
        const val INTAKE_KEY = "aggregate_intake_key"
        private const val UNIQUE_VIOLATION = "23505"

        /**
         * Only a unique violation (23505) of the intake key is a replay; any other constraint is a defect
         * (INV-Q-13905 A). Decided by the structured PostgreSQL error fields, never by the localized message text.
         */
        internal fun isIntakeKeyViolation(failure: DataAccessException): Boolean {
            val server =
                generateSequence<Throwable>(failure) { it.cause }
                    .filterIsInstance<PSQLException>()
                    .firstOrNull() ?: return false
            return server.sqlState == UNIQUE_VIOLATION && server.serverErrorMessage?.constraint == INTAKE_KEY
        }

        /** Read through the hand-written sequence (INV-Q-7449 A); the names equal those of the migration. */
        val SEQUENCE = DSL.sequence(DSL.name(SCHEMA, SEQUENCE_NAME), SQLDataType.BIGINT)
    }
}

/**
 * A row superseded by an aggregate of a lower revision lost it to a zero-difference revision (INV-Q-22395 A): it is no
 * replay (ING-002), unlike a row that a higher revision replaced. Call inside a transaction.
 */
private fun DSLContext.lostToLowerRevision(
    tenant: UUID,
    row: AggregateRecord,
    sourceRevision: Long,
): Boolean {
    val by = row.get(AGGREGATE.SUPERSEDED_BY) ?: return false
    val revision =
        select(AGGREGATE.SOURCE_REVISION)
            .from(AGGREGATE)
            .where(AGGREGATE.TENANT_ID.eq(tenant))
            .and(AGGREGATE.AGGREGATE_ID.eq(by))
            .fetchOne()
            ?.value1()
    return revision != null && revision < sourceRevision
}
