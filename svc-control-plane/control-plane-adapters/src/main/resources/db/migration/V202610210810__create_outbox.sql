-- V202610210810__create_outbox.sql: platform-generated DDL (TPL-12); regenerate with GeneratedMigrationsTest, never edit by hand.
-- Generated: outbox-and-queue's OutboxSchema.ddl("controlplane", "controlplane_runtime", "controlplane_sys") of the platform-libs-bom 0.103.0 train.
create table controlplane.outbox (
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
create index outbox_unpublished on controlplane.outbox (seq) where published_at is null;
create index outbox_published_at on controlplane.outbox (published_at) where published_at is not null;
create table controlplane.outbox_sequence (
    tenant_id uuid not null,
    aggregate_key text not null,
    last_value bigint not null,
    primary key (tenant_id, aggregate_key)
);
create table controlplane.inbox (
    consumer text not null,
    event_id text not null,
    tenant_id uuid not null,
    received_at timestamptz not null default now(),
    primary key (consumer, event_id)
);
create index inbox_received_at on controlplane.inbox (received_at);
create table controlplane.consumer_sequence (
    consumer text not null,
    source text not null,
    subject text not null,
    tenant_id uuid not null,
    last_value bigint not null,
    primary key (consumer, tenant_id, source, subject)
);
create table controlplane.consumer_offset (
    consumer text not null,
    topic text not null,
    next_offset bigint not null,
    attempts int not null default 0,
    infra_failures int not null default 0,
    first_infra_failure_at timestamptz,
    retry_not_before timestamptz,
    primary key (consumer, topic)
);
comment on table controlplane.consumer_offset is 'platform-scoped: no tenant data';
create table controlplane.event_dead_letter (
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
create index event_dead_letter_resolved_at on controlplane.event_dead_letter (resolved_at) where resolved_at is not null;
comment on column controlplane.outbox.data is 'class: content';
comment on column controlplane.event_dead_letter.raw is 'class: content';
ALTER TABLE controlplane.outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE controlplane.outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_tenant_isolation ON controlplane.outbox
    USING      (tenant_id = (select controlplane.bound_tenant()))
    WITH CHECK (tenant_id = (select controlplane.bound_tenant()));
ALTER TABLE controlplane.outbox_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE controlplane.outbox_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_sequence_tenant_isolation ON controlplane.outbox_sequence
    USING      (tenant_id = (select controlplane.bound_tenant()))
    WITH CHECK (tenant_id = (select controlplane.bound_tenant()));
ALTER TABLE controlplane.inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE controlplane.inbox FORCE ROW LEVEL SECURITY;
CREATE POLICY inbox_tenant_isolation ON controlplane.inbox
    USING      (tenant_id = (select controlplane.bound_tenant()))
    WITH CHECK (tenant_id = (select controlplane.bound_tenant()));
ALTER TABLE controlplane.consumer_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE controlplane.consumer_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY consumer_sequence_tenant_isolation ON controlplane.consumer_sequence
    USING      (tenant_id = (select controlplane.bound_tenant()))
    WITH CHECK (tenant_id = (select controlplane.bound_tenant()));
ALTER TABLE controlplane.event_dead_letter ENABLE ROW LEVEL SECURITY;
ALTER TABLE controlplane.event_dead_letter FORCE ROW LEVEL SECURITY;
CREATE POLICY event_dead_letter_tenant_isolation ON controlplane.event_dead_letter
    USING      (tenant_id = (select controlplane.bound_tenant()))
    WITH CHECK (tenant_id = (select controlplane.bound_tenant()));
create table if not exists controlplane.consumer_replay (
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
comment on table controlplane.consumer_replay is 'platform-scoped: no tenant data';
create unique index if not exists consumer_replay_pending on controlplane.consumer_replay (consumer, producer)
    where closed_at is null;
create table if not exists controlplane.consumer_replay_marker (
    replay_id uuid not null,
    source text not null,
    applied_at timestamptz not null default now(),
    primary key (replay_id, source)
);
comment on table controlplane.consumer_replay_marker is 'platform-scoped: no tenant data';
create table if not exists controlplane.consumer_source_seen (
    consumer text not null,
    producer text not null,
    source text not null,
    primary key (consumer, producer, source)
);
comment on table controlplane.consumer_source_seen is 'platform-scoped: no tenant data';
create function controlplane.outbox_publish_once() returns trigger
language plpgsql set search_path = pg_catalog, controlplane, pg_temp as $$
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
revoke all on function controlplane.outbox_publish_once() from public;
create trigger outbox_publish_once before update on controlplane.outbox
    for each row execute function controlplane.outbox_publish_once();
grant usage on schema controlplane to controlplane_runtime;
grant select, insert, update (published_at) on controlplane.outbox to controlplane_runtime;
grant select, insert, update on controlplane.outbox_sequence, controlplane.consumer_sequence, controlplane.consumer_offset to controlplane_runtime;
grant select, insert on controlplane.inbox to controlplane_runtime;
grant select on controlplane.event_dead_letter to controlplane_runtime;
create policy event_dead_letter_no_platform_tenant on controlplane.event_dead_letter as restrictive for select to controlplane_runtime
    using (tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
grant select, insert, update on controlplane.consumer_replay, controlplane.consumer_replay_marker, controlplane.consumer_source_seen
    to controlplane_runtime;
grant usage on schema controlplane to controlplane_sys;
create policy outbox_sys_select on controlplane.outbox for select to controlplane_sys using (true);
create policy outbox_sys_purge on controlplane.outbox for delete to controlplane_sys using (published_at is not null);
create policy outbox_sys_purge_published on controlplane.outbox as restrictive for delete to controlplane_sys
    using (published_at is not null);
grant select (seq, tenant_id, published_at) on controlplane.outbox to controlplane_sys;
grant delete on controlplane.outbox to controlplane_sys;
grant insert on controlplane.outbox to controlplane_sys;
create policy inbox_sys_select on controlplane.inbox for select to controlplane_sys using (true);
create policy inbox_sys_purge on controlplane.inbox for delete to controlplane_sys using (true);
grant select (consumer, event_id, received_at) on controlplane.inbox to controlplane_sys;
grant delete on controlplane.inbox to controlplane_sys;
create policy consumer_sequence_sys_select on controlplane.consumer_sequence for select to controlplane_sys using (true);
create policy consumer_sequence_sys_full_sync on controlplane.consumer_sequence for delete to controlplane_sys using (true);
grant select (consumer, source, tenant_id) on controlplane.consumer_sequence to controlplane_sys;
grant delete on controlplane.consumer_sequence to controlplane_sys;
create policy event_dead_letter_sys_select on controlplane.event_dead_letter for select to controlplane_sys using (true);
create policy event_dead_letter_sys_insert on controlplane.event_dead_letter for insert to controlplane_sys with check (true);
create policy event_dead_letter_sys_update on controlplane.event_dead_letter for update to controlplane_sys using (true) with check (true);
create policy event_dead_letter_sys_purge on controlplane.event_dead_letter for delete to controlplane_sys using (resolved_at is not null);
create policy event_dead_letter_sys_purge_triaged on controlplane.event_dead_letter as restrictive for delete to controlplane_sys
    using (resolved_at is not null and status <> 'OPEN');
grant select (id, consumer, topic, log_offset, tenant_id, reason, recorded_at, status, resolved_at, note, replayed_to) on controlplane.event_dead_letter to controlplane_sys;
grant insert on controlplane.event_dead_letter to controlplane_sys;
grant update (status, resolved_at, note, replayed_to) on controlplane.event_dead_letter to controlplane_sys;
grant delete on controlplane.event_dead_letter to controlplane_sys;
grant select, insert, update on controlplane.consumer_offset to controlplane_sys;
grant insert on controlplane.consumer_source_seen to controlplane_sys;
create view controlplane.outbox_claim_v as select seq, tenant_id, published_at from controlplane.outbox;
create view controlplane.event_dead_letter_triage_v as select id, consumer, topic, log_offset, tenant_id, reason, recorded_at, status, resolved_at, note, replayed_to from controlplane.event_dead_letter;
grant create on schema controlplane to controlplane_sys;
alter view controlplane.outbox_claim_v owner to controlplane_sys;
revoke all on controlplane.outbox_claim_v from public;
grant select on controlplane.outbox_claim_v to controlplane_runtime;
alter view controlplane.event_dead_letter_triage_v owner to controlplane_sys;
revoke all on controlplane.event_dead_letter_triage_v from public;
grant select on controlplane.event_dead_letter_triage_v to controlplane_runtime;
revoke create on schema controlplane from controlplane_sys;
create function controlplane.outbox_claim_page(after_seq bigint, page_size integer, unpublished_only boolean)
returns table (seq bigint, tenant_id uuid, published_at timestamptz)
language plpgsql stable security definer set search_path = pg_catalog, controlplane, pg_temp as $$
begin
    return query select v.seq, v.tenant_id, v.published_at from controlplane.outbox_claim_v v
        where v.seq > after_seq and (not unpublished_only or v.published_at is null)
        order by v.seq limit page_size;
end
$$;
create function controlplane.outbox_claim_pending() returns bigint
language plpgsql stable security definer set search_path = pg_catalog, controlplane, pg_temp as $$
begin
    return (select count(*) from controlplane.outbox_claim_v v where v.published_at is null);
end
$$;
grant create on schema controlplane to controlplane_sys;
alter function controlplane.outbox_claim_page(bigint, integer, boolean) owner to controlplane_sys;
revoke all on function controlplane.outbox_claim_page(bigint, integer, boolean) from public;
grant execute on function controlplane.outbox_claim_page(bigint, integer, boolean) to controlplane_runtime;
alter function controlplane.outbox_claim_pending() owner to controlplane_sys;
revoke all on function controlplane.outbox_claim_pending() from public;
grant execute on function controlplane.outbox_claim_pending() to controlplane_runtime;
revoke create on schema controlplane from controlplane_sys;
create function controlplane.consumer_offset_lock(p_consumer text, p_topic text) returns bigint
language plpgsql volatile security definer set search_path = pg_catalog, controlplane, pg_temp as $$
declare
    v_next bigint;
begin
    insert into controlplane.consumer_offset (consumer, topic, next_offset) values (p_consumer, p_topic, 1)
        on conflict do nothing;
    select o.next_offset into v_next from controlplane.consumer_offset o
        where o.consumer = p_consumer and o.topic = p_topic for update;
    if not found then
        raise exception 'offset row missing';
    end if;
    return v_next;
end
$$;
create function controlplane.consumer_offset_advance(p_consumer text, p_topic text, p_next_offset bigint) returns void
language plpgsql volatile security definer set search_path = pg_catalog, controlplane, pg_temp as $$
begin
    update controlplane.consumer_offset set next_offset = p_next_offset, attempts = 0, infra_failures = 0,
        first_infra_failure_at = null, retry_not_before = null
    where consumer = p_consumer and topic = p_topic;
end
$$;
create function controlplane.consumer_source_seen_record(p_consumer text, p_producer text, p_source text) returns void
language plpgsql volatile security definer set search_path = pg_catalog, controlplane, pg_temp as $$
begin
    insert into controlplane.consumer_source_seen (consumer, producer, source)
        values (p_consumer, p_producer, p_source) on conflict do nothing;
end
$$;
grant create on schema controlplane to controlplane_sys;
alter function controlplane.consumer_offset_lock(text, text) owner to controlplane_sys;
revoke all on function controlplane.consumer_offset_lock(text, text) from public;
grant execute on function controlplane.consumer_offset_lock(text, text) to controlplane_runtime;
alter function controlplane.consumer_offset_advance(text, text, bigint) owner to controlplane_sys;
revoke all on function controlplane.consumer_offset_advance(text, text, bigint) from public;
grant execute on function controlplane.consumer_offset_advance(text, text, bigint) to controlplane_runtime;
alter function controlplane.consumer_source_seen_record(text, text, text) owner to controlplane_sys;
revoke all on function controlplane.consumer_source_seen_record(text, text, text) from public;
grant execute on function controlplane.consumer_source_seen_record(text, text, text) to controlplane_runtime;
revoke create on schema controlplane from controlplane_sys;
create function controlplane.event_dead_letter_triage_page(p_after bigint, p_status text, p_ids bigint[], p_tenant uuid,
    p_consumer text, p_topic text, p_failure text, p_from timestamptz, p_before timestamptz,
    p_with_tenant boolean, p_platform uuid, p_limit integer)
returns table (id bigint, consumer text, topic text, log_offset bigint, tenant_id uuid, reason text,
    status text, recorded_at timestamptz, resolved_at timestamptz, note text, replayed_to text)
language plpgsql stable security definer set search_path = pg_catalog, controlplane, pg_temp as $$
begin
    if p_limit is null or p_limit < 1 or p_limit > 1000 then
        raise exception 'the triage page limit must be in 1..1000' using errcode = '22023';
    end if;
    return query select v.id, v.consumer, v.topic, v.log_offset, v.tenant_id, v.reason, v.status,
            v.recorded_at, v.resolved_at, v.note, v.replayed_to
        from controlplane.event_dead_letter_triage_v v
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
grant create on schema controlplane to controlplane_sys;
alter function controlplane.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) owner to controlplane_sys;
revoke all on function controlplane.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) from public;
grant execute on function controlplane.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) to controlplane_runtime;
revoke create on schema controlplane from controlplane_sys;
create function controlplane.outbox_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, controlplane, pg_temp as $$
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
    delete from controlplane.outbox where seq in
        (select seq from controlplane.outbox where published_at is not null and published_at < cutoff
         limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema controlplane to controlplane_sys;
alter function controlplane.outbox_purge_expired(timestamptz, integer) owner to controlplane_sys;
revoke all on function controlplane.outbox_purge_expired(timestamptz, integer) from public;
grant execute on function controlplane.outbox_purge_expired(timestamptz, integer) to controlplane_runtime;
revoke create on schema controlplane from controlplane_sys;
create function controlplane.inbox_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, controlplane, pg_temp as $$
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
    delete from controlplane.inbox where (consumer, event_id) in
        (select consumer, event_id from controlplane.inbox where received_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema controlplane to controlplane_sys;
alter function controlplane.inbox_purge_expired(timestamptz, integer) owner to controlplane_sys;
revoke all on function controlplane.inbox_purge_expired(timestamptz, integer) from public;
grant execute on function controlplane.inbox_purge_expired(timestamptz, integer) to controlplane_runtime;
revoke create on schema controlplane from controlplane_sys;
create function controlplane.event_dead_letter_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, controlplane, pg_temp as $$
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
    delete from controlplane.event_dead_letter where id in
        (select id from controlplane.event_dead_letter
         where status <> 'OPEN' and resolved_at is not null and resolved_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema controlplane to controlplane_sys;
alter function controlplane.event_dead_letter_purge_expired(timestamptz, integer) owner to controlplane_sys;
revoke all on function controlplane.event_dead_letter_purge_expired(timestamptz, integer) from public;
grant execute on function controlplane.event_dead_letter_purge_expired(timestamptz, integer) to controlplane_runtime;
revoke create on schema controlplane from controlplane_sys;
create function controlplane.full_sync_delete(p_consumer text, p_source text) returns bigint
language plpgsql security definer set search_path = pg_catalog, controlplane, pg_temp as $$
declare
    deleted bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'
        or p_source is null or p_source ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$' then
        raise exception 'a full sync names one consumer and one source' using errcode = '22023';
    end if;
    delete from controlplane.consumer_sequence where consumer = p_consumer and source = p_source;
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema controlplane to controlplane_sys;
alter function controlplane.full_sync_delete(text, text) owner to controlplane_sys;
revoke all on function controlplane.full_sync_delete(text, text) from public;
grant execute on function controlplane.full_sync_delete(text, text) to controlplane_runtime;
revoke create on schema controlplane from controlplane_sys;
create function controlplane.event_dead_letter_record(p_consumer text, p_topic text, p_log_offset bigint, p_tenant_id uuid,
    p_reason text, p_raw text) returns bigint
language plpgsql security definer set search_path = pg_catalog, controlplane, pg_temp as $$
declare
    recorded bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'
        or p_topic is null or p_log_offset is null
        or p_reason is null or p_raw is null then
        raise exception 'a dead letter names its consumer, topic, offset, reason and record'
            using errcode = '22023';
    end if;
    insert into controlplane.event_dead_letter (consumer, topic, log_offset, tenant_id, reason, raw)
    values (p_consumer, p_topic, p_log_offset, coalesce(p_tenant_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid), p_reason, p_raw)
    returning id into recorded;
    return recorded;
end
$$;
grant create on schema controlplane to controlplane_sys;
alter function controlplane.event_dead_letter_record(text, text, bigint, uuid, text, text) owner to controlplane_sys;
revoke all on function controlplane.event_dead_letter_record(text, text, bigint, uuid, text, text) from public;
grant execute on function controlplane.event_dead_letter_record(text, text, bigint, uuid, text, text) to controlplane_runtime;
revoke create on schema controlplane from controlplane_sys;
create function controlplane.event_dead_letter_resolve(p_id bigint, p_status text, p_resolved_at timestamptz, p_note text,
    p_replayed_to text) returns boolean
language plpgsql security definer set search_path = pg_catalog, controlplane, pg_temp as $$
begin
    if p_status is null or p_status not in ('REPLAYED', 'DISCARDED') or p_resolved_at is null then
        raise exception 'a dead letter is resolved as REPLAYED or DISCARDED at a time' using errcode = '22023';
    end if;
    update controlplane.event_dead_letter set status = p_status, resolved_at = p_resolved_at, note = p_note,
        replayed_to = p_replayed_to
    where id = p_id and status = 'OPEN';
    return found;
end
$$;
grant create on schema controlplane to controlplane_sys;
alter function controlplane.event_dead_letter_resolve(bigint, text, timestamptz, text, text) owner to controlplane_sys;
revoke all on function controlplane.event_dead_letter_resolve(bigint, text, timestamptz, text, text) from public;
grant execute on function controlplane.event_dead_letter_resolve(bigint, text, timestamptz, text, text) to controlplane_runtime;
revoke create on schema controlplane from controlplane_sys;
