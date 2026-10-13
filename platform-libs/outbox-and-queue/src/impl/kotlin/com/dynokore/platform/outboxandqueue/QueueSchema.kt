package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantRlsMigration

/**
 * DDL for the partition-ordered queue (INV-REQ-540, INV-REQ-1072): one `work_queue` table in a service's own
 * schema. It is tenant data, so it carries the mandatory `tenant_id` and tenancy-kernel's forced row-level
 * security keyed on `app.tenant_id`; the runtime login `<svc>_runtime` enqueues, reads the payload, completes and
 * releases under that tenant policy only. Finding and claiming work across tenants is the NOLOGIN `<svc>_sys`'s,
 * reached only through its SECURITY DEFINER function [CLAIM_DUE] (static SQL, `SET search_path = pg_catalog,
 * <schema>, pg_temp`, EXECUTE for the runtime login only; owner rulings INV-Q-3951, INV-Q-3953, INV-ADR-3968): it returns the
 * claim columns of one claimed item, never its payload, and takes its partition lock with
 * `pg_try_advisory_xact_lock`, so nothing outlives the claim transaction. Finished items are deleted, so every row
 * is unfinished: `PENDING`, or `CLAIMED` by a worker that has not completed it.
 * A service's migration (TPL-12) embeds [ddl] beside [OutboxSchema.ddl].
 *
 * [LANE_RATE_WINDOW] holds each lane's shared rate-limit count ([LaneRateLimiter], INV-Q-2966): lane, window start and
 * permits used. Limits are per lane, not per tenant, so it holds no tenant data and has no `tenant_id` and no RLS
 * (a documented CS-19 exception, as for `consumer_offset`); the runtime login writes it directly. Opening a lane's
 * next window replaces the row of its ended window, so nothing accumulates.
 */
object QueueSchema {
    const val WORK_QUEUE = "work_queue"
    const val LANE_RATE_WINDOW = "lane_rate_window"

    /**
     * `claim_due(lane, consumer, claimed_at, reclaim_before)`: claims the oldest claimable item of [lane] for
     * `consumer` (an item is claimable when `PENDING`, or `CLAIMED` before `reclaim_before`, and the oldest of its
     * (tenant, partition key, lane, consumer)); returns its (`claimed_seq`, `claimed_tenant`, `claimed_partition`), or
     * no row.
     */
    const val CLAIM_DUE = "claim_due"

    /**
     * The lane names the `lane` checks and [CLAIM_DUE] accept, as SQL string literals: a constant, so no lane name is
     * interpolated into SQL text (INV-Q-4036); [Lane] and this list must agree, checked on first use.
     */
    private const val LANE_VALUES = "'DEADLINE_NEAR', 'LIVE', 'REPROCESSING', 'BACKFILL', 'REPLAY'"

    init {
        check(LANE_VALUES == Lane.entries.joinToString(", ") { "'${it.name}'" }) {
            "QueueSchema.LANE_VALUES must list every Lane in order"
        }
    }

    fun ddl(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        require(runtime != sys) { "the runtime login and the sys role must differ, both were '$runtime'" }
        val table =
            """
            create table $s.$WORK_QUEUE (
                seq bigint generated always as identity primary key,
                tenant_id uuid not null,
                partition_key text not null,
                lane text not null check (lane in ($LANE_VALUES)),
                consumer text,
                payload jsonb not null,
                state text not null default 'PENDING' check (state in ('PENDING', 'CLAIMED')),
                attempts int not null default 0,
                enqueued_at timestamptz not null default now(),
                claimed_at timestamptz,
                source_dead_letter_id bigint
            );
            create index ${WORK_QUEUE}_partition on $s.$WORK_QUEUE (tenant_id, partition_key, lane, seq);
            create index ${WORK_QUEUE}_lane on $s.$WORK_QUEUE (lane, seq);
            create unique index ${WORK_QUEUE}_replay_once on $s.$WORK_QUEUE (source_dead_letter_id)
                where source_dead_letter_id is not null;
            comment on column $s.$WORK_QUEUE.payload is 'class: content';
            """.trimIndent()
        val grants =
            """
            grant usage on schema $s to $runtime;
            grant select, insert, update, delete on $s.$WORK_QUEUE to $runtime;
            grant usage on schema $s to $sys;
            create policy ${WORK_QUEUE}_sys_select on $s.$WORK_QUEUE for select to $sys using (true);
            create policy ${WORK_QUEUE}_sys_claim on $s.$WORK_QUEUE for update to $sys using (true) with check (true);
            grant select (seq, tenant_id, partition_key, lane, consumer, state, attempts, claimed_at)
                on $s.$WORK_QUEUE to $sys;
            grant update (state, attempts, claimed_at) on $s.$WORK_QUEUE to $sys;
            """.trimIndent()
        // Statement groups joined outside SQL text: tenancy-kernel's RLS DDL is not spliced into this module's SQL.
        return listOf(
            table,
            TenantRlsMigration.ddl(s, WORK_QUEUE, policyName = "${WORK_QUEUE}_tenant_isolation"),
            grants,
            claimDue(schema, runtimeRole, sysRole),
            laneRateWindow(schema, runtimeRole),
        ).joinToString("\n")
    }

    /**
     * The claim, as [JdbcWorkQueue] ran it on the former worker connection: candidates `FOR UPDATE SKIP LOCKED`, a
     * transaction advisory lock on the candidate's partition, a re-check under it, then the claim. Handed to the sys
     * role, EXECUTE for the runtime login only.
     */
    private fun claimDue(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.$CLAIM_DUE(p_lane text, p_consumer text, p_claimed_at timestamptz,
                p_reclaim_before timestamptz)
            returns table (claimed_seq bigint, claimed_tenant uuid, claimed_partition text)
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                c record;
            begin
                if p_lane is null or p_lane not in ($LANE_VALUES) or p_claimed_at is null or p_reclaim_before is null then
                    raise exception 'a claim names a lane, its time and the reclaim time' using errcode = '22023';
                end if;
                for c in
                    select q.seq, q.tenant_id, q.partition_key from $s.$WORK_QUEUE q
                    where q.lane = p_lane and q.consumer is not distinct from p_consumer
                      and (q.state = 'PENDING' or (q.state = 'CLAIMED' and q.claimed_at < p_reclaim_before))
                      and not exists (
                          select 1 from $s.$WORK_QUEUE p
                          where p.tenant_id = q.tenant_id and p.partition_key = q.partition_key and p.lane = q.lane
                            and p.consumer is not distinct from q.consumer and p.seq < q.seq)
                    order by q.seq limit $CANDIDATES for update of q skip locked
                loop
                    continue when not pg_try_advisory_xact_lock(hashtextextended(
                        '$s.$WORK_QUEUE|' || c.tenant_id || '|' || c.partition_key || '|' || p_lane || '|' ||
                        coalesce(p_consumer, ''), 0));
                    perform 1 from $s.$WORK_QUEUE q
                    where q.seq = c.seq
                      and (q.state = 'PENDING' or (q.state = 'CLAIMED' and q.claimed_at < p_reclaim_before))
                      and not exists (
                          select 1 from $s.$WORK_QUEUE p
                          where p.tenant_id = q.tenant_id and p.partition_key = q.partition_key and p.lane = q.lane
                            and p.consumer is not distinct from q.consumer and p.seq < q.seq)
                    for update of q skip locked;
                    continue when not found;
                    update $s.$WORK_QUEUE set state = 'CLAIMED', claimed_at = p_claimed_at, attempts = attempts + 1
                    where seq = c.seq;
                    claimed_seq := c.seq;
                    claimed_tenant := c.tenant_id;
                    claimed_partition := c.partition_key;
                    return next;
                    return;
                end loop;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$CLAIM_DUE(text, text, timestamptz, timestamptz) owner to $sys;
            revoke all on function $s.$CLAIM_DUE(text, text, timestamptz, timestamptz) from public;
            grant execute on function $s.$CLAIM_DUE(text, text, timestamptz, timestamptz) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    private const val CANDIDATES = 20

    private fun laneRateWindow(
        schema: String,
        runtimeRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        return """
            create table if not exists $s.$LANE_RATE_WINDOW (
                lane text not null check (lane in ($LANE_VALUES)),
                window_start timestamptz not null,
                used int not null check (used >= 0),
                primary key (lane)
            );
            comment on table $s.$LANE_RATE_WINDOW is
                'Shared per-lane rate-limit windows (no tenant data; CS-19 exception, as consumer_offset)';
            grant usage on schema $s to $runtime;
            grant select, insert, update, delete on $s.$LANE_RATE_WINDOW to $runtime;
            """.trimIndent()
    }
}
