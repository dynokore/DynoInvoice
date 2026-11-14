-- INV-T-22010: the queue tables and the claim function of the library (INV-AC-22009): work_queue, lane_rate_window and
-- console_bff.claim_due. The generator is self contained (it grants and revokes CREATE on the schema to the sys role)
-- and needs the binding functions installed by INV-T-21023. QueueSubstrateTest keeps the section between the markers
-- equal to QueueSchema.ddl.
-- BEGIN QueueSchema.ddl("console_bff", "console_bff_runtime", "console_bff_sys")
create table console_bff.work_queue (
    seq bigint generated always as identity primary key,
    tenant_id uuid not null,
    partition_key text not null,
    lane text not null check (lane in ('DEADLINE_NEAR', 'LIVE', 'REPROCESSING', 'BACKFILL', 'REPLAY')),
    consumer text,
    payload jsonb not null,
    state text not null default 'PENDING' check (state in ('PENDING', 'CLAIMED')),
    attempts int not null default 0,
    enqueued_at timestamptz not null default now(),
    claimed_at timestamptz,
    source_dead_letter_id bigint
);
create index work_queue_partition on console_bff.work_queue (tenant_id, partition_key, lane, seq);
create index work_queue_lane on console_bff.work_queue (lane, seq);
create unique index work_queue_replay_once on console_bff.work_queue (source_dead_letter_id)
    where source_dead_letter_id is not null;
comment on column console_bff.work_queue.payload is 'class: content';
ALTER TABLE console_bff.work_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE console_bff.work_queue FORCE ROW LEVEL SECURITY;
CREATE POLICY work_queue_tenant_isolation ON console_bff.work_queue
    USING      (tenant_id = (select console_bff.bound_tenant()))
    WITH CHECK (tenant_id = (select console_bff.bound_tenant()));
grant usage on schema console_bff to console_bff_runtime;
grant select, insert, update, delete on console_bff.work_queue to console_bff_runtime;
grant usage on schema console_bff to console_bff_sys;
create policy work_queue_sys_select on console_bff.work_queue for select to console_bff_sys using (true);
create policy work_queue_sys_claim on console_bff.work_queue for update to console_bff_sys using (true) with check (true);
grant select (seq, tenant_id, partition_key, lane, consumer, state, attempts, claimed_at)
    on console_bff.work_queue to console_bff_sys;
grant update (state, attempts, claimed_at) on console_bff.work_queue to console_bff_sys;
create function console_bff.claim_due(p_lane text, p_consumer text, p_claimed_at timestamptz,
    p_reclaim_before timestamptz)
returns table (claimed_seq bigint, claimed_tenant uuid, claimed_partition text)
language plpgsql security definer set search_path = pg_catalog, console_bff, pg_temp as $$
declare
    c record;
begin
    if p_lane is null or p_lane not in ('DEADLINE_NEAR', 'LIVE', 'REPROCESSING', 'BACKFILL', 'REPLAY') or p_claimed_at is null or p_reclaim_before is null then
        raise exception 'a claim names a lane, its time and the reclaim time' using errcode = '22023';
    end if;
    for c in
        select q.seq, q.tenant_id, q.partition_key from console_bff.work_queue q
        where q.lane = p_lane and q.consumer is not distinct from p_consumer
          and (q.state = 'PENDING' or (q.state = 'CLAIMED' and q.claimed_at < p_reclaim_before))
          and not exists (
              select 1 from console_bff.work_queue p
              where p.tenant_id = q.tenant_id and p.partition_key = q.partition_key and p.lane = q.lane
                and p.consumer is not distinct from q.consumer and p.seq < q.seq)
        order by q.seq limit 20
    loop
        continue when not pg_try_advisory_xact_lock(hashtextextended(
            'console_bff.work_queue|' || c.tenant_id || '|' || c.partition_key || '|' || p_lane || '|' ||
            coalesce(p_consumer, ''), 0));
        perform 1 from console_bff.work_queue q
        where q.seq = c.seq
          and (q.state = 'PENDING' or (q.state = 'CLAIMED' and q.claimed_at < p_reclaim_before))
          and not exists (
              select 1 from console_bff.work_queue p
              where p.tenant_id = q.tenant_id and p.partition_key = q.partition_key and p.lane = q.lane
                and p.consumer is not distinct from q.consumer and p.seq < q.seq)
        for update of q skip locked;
        continue when not found;
        update console_bff.work_queue set state = 'CLAIMED', claimed_at = p_claimed_at, attempts = attempts + 1
        where seq = c.seq;
        claimed_seq := c.seq;
        claimed_tenant := c.tenant_id;
        claimed_partition := c.partition_key;
        return next;
        return;
    end loop;
end
$$;
grant create on schema console_bff to console_bff_sys;
alter function console_bff.claim_due(text, text, timestamptz, timestamptz) owner to console_bff_sys;
revoke all on function console_bff.claim_due(text, text, timestamptz, timestamptz) from public;
grant execute on function console_bff.claim_due(text, text, timestamptz, timestamptz) to console_bff_runtime;
revoke create on schema console_bff from console_bff_sys;
create table if not exists console_bff.lane_rate_window (
    lane text not null check (lane in ('DEADLINE_NEAR', 'LIVE', 'REPROCESSING', 'BACKFILL', 'REPLAY')),
    window_start timestamptz not null,
    used int not null check (used >= 0),
    primary key (lane)
);
comment on table console_bff.lane_rate_window is 'platform-scoped: no tenant data';
grant usage on schema console_bff to console_bff_runtime;
grant select, insert, update, delete on console_bff.lane_rate_window to console_bff_runtime;
-- END QueueSchema.ddl
