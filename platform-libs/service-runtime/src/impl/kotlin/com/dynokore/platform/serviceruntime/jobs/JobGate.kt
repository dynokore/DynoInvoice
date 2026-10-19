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

/** One stage of a job command: carry on with a value, or stop with the refusal to answer. */
internal sealed interface JobGate<out T> {
    class Open<out T>(
        val value: T,
    ) : JobGate<T>

    class Closed(
        val refusal: JobCommandOutcome.Refused,
    ) : JobGate<Nothing>

    companion object {
        /** [value] when present, else the refusal with [code]. */
        fun <T : Any> of(
            value: T?,
            code: JobErrorCode,
        ): JobGate<T> = if (value == null) Closed(JobCommandOutcome.Refused(code)) else Open(value)
    }
}

internal inline fun <T, R> JobGate<T>.andThen(next: (T) -> JobGate<R>): JobGate<R> =
    when (this) {
        is JobGate.Open -> next(value)
        is JobGate.Closed -> this
    }
