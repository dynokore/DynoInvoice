package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.Aggregate.Companion.AGGREGATE
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.records.AggregateRecord
import com.dynokore.gst.aggregation.application.AggregateRepository
import com.dynokore.gst.aggregation.application.DuplicateIntakeKey
import com.dynokore.gst.aggregation.application.NewAggregate
import com.dynokore.gst.aggregation.application.StoredAggregate
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.util.UUID

/**
 * The aggregates of the intake in `aggregation.aggregate` (INV-REQ-434). Every method runs in its own tenant-bound
 * transaction, so a unique violation rolls back only the insert it came from and the caller can read the winner.
 */
class JooqAggregateStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val tenants: TenantContext,
) : AggregateRepository {
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
                ?.let(::stored)
        }

    override fun highestRevision(
        entityRef: String,
        sourceKey: String,
    ): Long? =
        runner.inTransaction {
            dsl
                .select(DSL.max(AGGREGATE.SOURCE_REVISION))
                .from(AGGREGATE)
                .where(AGGREGATE.TENANT_ID.eq(tenants.current().value))
                .and(AGGREGATE.ENTITY_REF.eq(entityRef))
                .and(AGGREGATE.SOURCE_KEY.eq(sourceKey))
                .fetchOne()
                ?.value1()
        }

    override fun nextSequence(): Long =
        runner.inTransaction { requireNotNull(dsl.select(SEQUENCE.nextval()).fetchOne()?.value1()) }

    override fun store(aggregate: NewAggregate): StoredAggregate {
        val id = UUID.randomUUID()
        try {
            runner.inTransaction {
                dsl
                    .insertInto(AGGREGATE)
                    .set(AGGREGATE.AGGREGATE_ID, id)
                    .set(AGGREGATE.TENANT_ID, tenants.current().value)
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
                    .execute()
            }
        } catch (failure: DataAccessException) {
            if (isIntakeKeyViolation(failure)) throw DuplicateIntakeKey()
            throw failure
        }
        return StoredAggregate(aggregate, id)
    }

    /** Only a unique violation (23505) of the intake key is a replay; any other constraint is a defect. */
    private fun isIntakeKeyViolation(failure: DataAccessException): Boolean =
        failure.sqlState() == UNIQUE_VIOLATION &&
            generateSequence<Throwable>(failure) { it.cause }.any { it.message?.contains("\"$INTAKE_KEY\"") == true }

    private fun stored(row: AggregateRecord): StoredAggregate =
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
            ),
            row.required(AGGREGATE.AGGREGATE_ID),
        )

    private fun <T : Any> AggregateRecord.required(field: Field<T?>): T =
        checkNotNull(get(field)) { "${field.name} is not null in the table" }

    companion object {
        const val SCHEMA = "aggregation"
        const val SEQUENCE_NAME = "aggregate_sequence"
        const val INTAKE_KEY = "aggregate_intake_key"
        private const val UNIQUE_VIOLATION = "23505"

        /** Read through the hand-written sequence (INV-Q-7449 A); the names equal those of the migration. */
        val SEQUENCE = DSL.sequence(DSL.name(SCHEMA, SEQUENCE_NAME), SQLDataType.BIGINT)
    }
}
