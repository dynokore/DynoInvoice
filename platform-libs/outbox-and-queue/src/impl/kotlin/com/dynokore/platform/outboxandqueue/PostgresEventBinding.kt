package com.dynokore.platform.outboxandqueue

import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * [EventBinding] over the PostgreSQL event log (INV-REQ-1212; the binding in both forms).
 *
 * Each publish is its own short transaction that first takes a transaction-scoped advisory lock
 * on the topic's table, so inserts into one topic are serialised and `log_offset` order equals
 * commit order: a reader that has seen offset N never later sees an offset below N appear
 * (S2-30's "exactly one relay inserts serially" option, enforced in the database).
 *
 * The topic tables are under forced tenant row-level security ([EventLogSchema], INV-REQ-854, Keel INV-Q-3251): the
 * transaction is opened through [eventLog], the TenantScope over the event-log DataSource, bound to the envelope's
 * tenant (`TenantId.PLATFORM` for a platform event, INV-Q-2927) and the service's workload principal, which tenancy-kernel
 * sets as its first statement (ARC-21). The service's runtime login holds INSERT only on its own producer topics, so a
 * row whose tenant differs from the bound one is refused by the database (INV-REQ-1205 b3).
 */
class PostgresEventBinding(
    private val eventLog: TenantScope,
    private val dsl: DSLContext,
) : EventBinding {
    private val json = EnvelopeJson()

    override fun publish(
        topic: String,
        envelope: Envelope,
    ) {
        val topicName = EventLogSchema.tableName(topic)
        val topicTable = DSL.table(DSL.name(EventLogSchema.SCHEMA, topicName))
        val body = json.write(envelope)
        try {
            eventLog.bindWorkload(envelope.tenant) {
                eventLog.transactions.inTransaction {
                    dsl.select(JooqRoutines.advisoryXactLock("${EventLogSchema.SCHEMA}.$topicName")).execute()
                    dsl
                        .insertInto(topicTable)
                        .set(TENANT_ID, envelope.tenant.value)
                        .set(EVENT_ID, envelope.id.value)
                        .set(SUBJECT, envelope.subject)
                        .set(ENVELOPE, DSL.`val`(body, SQLDataType.JSONB))
                        .execute()
                }
            }
        } catch (failure: Exception) {
            // jOOQ wraps the SQLException; the SQLSTATE is read through the runner (INV-Q-5121)
            val state = eventLog.sqlErrors.sqlStateOf(failure) ?: throw failure
            throw EventBindingUnavailable("event log refused ${envelope.type}: $state", failure)
        }
    }

    companion object {
        private val TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
        private val EVENT_ID = DSL.field(DSL.name("event_id"), SQLDataType.CLOB)
        private val SUBJECT = DSL.field(DSL.name("subject"), SQLDataType.CLOB)
        private val ENVELOPE = DSL.field(DSL.name("envelope"), SQLDataType.JSONB)
    }
}
