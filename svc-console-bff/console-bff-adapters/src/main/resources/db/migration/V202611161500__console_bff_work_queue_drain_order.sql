SET lock_timeout = '5s';

-- INV-T-23708, INV-AC-23707: expand-contract upgrade of the work queue for the drain order. The section below is
-- QueueSchema.drainOrderUpgradeDdl("console_bff", "console_bff_runtime", "console_bff_sys") of platform-libs-bom 0.207.0,
-- embedded as text. Nothing is dropped; every statement is repeatable.
-- BEGIN QueueSchema.drainOrderUpgradeDdl("console_bff", "console_bff_runtime", "console_bff_sys")
alter table console_bff.work_queue add column if not exists drain_tier smallint not null default 3
    check (drain_tier in (1, 2, 3));
alter table console_bff.work_queue add column if not exists drain_rank int;
grant select (drain_tier, drain_rank) on console_bff.work_queue to console_bff_sys;
create or replace function console_bff.claim_due(p_lane text, p_consumer text, p_claimed_at timestamptz,
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
        order by q.drain_tier, q.drain_rank nulls last, q.seq limit 20
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
-- END QueueSchema.drainOrderUpgradeDdl
