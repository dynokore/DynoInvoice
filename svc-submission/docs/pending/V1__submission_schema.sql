-- V1__submission_schema.sql (TPL-12; INV-T-5964, INV-REQ-1194, INV-AC-2180, coordinator ruling INV-Q-7266 (A)).
--
-- Roles (INV-AC-2175): submission_migrate owns the tables and runs this file; submission_runtime logs in;
-- submission_sys is NOLOGIN. No role named "submission" exists.
-- Precondition (QueueSchema.ddl KDoc, coordinator rulings INV-Q-6586 and INV-Q-6663): submission.bound_tenant() and
-- submission.bound_actor() already exist in the schema, installed once per schema by the sys login from
-- tenancy-kernel's BindingKeyMigration.ddl; this file does not run that generator and does not switch role.
create schema if not exists submission;

create table submission.submission (
    id uuid primary key,
    tenant_id uuid not null,
    state text not null,
    version bigint not null default 0
);
comment on column submission.submission.state is 'class: operational';
create table submission.submission_attempt (
    id uuid primary key,
    tenant_id uuid not null,
    submission_id uuid not null references submission.submission (id),
    state text not null,
    version bigint not null default 0
);
comment on column submission.submission_attempt.state is 'class: operational';
create index submission_attempt_submission on submission.submission_attempt (submission_id);

alter table submission.submission enable row level security;
alter table submission.submission force row level security;
create policy submission_tenant_isolation on submission.submission
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
alter table submission.submission_attempt enable row level security;
alter table submission.submission_attempt force row level security;
create policy submission_attempt_tenant_isolation on submission.submission_attempt
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant usage on schema submission to submission_runtime;
grant select, insert, update on submission.submission, submission.submission_attempt to submission_runtime;

-- OutboxSchema.ddl("submission", "submission_runtime", "submission_sys")
create table submission.outbox (
    seq bigint generated always as identity primary key,
    id text not null unique,
    topic text not null,
    type text not null,
    source text not null,
    subject text not null,
    event_time timestamptz not null,
    tenant_id uuid not null,
    entity_ref text not null,
    doc_uuid uuid,
    transaction_date date,
    docsequence bigint,
    instance_id text not null,
    correlation_id text not null,
    causation_id text not null,
    data jsonb not null,
    published_at timestamptz
);
create index outbox_unpublished on submission.outbox (seq) where published_at is null;
create index outbox_published_at on submission.outbox (published_at) where published_at is not null;
create table submission.outbox_sequence (
    tenant_id uuid not null,
    aggregate_key text not null,
    last_value bigint not null,
    primary key (tenant_id, aggregate_key)
);
create table submission.inbox (
    consumer text not null,
    event_id text not null,
    tenant_id uuid not null,
    received_at timestamptz not null default now(),
    primary key (consumer, event_id)
);
create index inbox_received_at on submission.inbox (received_at);
create table submission.consumer_sequence (
    consumer text not null,
    source text not null,
    subject text not null,
    tenant_id uuid not null,
    last_value bigint not null,
    primary key (consumer, tenant_id, source, subject)
);
create table submission.consumer_offset (
    consumer text not null,
    topic text not null,
    next_offset bigint not null,
    attempts int not null default 0,
    infra_failures int not null default 0,
    first_infra_failure_at timestamptz,
    retry_not_before timestamptz,
    primary key (consumer, topic)
);
comment on table submission.consumer_offset is 'platform-scoped: no tenant data';
create table submission.event_dead_letter (
    id bigint generated always as identity primary key,
    consumer text not null,
    topic text not null,
    log_offset bigint not null,
    tenant_id uuid not null,
    reason text not null,
    raw text not null,
    recorded_at timestamptz not null default now(),
    status text not null default 'OPEN' check (status in ('OPEN', 'REPLAYED', 'DISCARDED')),
    resolved_at timestamptz,
    note text,
    replayed_to text
);
create index event_dead_letter_resolved_at on submission.event_dead_letter (resolved_at) where resolved_at is not null;
comment on column submission.outbox.data is 'class: content';
comment on column submission.event_dead_letter.raw is 'class: content';
ALTER TABLE submission.outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE submission.outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_tenant_isolation ON submission.outbox
    USING      (tenant_id = (select submission.bound_tenant()))
    WITH CHECK (tenant_id = (select submission.bound_tenant()));
ALTER TABLE submission.outbox_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE submission.outbox_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_sequence_tenant_isolation ON submission.outbox_sequence
    USING      (tenant_id = (select submission.bound_tenant()))
    WITH CHECK (tenant_id = (select submission.bound_tenant()));
ALTER TABLE submission.inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE submission.inbox FORCE ROW LEVEL SECURITY;
CREATE POLICY inbox_tenant_isolation ON submission.inbox
    USING      (tenant_id = (select submission.bound_tenant()))
    WITH CHECK (tenant_id = (select submission.bound_tenant()));
ALTER TABLE submission.consumer_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE submission.consumer_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY consumer_sequence_tenant_isolation ON submission.consumer_sequence
    USING      (tenant_id = (select submission.bound_tenant()))
    WITH CHECK (tenant_id = (select submission.bound_tenant()));
ALTER TABLE submission.event_dead_letter ENABLE ROW LEVEL SECURITY;
ALTER TABLE submission.event_dead_letter FORCE ROW LEVEL SECURITY;
CREATE POLICY event_dead_letter_tenant_isolation ON submission.event_dead_letter
    USING      (tenant_id = (select submission.bound_tenant()))
    WITH CHECK (tenant_id = (select submission.bound_tenant()));
create table if not exists submission.consumer_replay (
    replay_id uuid not null,
    consumer text not null,
    producer text not null,
    mode text not null check (mode in ('SCOPE', 'FULL_SYNC')),
    scope text,
    topics text[] not null,
    requested_at timestamptz not null,
    completed_at timestamptz,
    closed_at timestamptz,
    primary key (replay_id, producer)
);
comment on table submission.consumer_replay is 'platform-scoped: no tenant data';
create unique index if not exists consumer_replay_pending on submission.consumer_replay (consumer, producer)
    where closed_at is null;
create table if not exists submission.consumer_replay_marker (
    replay_id uuid not null,
    source text not null,
    applied_at timestamptz not null default now(),
    primary key (replay_id, source)
);
comment on table submission.consumer_replay_marker is 'platform-scoped: no tenant data';
create table if not exists submission.consumer_source_seen (
    consumer text not null,
    producer text not null,
    source text not null,
    primary key (consumer, producer, source)
);
comment on table submission.consumer_source_seen is 'platform-scoped: no tenant data';
create function submission.outbox_publish_once() returns trigger
language plpgsql set search_path = pg_catalog, submission, pg_temp as $$
begin
    if (to_jsonb(new) - 'published_at') is distinct from (to_jsonb(old) - 'published_at') then
        raise exception 'an outbox row is never rewritten: only published_at is set, once'
            using errcode = '42501';
    end if;
    if old.published_at is not null then
        raise exception 'outbox row % is published: published_at is never re-set, cleared or backdated',
            old.seq using errcode = '42501';
    end if;
    if new.published_at is null then
        raise exception 'published_at changes only from NULL to the database time' using errcode = '42501';
    end if;
    new.published_at := clock_timestamp();
    return new;
end
$$;
revoke all on function submission.outbox_publish_once() from public;
create trigger outbox_publish_once before update on submission.outbox
    for each row execute function submission.outbox_publish_once();
grant usage on schema submission to submission_runtime;
grant select, insert, update (published_at) on submission.outbox to submission_runtime;
grant select, insert, update on submission.outbox_sequence, submission.consumer_sequence, submission.consumer_offset to submission_runtime;
grant select, insert on submission.inbox to submission_runtime;
grant select on submission.event_dead_letter to submission_runtime;
create policy event_dead_letter_no_platform_tenant on submission.event_dead_letter as restrictive for select to submission_runtime
    using (tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
grant select, insert, update on submission.consumer_replay, submission.consumer_replay_marker, submission.consumer_source_seen
    to submission_runtime;
grant usage on schema submission to submission_sys;
create policy outbox_sys_select on submission.outbox for select to submission_sys using (true);
create policy outbox_sys_purge on submission.outbox for delete to submission_sys using (published_at is not null);
create policy outbox_sys_purge_published on submission.outbox as restrictive for delete to submission_sys
    using (published_at is not null);
grant select (seq, tenant_id, published_at) on submission.outbox to submission_sys;
grant delete on submission.outbox to submission_sys;
grant insert on submission.outbox to submission_sys;
create policy inbox_sys_select on submission.inbox for select to submission_sys using (true);
create policy inbox_sys_purge on submission.inbox for delete to submission_sys using (true);
grant select (consumer, event_id, received_at) on submission.inbox to submission_sys;
grant delete on submission.inbox to submission_sys;
create policy consumer_sequence_sys_select on submission.consumer_sequence for select to submission_sys using (true);
create policy consumer_sequence_sys_full_sync on submission.consumer_sequence for delete to submission_sys using (true);
grant select (consumer, source, tenant_id) on submission.consumer_sequence to submission_sys;
grant delete on submission.consumer_sequence to submission_sys;
create policy event_dead_letter_sys_select on submission.event_dead_letter for select to submission_sys using (true);
create policy event_dead_letter_sys_insert on submission.event_dead_letter for insert to submission_sys with check (true);
create policy event_dead_letter_sys_update on submission.event_dead_letter for update to submission_sys using (true) with check (true);
create policy event_dead_letter_sys_purge on submission.event_dead_letter for delete to submission_sys using (resolved_at is not null);
create policy event_dead_letter_sys_purge_triaged on submission.event_dead_letter as restrictive for delete to submission_sys
    using (resolved_at is not null and status <> 'OPEN');
grant select (id, consumer, topic, log_offset, tenant_id, reason, recorded_at, status, resolved_at, note, replayed_to) on submission.event_dead_letter to submission_sys;
grant insert on submission.event_dead_letter to submission_sys;
grant update (status, resolved_at, note, replayed_to) on submission.event_dead_letter to submission_sys;
grant delete on submission.event_dead_letter to submission_sys;
grant select, insert, update on submission.consumer_offset to submission_sys;
grant insert on submission.consumer_source_seen to submission_sys;
create view submission.outbox_claim_v as select seq, tenant_id, published_at from submission.outbox;
create view submission.event_dead_letter_triage_v as select id, consumer, topic, log_offset, tenant_id, reason, recorded_at, status, resolved_at, note, replayed_to from submission.event_dead_letter;
grant create on schema submission to submission_sys;
alter view submission.outbox_claim_v owner to submission_sys;
revoke all on submission.outbox_claim_v from public;
grant select on submission.outbox_claim_v to submission_runtime;
alter view submission.event_dead_letter_triage_v owner to submission_sys;
revoke all on submission.event_dead_letter_triage_v from public;
grant select on submission.event_dead_letter_triage_v to submission_runtime;
revoke create on schema submission from submission_sys;
create function submission.outbox_claim_page(after_seq bigint, page_size integer, unpublished_only boolean)
returns table (seq bigint, tenant_id uuid, published_at timestamptz)
language plpgsql stable security definer set search_path = pg_catalog, submission, pg_temp as $$
begin
    return query select v.seq, v.tenant_id, v.published_at from submission.outbox_claim_v v
        where v.seq > after_seq and (not unpublished_only or v.published_at is null)
        order by v.seq limit page_size;
end
$$;
create function submission.outbox_claim_pending() returns bigint
language plpgsql stable security definer set search_path = pg_catalog, submission, pg_temp as $$
begin
    return (select count(*) from submission.outbox_claim_v v where v.published_at is null);
end
$$;
grant create on schema submission to submission_sys;
alter function submission.outbox_claim_page(bigint, integer, boolean) owner to submission_sys;
revoke all on function submission.outbox_claim_page(bigint, integer, boolean) from public;
grant execute on function submission.outbox_claim_page(bigint, integer, boolean) to submission_runtime;
alter function submission.outbox_claim_pending() owner to submission_sys;
revoke all on function submission.outbox_claim_pending() from public;
grant execute on function submission.outbox_claim_pending() to submission_runtime;
revoke create on schema submission from submission_sys;
create function submission.consumer_offset_lock(p_consumer text, p_topic text) returns bigint
language plpgsql volatile security definer set search_path = pg_catalog, submission, pg_temp as $$
declare
    v_next bigint;
begin
    insert into submission.consumer_offset (consumer, topic, next_offset) values (p_consumer, p_topic, 1)
        on conflict do nothing;
    select o.next_offset into v_next from submission.consumer_offset o
        where o.consumer = p_consumer and o.topic = p_topic for update;
    if not found then
        raise exception 'offset row missing';
    end if;
    return v_next;
end
$$;
create function submission.consumer_offset_advance(p_consumer text, p_topic text, p_next_offset bigint) returns void
language plpgsql volatile security definer set search_path = pg_catalog, submission, pg_temp as $$
begin
    update submission.consumer_offset set next_offset = p_next_offset, attempts = 0, infra_failures = 0,
        first_infra_failure_at = null, retry_not_before = null
    where consumer = p_consumer and topic = p_topic;
end
$$;
create function submission.consumer_source_seen_record(p_consumer text, p_producer text, p_source text) returns void
language plpgsql volatile security definer set search_path = pg_catalog, submission, pg_temp as $$
begin
    insert into submission.consumer_source_seen (consumer, producer, source)
        values (p_consumer, p_producer, p_source) on conflict do nothing;
end
$$;
grant create on schema submission to submission_sys;
alter function submission.consumer_offset_lock(text, text) owner to submission_sys;
revoke all on function submission.consumer_offset_lock(text, text) from public;
grant execute on function submission.consumer_offset_lock(text, text) to submission_runtime;
alter function submission.consumer_offset_advance(text, text, bigint) owner to submission_sys;
revoke all on function submission.consumer_offset_advance(text, text, bigint) from public;
grant execute on function submission.consumer_offset_advance(text, text, bigint) to submission_runtime;
alter function submission.consumer_source_seen_record(text, text, text) owner to submission_sys;
revoke all on function submission.consumer_source_seen_record(text, text, text) from public;
grant execute on function submission.consumer_source_seen_record(text, text, text) to submission_runtime;
revoke create on schema submission from submission_sys;
create function submission.event_dead_letter_triage_page(p_after bigint, p_status text, p_ids bigint[], p_tenant uuid,
    p_consumer text, p_topic text, p_failure text, p_from timestamptz, p_before timestamptz,
    p_with_tenant boolean, p_platform uuid, p_limit integer)
returns table (id bigint, consumer text, topic text, log_offset bigint, tenant_id uuid, reason text,
    status text, recorded_at timestamptz, resolved_at timestamptz, note text, replayed_to text)
language plpgsql stable security definer set search_path = pg_catalog, submission, pg_temp as $$
begin
    if p_limit is null or p_limit < 1 or p_limit > 1000 then
        raise exception 'the triage page limit must be in 1..1000' using errcode = '22023';
    end if;
    return query select v.id, v.consumer, v.topic, v.log_offset, v.tenant_id, v.reason, v.status,
            v.recorded_at, v.resolved_at, v.note, v.replayed_to
        from submission.event_dead_letter_triage_v v
        where v.id > p_after
          and (p_status is null or v.status = p_status)
          and (p_ids is null or v.id = any(p_ids))
          and (p_tenant is null or v.tenant_id = p_tenant)
          and (p_consumer is null or v.consumer = p_consumer)
          and (p_topic is null or v.topic = p_topic)
          and (p_failure is null or starts_with(v.reason, p_failure))
          and (p_from is null or v.recorded_at >= p_from)
          and (p_before is null or v.recorded_at < p_before)
          and (not p_with_tenant or v.tenant_id <> p_platform)
        order by v.id limit p_limit;
end
$$;
grant create on schema submission to submission_sys;
alter function submission.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) owner to submission_sys;
revoke all on function submission.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) from public;
grant execute on function submission.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) to submission_runtime;
revoke create on schema submission from submission_sys;
create function submission.outbox_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, submission, pg_temp as $$
declare
    deleted bigint;
begin
    if cutoff is null or cutoff > now() - interval '7 days' then
        raise exception 'cut-off % is later than the retention floor of 7 days', cutoff
            using errcode = '22023';
    end if;
    if batch_size is null or batch_size < 1 or batch_size > 10000 then
        raise exception 'batch size % is outside 1..10000', batch_size using errcode = '22023';
    end if;
    delete from submission.outbox where seq in
        (select seq from submission.outbox where published_at is not null and published_at < cutoff
         limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema submission to submission_sys;
alter function submission.outbox_purge_expired(timestamptz, integer) owner to submission_sys;
revoke all on function submission.outbox_purge_expired(timestamptz, integer) from public;
grant execute on function submission.outbox_purge_expired(timestamptz, integer) to submission_runtime;
revoke create on schema submission from submission_sys;
create function submission.inbox_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, submission, pg_temp as $$
declare
    deleted bigint;
begin
    if cutoff is null or cutoff > now() - interval '8 days' then
        raise exception 'cut-off % is later than the retention floor of 8 days', cutoff
            using errcode = '22023';
    end if;
    if batch_size is null or batch_size < 1 or batch_size > 10000 then
        raise exception 'batch size % is outside 1..10000', batch_size using errcode = '22023';
    end if;
    delete from submission.inbox where (consumer, event_id) in
        (select consumer, event_id from submission.inbox where received_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema submission to submission_sys;
alter function submission.inbox_purge_expired(timestamptz, integer) owner to submission_sys;
revoke all on function submission.inbox_purge_expired(timestamptz, integer) from public;
grant execute on function submission.inbox_purge_expired(timestamptz, integer) to submission_runtime;
revoke create on schema submission from submission_sys;
create function submission.event_dead_letter_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, submission, pg_temp as $$
declare
    deleted bigint;
begin
    if cutoff is null or cutoff > now() - interval '7 days' then
        raise exception 'cut-off % is later than the retention floor of 7 days', cutoff
            using errcode = '22023';
    end if;
    if batch_size is null or batch_size < 1 or batch_size > 10000 then
        raise exception 'batch size % is outside 1..10000', batch_size using errcode = '22023';
    end if;
    delete from submission.event_dead_letter where id in
        (select id from submission.event_dead_letter
         where status <> 'OPEN' and resolved_at is not null and resolved_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema submission to submission_sys;
alter function submission.event_dead_letter_purge_expired(timestamptz, integer) owner to submission_sys;
revoke all on function submission.event_dead_letter_purge_expired(timestamptz, integer) from public;
grant execute on function submission.event_dead_letter_purge_expired(timestamptz, integer) to submission_runtime;
revoke create on schema submission from submission_sys;
create function submission.full_sync_delete(p_consumer text, p_source text) returns bigint
language plpgsql security definer set search_path = pg_catalog, submission, pg_temp as $$
declare
    deleted bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'
        or p_source is null or p_source ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$' then
        raise exception 'a full sync names one consumer and one source' using errcode = '22023';
    end if;
    delete from submission.consumer_sequence where consumer = p_consumer and source = p_source;
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema submission to submission_sys;
alter function submission.full_sync_delete(text, text) owner to submission_sys;
revoke all on function submission.full_sync_delete(text, text) from public;
grant execute on function submission.full_sync_delete(text, text) to submission_runtime;
revoke create on schema submission from submission_sys;
create function submission.event_dead_letter_record(p_consumer text, p_topic text, p_log_offset bigint, p_tenant_id uuid,
    p_reason text, p_raw text) returns bigint
language plpgsql security definer set search_path = pg_catalog, submission, pg_temp as $$
declare
    recorded bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'
        or p_topic is null or p_log_offset is null
        or p_reason is null or p_raw is null then
        raise exception 'a dead letter names its consumer, topic, offset, reason and record'
            using errcode = '22023';
    end if;
    insert into submission.event_dead_letter (consumer, topic, log_offset, tenant_id, reason, raw)
    values (p_consumer, p_topic, p_log_offset, coalesce(p_tenant_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid), p_reason, p_raw)
    returning id into recorded;
    return recorded;
end
$$;
grant create on schema submission to submission_sys;
alter function submission.event_dead_letter_record(text, text, bigint, uuid, text, text) owner to submission_sys;
revoke all on function submission.event_dead_letter_record(text, text, bigint, uuid, text, text) from public;
grant execute on function submission.event_dead_letter_record(text, text, bigint, uuid, text, text) to submission_runtime;
revoke create on schema submission from submission_sys;
create function submission.event_dead_letter_resolve(p_id bigint, p_status text, p_resolved_at timestamptz, p_note text,
    p_replayed_to text) returns boolean
language plpgsql security definer set search_path = pg_catalog, submission, pg_temp as $$
begin
    if p_status is null or p_status not in ('REPLAYED', 'DISCARDED') or p_resolved_at is null then
        raise exception 'a dead letter is resolved as REPLAYED or DISCARDED at a time' using errcode = '22023';
    end if;
    update submission.event_dead_letter set status = p_status, resolved_at = p_resolved_at, note = p_note,
        replayed_to = p_replayed_to
    where id = p_id and status = 'OPEN';
    return found;
end
$$;
grant create on schema submission to submission_sys;
alter function submission.event_dead_letter_resolve(bigint, text, timestamptz, text, text) owner to submission_sys;
revoke all on function submission.event_dead_letter_resolve(bigint, text, timestamptz, text, text) from public;
grant execute on function submission.event_dead_letter_resolve(bigint, text, timestamptz, text, text) to submission_runtime;
revoke create on schema submission from submission_sys;
-- QueueSchema.ddl("submission", "submission_runtime", "submission_sys")
create table submission.work_queue (
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
create index work_queue_partition on submission.work_queue (tenant_id, partition_key, lane, seq);
create index work_queue_lane on submission.work_queue (lane, seq);
create unique index work_queue_replay_once on submission.work_queue (source_dead_letter_id)
    where source_dead_letter_id is not null;
comment on column submission.work_queue.payload is 'class: content';
ALTER TABLE submission.work_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE submission.work_queue FORCE ROW LEVEL SECURITY;
CREATE POLICY work_queue_tenant_isolation ON submission.work_queue
    USING      (tenant_id = (select submission.bound_tenant()))
    WITH CHECK (tenant_id = (select submission.bound_tenant()));
grant usage on schema submission to submission_runtime;
grant select, insert, update, delete on submission.work_queue to submission_runtime;
grant usage on schema submission to submission_sys;
create policy work_queue_sys_select on submission.work_queue for select to submission_sys using (true);
create policy work_queue_sys_claim on submission.work_queue for update to submission_sys using (true) with check (true);
grant select (seq, tenant_id, partition_key, lane, consumer, state, attempts, claimed_at)
    on submission.work_queue to submission_sys;
grant update (state, attempts, claimed_at) on submission.work_queue to submission_sys;
create function submission.claim_due(p_lane text, p_consumer text, p_claimed_at timestamptz,
    p_reclaim_before timestamptz)
returns table (claimed_seq bigint, claimed_tenant uuid, claimed_partition text)
language plpgsql security definer set search_path = pg_catalog, submission, pg_temp as $$
declare
    c record;
begin
    if p_lane is null or p_lane not in ('DEADLINE_NEAR', 'LIVE', 'REPROCESSING', 'BACKFILL', 'REPLAY') or p_claimed_at is null or p_reclaim_before is null then
        raise exception 'a claim names a lane, its time and the reclaim time' using errcode = '22023';
    end if;
    for c in
        select q.seq, q.tenant_id, q.partition_key from submission.work_queue q
        where q.lane = p_lane and q.consumer is not distinct from p_consumer
          and (q.state = 'PENDING' or (q.state = 'CLAIMED' and q.claimed_at < p_reclaim_before))
          and not exists (
              select 1 from submission.work_queue p
              where p.tenant_id = q.tenant_id and p.partition_key = q.partition_key and p.lane = q.lane
                and p.consumer is not distinct from q.consumer and p.seq < q.seq)
        order by q.seq limit 20 for update of q skip locked
    loop
        continue when not pg_try_advisory_xact_lock(hashtextextended(
            'submission.work_queue|' || c.tenant_id || '|' || c.partition_key || '|' || p_lane || '|' ||
            coalesce(p_consumer, ''), 0));
        perform 1 from submission.work_queue q
        where q.seq = c.seq
          and (q.state = 'PENDING' or (q.state = 'CLAIMED' and q.claimed_at < p_reclaim_before))
          and not exists (
              select 1 from submission.work_queue p
              where p.tenant_id = q.tenant_id and p.partition_key = q.partition_key and p.lane = q.lane
                and p.consumer is not distinct from q.consumer and p.seq < q.seq)
        for update of q skip locked;
        continue when not found;
        update submission.work_queue set state = 'CLAIMED', claimed_at = p_claimed_at, attempts = attempts + 1
        where seq = c.seq;
        claimed_seq := c.seq;
        claimed_tenant := c.tenant_id;
        claimed_partition := c.partition_key;
        return next;
        return;
    end loop;
end
$$;
grant create on schema submission to submission_sys;
alter function submission.claim_due(text, text, timestamptz, timestamptz) owner to submission_sys;
revoke all on function submission.claim_due(text, text, timestamptz, timestamptz) from public;
grant execute on function submission.claim_due(text, text, timestamptz, timestamptz) to submission_runtime;
revoke create on schema submission from submission_sys;
create table if not exists submission.lane_rate_window (
    lane text not null check (lane in ('DEADLINE_NEAR', 'LIVE', 'REPROCESSING', 'BACKFILL', 'REPLAY')),
    window_start timestamptz not null,
    used int not null check (used >= 0),
    primary key (lane)
);
comment on table submission.lane_rate_window is 'platform-scoped: no tenant data';
grant usage on schema submission to submission_runtime;
grant select, insert, update, delete on submission.lane_rate_window to submission_runtime;
