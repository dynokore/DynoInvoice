package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.pool.PoolLane
import com.dynokore.platform.serviceruntime.pool.PoolLanes
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.DatePart
import org.jooq.Name
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** The job retention: the start-up check against the migration-written row, and the purge. */
internal class JobRetention(
    private val scope: TenantScope,
    private val dsl: DSLContext,
    private val retentionWindow: Name,
    private val purgeExpired: Name,
    private val retention: Duration,
) {
    /**
     * Refuses to start when [retention] differs from the job-retention row the migration runner wrote (coordinator
     * ruling INV-Q-4346 (A)), or when the row is missing: the purge reads only the row.
     */
    fun check() {
        val window =
            scope.bindWorkload(TenantId.PLATFORM) {
                scope.transactions.inTransaction {
                    val interval = DSL.function(retentionWindow, SQLDataType.INTERVALDAYTOSECOND)
                    dsl.select(DSL.extract(interval, DatePart.EPOCH).cast(SQLDataType.BIGINT)).fetchOne()?.value1()
                }
            }
        checkNotNull(window) { "the job-retention row is missing: run the service's migration (INV-Q-4346)" }
        check(Duration.ofSeconds(window) == retention) {
            "the configured job retention $retention differs from the migration-written row (${Duration.ofSeconds(
                window,
            )})"
        }
    }

    /**
     * Deletes `job_run` rows that started, and `job_pause` rows that ended or ran out, before the migration-written
     * retention window (T30 slot answer retention), through the `<svc>_sys` purge function in batches, each its own
     * transaction under the service's workload principal. Returns the number of rows deleted; with no retention row
     * nothing is deleted and an error is logged.
     */
    fun purge(): Int {
        var total = 0
        do {
            val deleted =
                scope.bindWorkload(TenantId.PLATFORM) {
                    scope.transactions.inTransaction {
                        dsl
                            .select(DSL.function(purgeExpired, SQLDataType.BIGINT, DSL.`val`(PURGE_BATCH)))
                            .fetchOne()
                            ?.value1()
                    }
                }
            if (deleted == null) {
                log.error("job retention row is missing in the service's schema: nothing purged (INV-Q-4346)")
                return total
            }
            total += deleted.toInt()
        } while (deleted == PURGE_BATCH.toLong())
        return total
    }

    private companion object {
        const val PURGE_BATCH = 1000
        val log: Logger = LoggerFactory.getLogger(JobRuntime::class.java)
    }
}
