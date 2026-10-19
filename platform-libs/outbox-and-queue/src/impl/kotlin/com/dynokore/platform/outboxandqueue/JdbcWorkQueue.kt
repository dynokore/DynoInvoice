package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

private const val DEFAULT_CLAIM_TIMEOUT_MINUTES = 5L

/**
 * [WorkQueue] over the `work_queue` table of the service's schema ([QueueSchema]).
 *
 * - **Priority.** [claimNext] walks [Lane.entries] in order and serves a lane only when every earlier lane
 *   had nothing claimable. A lane whose own rate limit is used up is skipped, so it neither blocks nor
 *   is served: the lanes below it may use their own allowance (INV-REQ-1072). The allowance is shared by every
 *   replica of the service ([LaneRateLimiter], INV-Q-2966).
 * - **Partition order.** An item is claimable only if no earlier item of its (tenant, partition key, lane,
 *   consumer) is still in the table, claimed or not. Within a lane, a document's items are therefore
 *   handled one at a time in enqueue order; across lanes the priority applies, so a live item never waits for
 *   an earlier backfill item of the same document.
 * - **SKIP LOCKED.** Candidates are selected `FOR UPDATE SKIP LOCKED`, so workers never queue behind each other.
 *   A candidate row lock alone cannot keep a later item of a partition from being claimed while the head's
 *   claim is still uncommitted, so each claim also takes a transaction advisory lock on its partition and
 *   re-checks eligibility under it. All of this runs inside the `<svc>_sys`-owned definer function
 *   [QueueSchema.CLAIM_DUE], in one short transaction.
 * - **Cross-tenant claim (ARC 4.3 step 1a; INV-Q-3953).** The claim is a jOOQ read of [QueueSchema.CLAIM_DUE] in its
 *   own transaction under the platform binding, which gets the claim columns only; the payload is read later in the
 *   item's own tenant transaction.
 * - **Crash.** A claim not completed within [claimTimeout] is claimed again (at-least-once; the handler is
 *   idempotent, INV-REQ-1164).
 */
@Suppress("LongParameterList") // constructor injection under CS-05; INV-Q-4629 (CS-50)
class JdbcWorkQueue(
    private val scope: TenantScope,
    private val dsl: DSLContext,
    schema: String,
    private val limiter: LaneRateLimiter,
    private val clock: Clock,
    private val consumer: String? = null,
    private val claimTimeout: Duration = Duration.ofMinutes(DEFAULT_CLAIM_TIMEOUT_MINUTES),
) : WorkQueue {
    private val schemaName = SqlIdentifier.require(schema, "schema")
    private val queue: Table<*> = DSL.table(DSL.name(schemaName, QueueSchema.WORK_QUEUE))

    override fun enqueue(entry: QueueEntry): Long {
        val tenant = scope.tenants.current()
        val seq =
            dsl
                .insertInto(queue)
                .columns(TENANT_ID, PARTITION_KEY, LANE, CONSUMER, PAYLOAD, ENQUEUED_AT)
                .values(
                    DSL.`val`(tenant.value, SQLDataType.UUID),
                    DSL.`val`(entry.partitionKey, SQLDataType.CLOB),
                    DSL.`val`(entry.lane.name, SQLDataType.CLOB),
                    DSL.`val`(entry.consumer, SQLDataType.CLOB),
                    DSL.`val`(entry.payloadJson, SQLDataType.JSONB),
                    DSL.`val`(now(), SQLDataType.TIMESTAMPWITHTIMEZONE),
                ).returning(SEQ)
                .fetchOne()
        return checkNotNull(seq) { "enqueue returned no row" }.get(SEQ)
    }

    override fun claimNext(): ClaimedItem? {
        for (lane in Lane.entries) {
            val permit = limiter.acquire(lane) ?: continue
            val item = claimInLane(lane)
            if (item != null) return item
            limiter.refund(permit) // nothing claimable: no AP call will follow, so no permit is spent
        }
        return null
    }

    override fun payloadOf(item: ClaimedItem): String =
        checkNotNull(
            dsl
                .select(PAYLOAD_TEXT)
                .from(queue)
                .where(SEQ.eq(item.seq))
                .fetchOne(),
        ) {
            "queue item ${item.seq} is not visible to this tenant"
        }.get(PAYLOAD_TEXT)

    override fun complete(item: ClaimedItem) {
        dsl.deleteFrom(queue).where(SEQ.eq(item.seq)).execute()
    }

    override fun release(item: ClaimedItem) {
        dsl
            .update(queue)
            .set(STATE, "PENDING")
            .setNull(CLAIMED_AT)
            .where(SEQ.eq(item.seq))
            .execute()
    }

    /** The claim, in its own short transaction under the platform binding: its commit ends the partition lock. */
    private fun claimInLane(lane: Lane): ClaimedItem? =
        scope.platformTransaction {
            val claim = ClaimDue(schemaName).call(lane.name, consumer, now(), now().minus(claimTimeout))
            dsl
                .select(claim.claimedSeq, claim.claimedTenant, claim.claimedPartition)
                .from(claim)
                .fetchOne()
                ?.let {
                    ClaimedItem(
                        it.get(claim.claimedSeq),
                        TenantId(it.get(claim.claimedTenant)),
                        it.get(claim.claimedPartition),
                        lane,
                    )
                }
        }

    private fun now(): OffsetDateTime = clock.instant().atOffset(ZoneOffset.UTC)

    private companion object {
        val SEQ = DSL.field(DSL.name("seq"), SQLDataType.BIGINT)
        val TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
        val PARTITION_KEY = DSL.field(DSL.name("partition_key"), SQLDataType.CLOB)
        val LANE = DSL.field(DSL.name("lane"), SQLDataType.CLOB)
        val CONSUMER = DSL.field(DSL.name("consumer"), SQLDataType.CLOB)
        val PAYLOAD = DSL.field(DSL.name("payload"), SQLDataType.JSONB)
        val PAYLOAD_TEXT = PAYLOAD.cast(SQLDataType.CLOB)
        val STATE = DSL.field(DSL.name("state"), SQLDataType.CLOB)
        val ENQUEUED_AT = DSL.field(DSL.name("enqueued_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        val CLAIMED_AT = DSL.field(DSL.name("claimed_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    }
}
