package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantRlsMigration

/**
 * DDL for the partition-ordered queue (INV-REQ-540, INV-REQ-1072): one `work_queue` table in a service's own
 * schema. It is tenant data, so it carries the mandatory `tenant_id` and tenancy-kernel's forced row-level
 * security. A worker finds and claims work across tenants through the claim path of ARC 4.3 step 1a: policies
 * `TO <worker>` and column grants limited to the claim columns (no payload), never `BYPASSRLS`. Finished items
 * are deleted, so every row is unfinished: `PENDING`, or `CLAIMED` by a worker that has not completed it.
 * A service's migration (TPL-12) embeds [ddl] beside [OutboxSchema.ddl].
 *
 * [LANE_RATE_WINDOW] holds each lane's shared rate-limit count ([LaneRateLimiter], INV-Q-2966): lane, window start and
 * permits used. Limits are per lane, not per tenant, so it holds no tenant data and has no `tenant_id` and no RLS
 * (a documented CS-19 exception, as for `consumer_offset`). Only the worker role is granted access to it, never
 * `BYPASSRLS`. The limiter deletes the rows of ended windows as it advances, so nothing accumulates.
 */
object QueueSchema {
    const val WORK_QUEUE = "work_queue"
    const val LANE_RATE_WINDOW = "lane_rate_window"

    fun ddl(
        schema: String,
        appRole: String,
        workerRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val role = SqlIdentifier.require(appRole, "role")
        val worker = SqlIdentifier.require(workerRole, "worker role")
        val lanes = Lane.entries.joinToString(", ") { "'${it.name}'" }
        val rls = TenantRlsMigration.ddl(s, WORK_QUEUE, policyName = "${WORK_QUEUE}_tenant_isolation")
        return """
            create table $s.$WORK_QUEUE (
                seq bigint generated always as identity primary key,
                tenant_id uuid not null,
                partition_key text not null,
                lane text not null check (lane in ($lanes)),
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
            $rls
            create policy ${WORK_QUEUE}_claim_select on $s.$WORK_QUEUE for select to $worker using (true);
            create policy ${WORK_QUEUE}_claim_update on $s.$WORK_QUEUE for update to $worker
                using (true) with check (true);
            grant usage on schema $s to $worker;
            grant select (seq, tenant_id, partition_key, lane, consumer, state, attempts, claimed_at)
                on $s.$WORK_QUEUE to $worker;
            grant update (state, attempts, claimed_at) on $s.$WORK_QUEUE to $worker;
            grant select, insert, update, delete on $s.$WORK_QUEUE to $role;
        """.trimIndent() + "\n" + laneRateWindow(s, worker)
    }

    /**
     * Brings a schema of 1.6.0 or earlier to 1.7.0: the shared per-lane rate-limit table [LANE_RATE_WINDOW] and the
     * worker's grants on it (additive; harmless to run again). Run once by the service's migration (TPL-12) before the
     * 1.7.0 [LaneRateLimiter] starts; not needed for a schema created by [ddl].
     */
    fun upgradeTo170(
        schema: String,
        workerRole: String,
    ): String = laneRateWindow(SqlIdentifier.require(schema, "schema"), SqlIdentifier.require(workerRole, "worker role"))

    private fun laneRateWindow(
        s: String,
        worker: String,
    ): String {
        val lanes = Lane.entries.joinToString(", ") { "'${it.name}'" }
        return """
            create table if not exists $s.$LANE_RATE_WINDOW (
                lane text not null check (lane in ($lanes)),
                window_start timestamptz not null,
                used int not null check (used >= 0),
                primary key (lane, window_start)
            );
            comment on table $s.$LANE_RATE_WINDOW is
                'Shared per-lane rate-limit windows (no tenant data; CS-19 exception, as consumer_offset)';
            grant usage on schema $s to $worker;
            grant select, insert, update, delete on $s.$LANE_RATE_WINDOW to $worker;
        """.trimIndent()
    }
}
