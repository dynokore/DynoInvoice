package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.CALL_RECORD
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.CONNECTION_STATE
import com.dynokore.gst.masterdata.application.connection.CallRecords
import com.dynokore.gst.masterdata.application.connection.ConnectionStateMemory
import com.dynokore.gst.masterdata.domain.connection.CallFailure
import com.dynokore.gst.masterdata.domain.connection.CallRecord
import com.dynokore.gst.masterdata.domain.connection.ConnectionState
import com.dynokore.gst.masterdata.domain.connection.LastFailure
import com.dynokore.gst.masterdata.domain.connection.TestClass
import com.dynokore.platform.outboxandqueue.UuidV7
import org.jooq.DSLContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * TPL-11. The recorded calls of the bound tenant's connections (INV-REQ-836). A call is inserted once and never
 * changed; the failure is a class and a code, never raw error text (INV-Q-2944). The tenant column is added by the
 * tenancy-jooq listeners and row level security is forced behind them.
 */
class JooqCallRecords(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : CallRecords {
    /** Appends one recorded call of [connectionRef]. */
    fun record(
        connectionRef: String,
        call: CallRecord,
    ) {
        dsl
            .insertInto(CALL_RECORD)
            .set(CALL_RECORD.ID, ids.next(clock.instant()))
            .set(CALL_RECORD.RECORD_REF, connectionRef)
            .set(CALL_RECORD.CALLED_AT, call.at.atOffset(ZoneOffset.UTC))
            .set(CALL_RECORD.LATENCY_MILLIS, call.latency.toMillis())
            .set(CALL_RECORD.FAILURE_CLASS, call.failure?.classification?.name)
            .set(CALL_RECORD.FAILURE_DETAIL, call.failure?.detail)
            .execute()
    }

    override fun between(
        connectionRef: String,
        from: Instant,
        to: Instant,
    ): List<CallRecord> =
        dsl
            .selectFrom(CALL_RECORD)
            .where(
                CALL_RECORD.RECORD_REF.eq(connectionRef),
                CALL_RECORD.CALLED_AT.ge(from.atOffset(ZoneOffset.UTC)),
                CALL_RECORD.CALLED_AT.le(to.atOffset(ZoneOffset.UTC)),
            ).orderBy(CALL_RECORD.CALLED_AT.asc(), CALL_RECORD.ID.asc())
            .fetch()
            .map { call(it) }

    override fun lastFailure(connectionRef: String): LastFailure? =
        dsl
            .selectFrom(CALL_RECORD)
            .where(CALL_RECORD.RECORD_REF.eq(connectionRef), CALL_RECORD.FAILURE_CLASS.isNotNull)
            .orderBy(CALL_RECORD.CALLED_AT.desc(), CALL_RECORD.ID.desc())
            .limit(1)
            .fetchOne()
            ?.let { row -> call(row).let { LastFailure(it.at, checkNotNull(it.failure)) } }

    override fun hasAny(connectionRef: String): Boolean =
        dsl.fetchExists(dsl.selectOne().from(CALL_RECORD).where(CALL_RECORD.RECORD_REF.eq(connectionRef)))

    private fun call(row: org.jooq.Record): CallRecord =
        CallRecord(
            at = checkNotNull(row.get(CALL_RECORD.CALLED_AT)).toInstant(),
            latency = Duration.ofMillis(checkNotNull(row.get(CALL_RECORD.LATENCY_MILLIS))),
            failure =
                row.get(CALL_RECORD.FAILURE_CLASS)?.let {
                    CallFailure(TestClass.valueOf(it), row.get(CALL_RECORD.FAILURE_DETAIL))
                },
        )
}

/** TPL-11. The state last requested as `ConnectionStatusChanged` for each connection of the bound tenant. */
class JooqConnectionStateMemory(
    private val dsl: DSLContext,
    private val clock: Clock,
) : ConnectionStateMemory {
    override fun lastRequested(connectionRef: String): ConnectionState? =
        dsl
            .select(CONNECTION_STATE.STATE)
            .from(CONNECTION_STATE)
            .where(CONNECTION_STATE.RECORD_REF.eq(connectionRef))
            .fetchOne(CONNECTION_STATE.STATE)
            ?.let(ConnectionState::valueOf)

    override fun remember(
        connectionRef: String,
        state: ConnectionState,
    ) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(CONNECTION_STATE)
            .set(CONNECTION_STATE.RECORD_REF, connectionRef)
            .set(CONNECTION_STATE.STATE, state.name)
            .set(CONNECTION_STATE.UPDATED_AT, now)
            .onConflict(CONNECTION_STATE.TENANT_ID, CONNECTION_STATE.RECORD_REF)
            .doUpdate()
            .set(CONNECTION_STATE.STATE, state.name)
            .set(CONNECTION_STATE.UPDATED_AT, now)
            .execute()
    }
}
