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
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** The tenant bindings of job work: a run binds the job's workload principal, a command the operator's `sub`. */
internal class JobScopes(
    private val scope: TenantScope,
    private val principal: WorkloadPrincipal,
) {
    /** A run of [job] (its firing, claim and completion): `svc:<svc>/job:<job>` (owner ruling INV-Q-4344 (b)). */
    fun <T> inRun(
        job: JobDefinition,
        target: JobTarget,
        block: () -> T,
    ): T = scope.bind(target.tenant, principal.job(job.name)) { scope.transactions.inTransaction(block) }

    /** An operator's command: bound to the operator's `sub` (INV-REQ-854 b1, INV-Q-4268); never the service's actor. */
    fun <T> inTenant(
        target: JobTarget,
        actor: String,
        block: () -> T,
    ): T = scope.bind(target.tenant, actor) { scope.transactions.inTransaction(block) }
}
