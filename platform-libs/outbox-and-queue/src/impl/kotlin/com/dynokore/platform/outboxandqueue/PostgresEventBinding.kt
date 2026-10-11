package com.dynokore.platform.outboxandqueue

import java.sql.SQLException
import javax.sql.DataSource

/**
 * [EventBinding] over the PostgreSQL event log (INV-REQ-1212; the binding in both forms).
 *
 * Each publish is its own short transaction that first takes a transaction-scoped advisory lock
 * on the topic's table, so inserts into one topic are serialised and `log_offset` order equals
 * commit order: a reader that has seen offset N never later sees an offset below N appear
 * (S2-30's "exactly one relay inserts serially" option, enforced in the database).
 *
 * The topic tables are under forced tenant row-level security ([EventLogSchema], INV-REQ-854, Keel INV-Q-3251):
 * the transaction binds the envelope's tenant (`set_config('app.tenant_id', …, true)`, transaction-local as
 * ARC-21 requires; `TenantId.PLATFORM` for a platform event, INV-Q-2927) and inserts the row under that tenant.
 * The relay role holds INSERT only, so a row whose tenant differs from the bound one is refused by the database.
 */
class PostgresEventBinding(
    private val dataSource: DataSource,
) : EventBinding {
    private val json = EnvelopeJson()

    override fun publish(
        topic: String,
        envelope: Envelope,
    ) {
        val table = "${EventLogSchema.SCHEMA}.${EventLogSchema.tableName(topic)}"
        val body = json.write(envelope)
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                connection.prepareStatement("select pg_advisory_xact_lock(hashtext(?))").use {
                    it.setString(1, table)
                    it.execute()
                }
                connection.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                    it.setString(1, envelope.tenant.toString())
                    it.execute()
                }
                connection.prepareStatement(
                    "insert into $table (tenant_id, event_id, subject, envelope) values (?, ?, ?, ?::jsonb)",
                ).use {
                    it.setObject(1, envelope.tenant.value)
                    it.setString(2, envelope.id.value)
                    it.setString(3, envelope.subject)
                    it.setString(4, body)
                    it.executeUpdate()
                }
                connection.commit()
            }
        } catch (failure: SQLException) {
            throw EventBindingUnavailable("event log refused ${envelope.type}: ${failure.sqlState}", failure)
        }
    }
}
