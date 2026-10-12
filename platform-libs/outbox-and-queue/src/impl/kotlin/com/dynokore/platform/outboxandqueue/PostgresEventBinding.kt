package com.dynokore.platform.outboxandqueue

import java.sql.SQLException

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
) : EventBinding {
    private val json = EnvelopeJson()

    override fun publish(
        topic: String,
        envelope: Envelope,
    ) {
        val topicTable = "${EventLogSchema.SCHEMA}.${EventLogSchema.tableName(topic)}"
        val body = json.write(envelope)
        try {
            eventLog.bindWorkload(envelope.tenant) {
                eventLog.transactions.inTransaction {
                    val connection = eventLog.connections.require()
                    connection.prepareStatement("select pg_advisory_xact_lock(hashtext(?))").use {
                        it.setString(1, topicTable)
                        it.execute()
                    }
                    connection
                        .prepareStatement(
                            "insert into $topicTable (tenant_id, event_id, subject, envelope) values (?, ?, ?, ?::jsonb)",
                        ).use {
                            it.setObject(1, envelope.tenant.value)
                            it.setString(2, envelope.id.value)
                            it.setString(3, envelope.subject)
                            it.setString(4, body)
                            it.executeUpdate()
                        }
                }
            }
        } catch (failure: SQLException) {
            throw EventBindingUnavailable("event log refused ${envelope.type}: ${failure.sqlState}", failure)
        }
    }
}
