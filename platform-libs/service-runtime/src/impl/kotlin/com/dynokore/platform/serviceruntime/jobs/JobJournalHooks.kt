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

/** The request journal's two hooks inside a command transaction. */
internal object JobJournalHooks {
    /**
     * The first step of a command transaction, before any effect: the journal's [TransactionalJobCommandJournal.begin].
     */
    fun begin(request: JobCommandRequest) {
        (request.journal as? TransactionalJobCommandJournal)?.begin()
    }

    /** Hands a done outcome to the request's journal inside the command transaction (ARC-05, TPL-04). */
    fun <T> journal(
        request: JobCommandRequest,
        outcome: JobCommandOutcome<T>,
    ): JobCommandOutcome<T> {
        if (outcome is JobCommandOutcome.Done) request.journal?.withinTransaction(outcome)
        return outcome
    }
}
