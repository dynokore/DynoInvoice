package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.LaneRefusedException
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * The per-service relay (INV-REQ-1212): reads the service's own outbox in `seq` order and
 * publishes each row to the [EventBinding], copying the stored envelope unchanged, so a
 * re-published row carries the same id (TPL-05, S2-01).
 *
 * - **Cross-tenant claim (ARC 4.3 step 1a; INV-Q-3953, INV-Q-5612).** The outbox is under tenancy-kernel's forced
 *   row-level security. The relay finds work through the `<svc>_sys`-owned function
 *   [OutboxSchema.OUTBOX_CLAIM_PAGE] (`seq`, `tenant_id` and `published_at` of the claim view's rows) and counts
 *   with [OutboxSchema.OUTBOX_CLAIM_PENDING], called as jOOQ routine calls under the platform binding; never
 *   `BYPASSRLS`. For each claimed row it binds that row's tenant ([TenantContext.bind], ARC-21) and reads,
 *   publishes and marks it in a normal tenant transaction.
 * - **Order per key.** `seq` is assigned after the writer has locked the key's counter row
 *   ([JdbcOutboxWriter]), so for one aggregate key `seq` order is commit order. The relay
 *   publishes strictly in `seq` order and stops at the first row the binding refuses, so no
 *   later row of any key overtakes it (INV-REQ-1221).
 * - **One relay at a time.** A transaction advisory lock (`pg_try_advisory_xact_lock`, a jOOQ function call) taken
 *   in the outer platform transaction, which spans the run, makes a second instance a no-op rather than a source
 *   of interleaved publishing. The claim read and each row's transaction run inside
 *   [com.dynokore.platform.tenancykernel.TransactionRunner.outsideOpenTransaction], each on its own connection
 *   (owner answer INV-Q-5609 (b)), so a relay thread holds at most 2 connections (INV-Q-4850, INV-Q-5251). The lock
 *   ends with the outer transaction, so no pooled connection keeps it and the relay works behind a
 *   transaction-mode pooler (INV-ADR-3968 item 9). The relay needs at least 2 permits in its [lane] and refuses to
 *   run with fewer (a [com.dynokore.platform.tenancykernel.LaneRefusedException] from the first `runOnce`).
 * - **Outage.** Producers only write the outbox, never call the binding, so they keep
 *   committing while it is down (INV-AC-2185); rows stay unpublished and the next run catches up.
 * - **At-least-once.** A crash after publish and before the `published_at` commit publishes
 *   the row again (INV-REQ-1164); consumers de-duplicate on the id.
 *
 * The publish happens inside the row's own tenant transaction: the event log is the platform's
 * own PostgreSQL, the transaction holds only that unpublished row, and nothing of a command's state.
 * This is the one bounded exception to "no remote call in a transaction" (CS-17).
 */
@Suppress("LongParameterList") // constructor injection under CS-05; INV-Q-4629 (CS-50)
class JdbcOutboxRelay(
    private val scope: TenantScope,
    private val dsl: DSLContext,
    schema: String,
    private val binding: EventBinding,
    private val clock: Clock,
    private val lane: String,
    private val laneSize: Int,
    private val batchSize: Int = DEFAULT_BATCH,
) : OutboxRelay {
    private val schemaName = SqlIdentifier.require(schema, "schema")
    private val outbox: Table<*> = DSL.table(DSL.name(schemaName, OutboxSchema.OUTBOX))
    private val lockKey = "$schemaName.${OutboxSchema.OUTBOX}"

    init {
        require(batchSize in 1..MAX_BATCH) { "batch size must be 1..$MAX_BATCH, was $batchSize" }
    }

    override fun runOnce(): RelayOutcome {
        // the outer transaction holds one connection and a row transaction a second one (INV-Q-5609 (b)); the refusal
        // is a LaneRefusedException so the event loop logs it as a lane message
        if (laneSize < MIN_LANE_SIZE) {
            throw LaneRefusedException(
                "the ${lane.uppercase()} lane has $laneSize permits, the relay needs at least $MIN_LANE_SIZE",
            )
        }
        return scope.bindWorkload(TenantId.PLATFORM) {
            scope.transactions.inTransaction {
                if (tryLock()) publishBatch() else RelayOutcome(published = 0, pending = pending(), stalled = false)
            }
        }
    }

    private fun publishBatch(): RelayOutcome {
        var published = 0
        var stalled = false
        for (item in claimBatch()) {
            try {
                if (publishOne(item)) published++
            } catch (_: EventBindingUnavailable) {
                // CS-14/CS-15: an infrastructure failure the relay is built to absorb; the row stays unpublished.
                stalled = true
                break
            }
        }
        return RelayOutcome(published, pending(), stalled)
    }

    /** Each row in its own transaction on a second connection, so the outer transaction keeps the lock. */
    private fun publishOne(item: Claimed): Boolean =
        scope.transactions.outsideOpenTransaction {
            scope.bindWorkload(item.tenant) {
                scope.transactions.inTransaction {
                    val row = read(item.seq) ?: return@inTransaction false
                    binding.publish(row.topic, row.envelope)
                    markPublished(item.seq)
                    true
                }
            }
        }

    private fun tryLock(): Boolean {
        val lock = JooqRoutines.tryAdvisoryXactLock(lockKey)
        return dsl.select(lock).fetchOne()?.get(lock) == true
    }

    private fun claimBatch(): List<Claimed> =
        platformRead {
            val page = OutboxClaimPage(schemaName).call(0, batchSize, true)
            dsl
                .select(
                    page.seq,
                    page.tenantId,
                ).from(page)
                .fetch { Claimed(it.get(page.seq), TenantId(it.get(page.tenantId))) }
        }

    private fun pending(): Long =
        platformRead {
            val count =
                DSL.function(DSL.name(schemaName, OutboxSchema.OUTBOX_CLAIM_PENDING), SQLDataType.BIGINT)
            checkNotNull(dsl.select(count).fetchOne()) { "count returned no row" }.get(count)
        }

    /** A claim read: its own transaction under the platform binding, on a second connection. */
    private fun <T> platformRead(block: () -> T): T =
        scope.transactions.outsideOpenTransaction {
            scope.bindWorkload(TenantId.PLATFORM) { scope.transactions.inTransaction(block) }
        }

    private fun read(seq: Long): Row? =
        dsl
            .select(TOPIC, ID, TYPE, SOURCE, SUBJECT, EVENT_TIME, TENANT_ID, ENTITY_REF, DOC_UUID)
            .select(TRANSACTION_DATE, DOCSEQUENCE, INSTANCE_ID, CORRELATION_ID, CAUSATION_ID, DATA_TEXT)
            .from(outbox)
            .where(SEQ.eq(seq).and(PUBLISHED_AT.isNull))
            .fetchOne()
            ?.let { row(it) }

    private fun row(rows: Record): Row =
        Row(
            topic = checkNotNull(rows.get(TOPIC)),
            envelope =
                Envelope(
                    id = EventId(checkNotNull(rows.get(ID))),
                    source = checkNotNull(rows.get(SOURCE)),
                    type = checkNotNull(rows.get(TYPE)),
                    subject = checkNotNull(rows.get(SUBJECT)),
                    time = checkNotNull(rows.get(EVENT_TIME)).toInstant(),
                    tenant = TenantId(checkNotNull(rows.get(TENANT_ID))),
                    entity = EntityRef(checkNotNull(rows.get(ENTITY_REF))),
                    docUuid = rows.get(DOC_UUID)?.let { DocUuid(it) },
                    transactionDate = rows.get(TRANSACTION_DATE),
                    docSequence = rows.get(DOCSEQUENCE),
                    instanceId = checkNotNull(rows.get(INSTANCE_ID)),
                    correlation = CorrelationId(checkNotNull(rows.get(CORRELATION_ID))),
                    causation = CausationId(checkNotNull(rows.get(CAUSATION_ID))),
                    dataJson = checkNotNull(rows.get(DATA_TEXT)),
                ),
        )

    private fun markPublished(seq: Long) {
        dsl
            .update(outbox)
            .set(PUBLISHED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .where(SEQ.eq(seq))
            .execute()
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
        const val MIN_LANE_SIZE = 2

        val SEQ = DSL.field(DSL.name("seq"), SQLDataType.BIGINT)
        val TOPIC = DSL.field(DSL.name("topic"), SQLDataType.CLOB)
        val ID = DSL.field(DSL.name("id"), SQLDataType.CLOB)
        val TYPE = DSL.field(DSL.name("type"), SQLDataType.CLOB)
        val SOURCE = DSL.field(DSL.name("source"), SQLDataType.CLOB)
        val SUBJECT = DSL.field(DSL.name("subject"), SQLDataType.CLOB)
        val EVENT_TIME = DSL.field(DSL.name("event_time"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        val TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
        val ENTITY_REF = DSL.field(DSL.name("entity_ref"), SQLDataType.CLOB)
        val DOC_UUID = DSL.field(DSL.name("doc_uuid"), SQLDataType.UUID)
        val TRANSACTION_DATE = DSL.field(DSL.name("transaction_date"), SQLDataType.LOCALDATE)
        val DOCSEQUENCE = DSL.field(DSL.name("docsequence"), SQLDataType.BIGINT)
        val INSTANCE_ID = DSL.field(DSL.name("instance_id"), SQLDataType.CLOB)
        val CORRELATION_ID = DSL.field(DSL.name("correlation_id"), SQLDataType.CLOB)
        val CAUSATION_ID = DSL.field(DSL.name("causation_id"), SQLDataType.CLOB)
        val DATA_TEXT = DSL.field(DSL.name("data"), SQLDataType.JSONB).cast(SQLDataType.CLOB)
        val PUBLISHED_AT = DSL.field(DSL.name("published_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    }
}
