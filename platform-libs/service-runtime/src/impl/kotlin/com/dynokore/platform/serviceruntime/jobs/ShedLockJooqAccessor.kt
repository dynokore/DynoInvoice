package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.support.AbstractStorageAccessor
import net.javacrumbs.shedlock.support.StorageBasedLockProvider
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.types.DayToSecond
import java.time.Duration
import java.time.LocalDateTime

/**
 * ShedLock's storage on the service's `shedlock` table through jOOQ (owner answer to INV-Q-5210, option A): the SQL of
 * shedlock-provider-jooq 6.10.0 on `DSL.name(schema, "shedlock")`, a table tenancy-jooq knows as PLATFORM_SCOPED (no
 * tenant data), and each of the four operations in one platform-tenant binding (the service's workload principal) and
 * one TransactionRunner transaction, which the artefact's own accessor cannot do (it names the table without its schema
 * and runs in `dslContext.transactionResult`). `now` is the database clock in UTC, as `usingDbTime()` gave.
 */
class ShedLockJooqAccessor(
    private val scope: TenantScope,
    private val dsl: DSLContext,
    schema: String,
) : AbstractStorageAccessor() {
    private val table: Table<*> = DSL.table(DSL.name(SqlIdentifier.require(schema, "schema"), TABLE))
    private val name: Field<String> = column(schema, "name")
    private val lockUntil: Field<LocalDateTime> = timestamp(schema, "lock_until")
    private val lockedAt: Field<LocalDateTime> = timestamp(schema, "locked_at")
    private val lockedBy: Field<String> = column(schema, "locked_by")

    override fun insertRecord(lockConfiguration: LockConfiguration): Boolean =
        operation {
            insertInto(table)
                .columns(name, lockUntil, lockedAt, lockedBy)
                .values(
                    DSL.`val`(lockConfiguration.name),
                    nowPlus(lockConfiguration.lockAtMostFor),
                    now(),
                    DSL.`val`(hostname),
                ).onConflictDoNothing()
                .execute() > 0
        }

    override fun updateRecord(lockConfiguration: LockConfiguration): Boolean =
        operation {
            update(table)
                .set(name, lockConfiguration.name)
                .set(lockUntil, nowPlus(lockConfiguration.lockAtMostFor))
                .set(lockedAt, now())
                .set(lockedBy, hostname)
                .where(name.eq(lockConfiguration.name).and(lockUntil.le(now())))
                .execute() > 0
        }

    override fun unlock(lockConfiguration: LockConfiguration) {
        operation {
            val atLeast: Field<LocalDateTime> =
                DSL.localDateTimeAdd(
                    lockedAt,
                    interval(lockConfiguration.lockAtLeastFor),
                )
            update(table)
                .set(lockUntil, DSL.`when`(atLeast.gt(now()), atLeast).otherwise(now()))
                .where(name.eq(lockConfiguration.name).and(lockedBy.eq(hostname)))
                .execute()
        }
    }

    override fun extend(lockConfiguration: LockConfiguration): Boolean =
        operation {
            update(table)
                .set(lockUntil, nowPlus(lockConfiguration.lockAtMostFor))
                .where(
                    name
                        .eq(lockConfiguration.name)
                        .and(lockedBy.eq(hostname))
                        .and(lockUntil.gt(now())),
                ).execute() > 0
        }

    /** One platform binding and one transaction per operation (INV-ADR-3968 item 4a). */
    private fun <T> operation(block: DSLContext.() -> T): T =
        scope.bindWorkload(TenantId.PLATFORM) { scope.transactions.inTransaction { dsl.block() } }

    /** The database clock in UTC, a timestamp without a zone like the columns. */
    private fun now(): Field<LocalDateTime> =
        DSL.function(DSL.name("timezone"), SQLDataType.LOCALDATETIME, DSL.`val`("UTC"), DSL.currentOffsetDateTime())

    private fun nowPlus(duration: Duration): Field<LocalDateTime> = DSL.localDateTimeAdd(now(), interval(duration))

    private fun interval(duration: Duration): DayToSecond = DayToSecond.valueOf(duration)

    private fun column(
        schema: String,
        column: String,
    ): Field<String> = DSL.field(DSL.name(SqlIdentifier.require(schema, "schema"), TABLE, column), SQLDataType.VARCHAR)

    private fun timestamp(
        schema: String,
        column: String,
    ): Field<LocalDateTime> =
        DSL.field(DSL.name(SqlIdentifier.require(schema, "schema"), TABLE, column), SQLDataType.LOCALDATETIME)

    private companion object {
        const val TABLE = "shedlock"
    }
}

/**
 * ShedLock core's storage-based provider over [ShedLockJooqAccessor] (its constructor is protected, as in
 * shedlock-provider-jooq).
 */
class ShedLockJooqLockProvider(
    accessor: ShedLockJooqAccessor,
) : StorageBasedLockProvider(accessor)
