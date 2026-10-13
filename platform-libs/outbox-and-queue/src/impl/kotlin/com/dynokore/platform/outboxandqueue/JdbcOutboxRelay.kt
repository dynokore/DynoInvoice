package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/**
 * The per-service relay (INV-REQ-1212): reads the service's own outbox in `seq` order and
 * publishes each row to the [EventBinding], copying the stored envelope unchanged, so a
 * re-published row carries the same id (TPL-05, S2-01).
 *
 * - **Cross-tenant claim (ARC 4.3 step 1a; INV-Q-3953).** The outbox is under tenancy-kernel's forced
 *   row-level security. The relay finds work on [claimSource], a connection of the service's runtime login,
 *   through the `<svc>_sys`-owned view [OutboxSchema.OUTBOX_CLAIM_VIEW], which exposes the `seq`, `tenant_id`
 *   and `published_at` columns only; never `BYPASSRLS`. For each claimed row it binds that row's tenant
 *   ([TenantContext.bind], ARC-21) and reads, publishes and marks it in a normal tenant transaction.
 * - **Order per key.** `seq` is assigned after the writer has locked the key's counter row
 *   ([JdbcOutboxWriter]), so for one aggregate key `seq` order is commit order. The relay
 *   publishes strictly in `seq` order and stops at the first row the binding refuses, so no
 *   later row of any key overtakes it (INV-REQ-1221).
 * - **One relay at a time.** A transaction advisory lock (`pg_try_advisory_xact_lock`) taken in the claim
 *   connection's transaction, which spans the run, makes a second instance a no-op rather than a source of
 *   interleaved publishing. The lock ends with that transaction, so no pooled connection keeps it and the relay
 *   works behind a transaction-mode pooler (INV-ADR-3968 item 9).
 * - **Outage.** Producers only write the outbox, never call the binding, so they keep
 *   committing while it is down (INV-AC-2185); rows stay unpublished and the next run catches up.
 * - **At-least-once.** A crash after publish and before the `published_at` commit publishes
 *   the row again (INV-REQ-1164); consumers de-duplicate on the id.
 *
 * The publish happens inside the row's own tenant transaction: the event log is the platform's
 * own PostgreSQL, the transaction holds only that unpublished row, and nothing of a command's state.
 * This is the one bounded exception to "no remote call in a transaction" (CS-17).
 */
class JdbcOutboxRelay(
    private val claimSource: DataSource,
    private val scope: TenantScope,
    schema: String,
    private val binding: EventBinding,
    private val clock: Clock,
    private val batchSize: Int = DEFAULT_BATCH,
) : OutboxRelay {
    private val outbox = "${SqlIdentifier.require(schema, "schema")}.${OutboxSchema.OUTBOX}"
    private val claimView = "${SqlIdentifier.require(schema, "schema")}.${OutboxSchema.OUTBOX_CLAIM_VIEW}"

    init {
        require(batchSize in 1..MAX_BATCH) { "batch size must be 1..$MAX_BATCH, was $batchSize" }
    }

    override fun runOnce(): RelayOutcome =
        claimSource.connection.use { claim ->
            val wasAutoCommit = claim.autoCommit
            claim.autoCommit = false
            try {
                if (tryLock(
                        claim,
                    )
                ) {
                    publishBatch(claim)
                } else {
                    RelayOutcome(published = 0, pending = pending(claim), stalled = false)
                }
            } finally {
                claim.rollback() // ends the claim transaction and with it the lock; it wrote nothing
                claim.autoCommit = wasAutoCommit
            }
        }

    private fun publishBatch(claim: Connection): RelayOutcome {
        var published = 0
        var stalled = false
        for (item in claimBatch(claim)) {
            try {
                if (publishOne(item)) published++
            } catch (_: EventBindingUnavailable) {
                // CS-14/CS-15: an infrastructure failure the relay is built to absorb; the row stays unpublished.
                stalled = true
                break
            }
        }
        return RelayOutcome(published, pending(claim), stalled)
    }

    private fun publishOne(item: Claimed): Boolean =
        scope.bindWorkload(item.tenant) {
            scope.transactions.inTransaction {
                val row = read(item.seq) ?: return@inTransaction false
                binding.publish(row.topic, row.envelope)
                markPublished(item.seq)
                true
            }
        }

    private fun tryLock(claim: Connection): Boolean =
        claim.prepareStatement("select pg_try_advisory_xact_lock(hashtext(?))").use { statement ->
            statement.setString(1, outbox)
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }

    private fun claimBatch(claim: Connection): List<Claimed> =
        claim
            .prepareStatement(
                "select seq, tenant_id from $claimView where published_at is null order by seq limit ?",
            ).use {
                it.setInt(1, batchSize)
                it.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                Claimed(rows.getLong(1), TenantId(rows.getObject(2, UUID::class.java))),
                            )
                        }
                    }
                }
            }

    private fun read(seq: Long): Row? =
        scope.connections
            .require()
            .prepareStatement(
                """
                select topic, id, type, source, subject, event_time, tenant_id, entity_ref, doc_uuid,
                       transaction_date, docsequence, instance_id, correlation_id, causation_id, data::text
                from $outbox where seq = ? and published_at is null
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, seq)
                statement.executeQuery().use { rows -> if (rows.next()) row(rows) else null }
            }

    private fun row(rows: ResultSet): Row =
        Row(
            topic = rows.getString("topic"),
            envelope =
                Envelope(
                    id = EventId(rows.getString("id")),
                    source = rows.getString("source"),
                    type = rows.getString("type"),
                    subject = rows.getString("subject"),
                    time = rows.getTimestamp("event_time").toInstant(),
                    tenant = TenantId(rows.getObject("tenant_id", UUID::class.java)),
                    entity = EntityRef(rows.getString("entity_ref")),
                    docUuid = rows.getObject("doc_uuid", UUID::class.java)?.let { DocUuid(it) },
                    transactionDate = rows.getObject("transaction_date", java.time.LocalDate::class.java),
                    docSequence = rows.getObject("docsequence", Long::class.javaObjectType),
                    instanceId = rows.getString("instance_id"),
                    correlation = CorrelationId(rows.getString("correlation_id")),
                    causation = CausationId(rows.getString("causation_id")),
                    dataJson = rows.getString("data"),
                ),
        )

    private fun markPublished(seq: Long) {
        scope.connections
            .require()
            .prepareStatement("update $outbox set published_at = ? where seq = ?")
            .use { statement ->
                statement.setTimestamp(1, Timestamp.from(clock.instant()))
                statement.setLong(2, seq)
                statement.executeUpdate()
            }
    }

    private fun pending(claim: Connection): Long =
        claim.prepareStatement("select count(*) from $claimView where published_at is null").use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next()) { "count returned no row" }
                rows.getLong(1)
            }
        }

    private class Claimed(
        val seq: Long,
        val tenant: TenantId,
    )

    private class Row(
        val topic: String,
        val envelope: Envelope,
    )

    private companion object {
        const val DEFAULT_BATCH = 200
        const val MAX_BATCH = 5000
    }
}
