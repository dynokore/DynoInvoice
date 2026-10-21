-- V202610210890__create_outbox.sql (INV-T-4942; FR-AH.9, FR-AH.11)
-- Generated: outbox-and-queue's OutboxSchema.ddl("masterdata", "masterdata_runtime", "masterdata_sys") of the BOM 0.97.0 train.
-- OutboxSchemaMigrationTest fails when this file differs from what the pinned module generates. The binding
-- functions masterdata.bound_tenant() and masterdata.bound_actor() must exist before it (src/test/resources/db/provisioning.sql).
create table masterdata.outbox (
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
create index outbox_unpublished on masterdata.outbox (seq) where published_at is null;
create index outbox_published_at on masterdata.outbox (published_at) where published_at is not null;
create table masterdata.outbox_sequence (
    tenant_id uuid not null,
    aggregate_key text not null,
    last_value bigint not null,
    primary key (tenant_id, aggregate_key)
);
create table masterdata.inbox (
    consumer text not null,
    event_id text not null,
    tenant_id uuid not null,
    received_at timestamptz not null default now(),
    primary key (consumer, event_id)
);
create index inbox_received_at on masterdata.inbox (received_at);
create table masterdata.consumer_sequence (
    consumer text not null,
    source text not null,
    subject text not null,
    tenant_id uuid not null,
    last_value bigint not null,
    primary key (consumer, tenant_id, source, subject)
);
create table masterdata.consumer_offset (
    consumer text not null,
    topic text not null,
    next_offset bigint not null,
    attempts int not null default 0,
    infra_failures int not null default 0,
    first_infra_failure_at timestamptz,
    retry_not_before timestamptz,
    primary key (consumer, topic)
);
comment on table masterdata.consumer_offset is 'platform-scoped: no tenant data';
create table masterdata.event_dead_letter (
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
create index event_dead_letter_resolved_at on masterdata.event_dead_letter (resolved_at) where resolved_at is not null;
comment on column masterdata.outbox.data is 'class: content';
comment on column masterdata.event_dead_letter.raw is 'class: content';
ALTER TABLE masterdata.outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_tenant_isolation ON masterdata.outbox
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));
ALTER TABLE masterdata.outbox_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.outbox_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_sequence_tenant_isolation ON masterdata.outbox_sequence
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));
ALTER TABLE masterdata.inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.inbox FORCE ROW LEVEL SECURITY;
CREATE POLICY inbox_tenant_isolation ON masterdata.inbox
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));
ALTER TABLE masterdata.consumer_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.consumer_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY consumer_sequence_tenant_isolation ON masterdata.consumer_sequence
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));
ALTER TABLE masterdata.event_dead_letter ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.event_dead_letter FORCE ROW LEVEL SECURITY;
CREATE POLICY event_dead_letter_tenant_isolation ON masterdata.event_dead_letter
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));
create table if not exists masterdata.consumer_replay (
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
comment on table masterdata.consumer_replay is 'platform-scoped: no tenant data';
create unique index if not exists consumer_replay_pending on masterdata.consumer_replay (consumer, producer)
    where closed_at is null;
create table if not exists masterdata.consumer_replay_marker (
    replay_id uuid not null,
    source text not null,
    applied_at timestamptz not null default now(),
    primary key (replay_id, source)
);
comment on table masterdata.consumer_replay_marker is 'platform-scoped: no tenant data';
create table if not exists masterdata.consumer_source_seen (
    consumer text not null,
    producer text not null,
    source text not null,
    primary key (consumer, producer, source)
);
comment on table masterdata.consumer_source_seen is 'platform-scoped: no tenant data';
create function masterdata.outbox_publish_once() returns trigger
language plpgsql set search_path = pg_catalog, masterdata, pg_temp as $$
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
revoke all on function masterdata.outbox_publish_once() from public;
create trigger outbox_publish_once before update on masterdata.outbox
    for each row execute function masterdata.outbox_publish_once();
grant usage on schema masterdata to masterdata_runtime;
grant select, insert, update (published_at) on masterdata.outbox to masterdata_runtime;
grant select, insert, update on masterdata.outbox_sequence, masterdata.consumer_sequence, masterdata.consumer_offset to masterdata_runtime;
grant select, insert on masterdata.inbox to masterdata_runtime;
grant select on masterdata.event_dead_letter to masterdata_runtime;
create policy event_dead_letter_no_platform_tenant on masterdata.event_dead_letter as restrictive for select to masterdata_runtime
    using (tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
grant select, insert, update on masterdata.consumer_replay, masterdata.consumer_replay_marker, masterdata.consumer_source_seen
    to masterdata_runtime;
grant usage on schema masterdata to masterdata_sys;
create policy outbox_sys_select on masterdata.outbox for select to masterdata_sys using (true);
create policy outbox_sys_purge on masterdata.outbox for delete to masterdata_sys using (published_at is not null);
create policy outbox_sys_purge_published on masterdata.outbox as restrictive for delete to masterdata_sys
    using (published_at is not null);
grant select (seq, tenant_id, published_at) on masterdata.outbox to masterdata_sys;
grant delete on masterdata.outbox to masterdata_sys;
grant insert on masterdata.outbox to masterdata_sys;
create policy inbox_sys_select on masterdata.inbox for select to masterdata_sys using (true);
create policy inbox_sys_purge on masterdata.inbox for delete to masterdata_sys using (true);
grant select (consumer, event_id, received_at) on masterdata.inbox to masterdata_sys;
grant delete on masterdata.inbox to masterdata_sys;
create policy consumer_sequence_sys_select on masterdata.consumer_sequence for select to masterdata_sys using (true);
create policy consumer_sequence_sys_full_sync on masterdata.consumer_sequence for delete to masterdata_sys using (true);
grant select (consumer, source, tenant_id) on masterdata.consumer_sequence to masterdata_sys;
grant delete on masterdata.consumer_sequence to masterdata_sys;
create policy event_dead_letter_sys_select on masterdata.event_dead_letter for select to masterdata_sys using (true);
create policy event_dead_letter_sys_insert on masterdata.event_dead_letter for insert to masterdata_sys with check (true);
create policy event_dead_letter_sys_update on masterdata.event_dead_letter for update to masterdata_sys using (true) with check (true);
create policy event_dead_letter_sys_purge on masterdata.event_dead_letter for delete to masterdata_sys using (resolved_at is not null);
create policy event_dead_letter_sys_purge_triaged on masterdata.event_dead_letter as restrictive for delete to masterdata_sys
    using (resolved_at is not null and status <> 'OPEN');
grant select (id, consumer, topic, log_offset, tenant_id, reason, recorded_at, status, resolved_at, note, replayed_to) on masterdata.event_dead_letter to masterdata_sys;
grant insert on masterdata.event_dead_letter to masterdata_sys;
grant update (status, resolved_at, note, replayed_to) on masterdata.event_dead_letter to masterdata_sys;
grant delete on masterdata.event_dead_letter to masterdata_sys;
grant select, insert, update on masterdata.consumer_offset to masterdata_sys;
grant insert on masterdata.consumer_source_seen to masterdata_sys;
create view masterdata.outbox_claim_v as select seq, tenant_id, published_at from masterdata.outbox;
create view masterdata.event_dead_letter_triage_v as select id, consumer, topic, log_offset, tenant_id, reason, recorded_at, status, resolved_at, note, replayed_to from masterdata.event_dead_letter;
grant create on schema masterdata to masterdata_sys;
alter view masterdata.outbox_claim_v owner to masterdata_sys;
revoke all on masterdata.outbox_claim_v from public;
grant select on masterdata.outbox_claim_v to masterdata_runtime;
alter view masterdata.event_dead_letter_triage_v owner to masterdata_sys;
revoke all on masterdata.event_dead_letter_triage_v from public;
grant select on masterdata.event_dead_letter_triage_v to masterdata_runtime;
revoke create on schema masterdata from masterdata_sys;
create function masterdata.outbox_claim_page(after_seq bigint, page_size integer, unpublished_only boolean)
returns table (seq bigint, tenant_id uuid, published_at timestamptz)
language plpgsql stable security definer set search_path = pg_catalog, masterdata, pg_temp as $$
begin
    return query select v.seq, v.tenant_id, v.published_at from masterdata.outbox_claim_v v
        where v.seq > after_seq and (not unpublished_only or v.published_at is null)
        order by v.seq limit page_size;
end
$$;
create function masterdata.outbox_claim_pending() returns bigint
language plpgsql stable security definer set search_path = pg_catalog, masterdata, pg_temp as $$
begin
    return (select count(*) from masterdata.outbox_claim_v v where v.published_at is null);
end
$$;
grant create on schema masterdata to masterdata_sys;
alter function masterdata.outbox_claim_page(bigint, integer, boolean) owner to masterdata_sys;
revoke all on function masterdata.outbox_claim_page(bigint, integer, boolean) from public;
grant execute on function masterdata.outbox_claim_page(bigint, integer, boolean) to masterdata_runtime;
alter function masterdata.outbox_claim_pending() owner to masterdata_sys;
revoke all on function masterdata.outbox_claim_pending() from public;
grant execute on function masterdata.outbox_claim_pending() to masterdata_runtime;
revoke create on schema masterdata from masterdata_sys;
create function masterdata.consumer_offset_lock(p_consumer text, p_topic text) returns bigint
language plpgsql volatile security definer set search_path = pg_catalog, masterdata, pg_temp as $$
declare
    v_next bigint;
begin
    insert into masterdata.consumer_offset (consumer, topic, next_offset) values (p_consumer, p_topic, 1)
        on conflict do nothing;
    select o.next_offset into v_next from masterdata.consumer_offset o
        where o.consumer = p_consumer and o.topic = p_topic for update;
    if not found then
        raise exception 'offset row missing';
    end if;
    return v_next;
end
$$;
create function masterdata.consumer_offset_advance(p_consumer text, p_topic text, p_next_offset bigint) returns void
language plpgsql volatile security definer set search_path = pg_catalog, masterdata, pg_temp as $$
begin
    update masterdata.consumer_offset set next_offset = p_next_offset, attempts = 0, infra_failures = 0,
        first_infra_failure_at = null, retry_not_before = null
    where consumer = p_consumer and topic = p_topic;
end
$$;
create function masterdata.consumer_source_seen_record(p_consumer text, p_producer text, p_source text) returns void
language plpgsql volatile security definer set search_path = pg_catalog, masterdata, pg_temp as $$
begin
    insert into masterdata.consumer_source_seen (consumer, producer, source)
        values (p_consumer, p_producer, p_source) on conflict do nothing;
end
$$;
grant create on schema masterdata to masterdata_sys;
alter function masterdata.consumer_offset_lock(text, text) owner to masterdata_sys;
revoke all on function masterdata.consumer_offset_lock(text, text) from public;
grant execute on function masterdata.consumer_offset_lock(text, text) to masterdata_runtime;
alter function masterdata.consumer_offset_advance(text, text, bigint) owner to masterdata_sys;
revoke all on function masterdata.consumer_offset_advance(text, text, bigint) from public;
grant execute on function masterdata.consumer_offset_advance(text, text, bigint) to masterdata_runtime;
alter function masterdata.consumer_source_seen_record(text, text, text) owner to masterdata_sys;
revoke all on function masterdata.consumer_source_seen_record(text, text, text) from public;
grant execute on function masterdata.consumer_source_seen_record(text, text, text) to masterdata_runtime;
revoke create on schema masterdata from masterdata_sys;
create function masterdata.event_dead_letter_triage_page(p_after bigint, p_status text, p_ids bigint[], p_tenant uuid,
    p_consumer text, p_topic text, p_failure text, p_from timestamptz, p_before timestamptz,
    p_with_tenant boolean, p_platform uuid, p_limit integer)
returns table (id bigint, consumer text, topic text, log_offset bigint, tenant_id uuid, reason text,
    status text, recorded_at timestamptz, resolved_at timestamptz, note text, replayed_to text)
language plpgsql stable security definer set search_path = pg_catalog, masterdata, pg_temp as $$
begin
    if p_limit is null or p_limit < 1 or p_limit > 1000 then
        raise exception 'the triage page limit must be in 1..1000' using errcode = '22023';
    end if;
    return query select v.id, v.consumer, v.topic, v.log_offset, v.tenant_id, v.reason, v.status,
            v.recorded_at, v.resolved_at, v.note, v.replayed_to
        from masterdata.event_dead_letter_triage_v v
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
grant create on schema masterdata to masterdata_sys;
alter function masterdata.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) owner to masterdata_sys;
revoke all on function masterdata.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) from public;
grant execute on function masterdata.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) to masterdata_runtime;
revoke create on schema masterdata from masterdata_sys;
create function masterdata.outbox_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, masterdata, pg_temp as $$
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
    delete from masterdata.outbox where seq in
        (select seq from masterdata.outbox where published_at is not null and published_at < cutoff
         limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema masterdata to masterdata_sys;
alter function masterdata.outbox_purge_expired(timestamptz, integer) owner to masterdata_sys;
revoke all on function masterdata.outbox_purge_expired(timestamptz, integer) from public;
grant execute on function masterdata.outbox_purge_expired(timestamptz, integer) to masterdata_runtime;
revoke create on schema masterdata from masterdata_sys;
create function masterdata.inbox_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, masterdata, pg_temp as $$
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
    delete from masterdata.inbox where (consumer, event_id) in
        (select consumer, event_id from masterdata.inbox where received_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema masterdata to masterdata_sys;
alter function masterdata.inbox_purge_expired(timestamptz, integer) owner to masterdata_sys;
revoke all on function masterdata.inbox_purge_expired(timestamptz, integer) from public;
grant execute on function masterdata.inbox_purge_expired(timestamptz, integer) to masterdata_runtime;
revoke create on schema masterdata from masterdata_sys;
create function masterdata.event_dead_letter_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, masterdata, pg_temp as $$
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
    delete from masterdata.event_dead_letter where id in
        (select id from masterdata.event_dead_letter
         where status <> 'OPEN' and resolved_at is not null and resolved_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema masterdata to masterdata_sys;
alter function masterdata.event_dead_letter_purge_expired(timestamptz, integer) owner to masterdata_sys;
revoke all on function masterdata.event_dead_letter_purge_expired(timestamptz, integer) from public;
grant execute on function masterdata.event_dead_letter_purge_expired(timestamptz, integer) to masterdata_runtime;
revoke create on schema masterdata from masterdata_sys;
create function masterdata.full_sync_delete(p_consumer text, p_source text) returns bigint
language plpgsql security definer set search_path = pg_catalog, masterdata, pg_temp as $$
declare
    deleted bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'
        or p_source is null or p_source ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$' then
        raise exception 'a full sync names one consumer and one source' using errcode = '22023';
    end if;
    delete from masterdata.consumer_sequence where consumer = p_consumer and source = p_source;
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema masterdata to masterdata_sys;
alter function masterdata.full_sync_delete(text, text) owner to masterdata_sys;
revoke all on function masterdata.full_sync_delete(text, text) from public;
grant execute on function masterdata.full_sync_delete(text, text) to masterdata_runtime;
revoke create on schema masterdata from masterdata_sys;
create function masterdata.event_dead_letter_record(p_consumer text, p_topic text, p_log_offset bigint, p_tenant_id uuid,
    p_reason text, p_raw text) returns bigint
language plpgsql security definer set search_path = pg_catalog, masterdata, pg_temp as $$
declare
    recorded bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'
        or p_topic is null or p_log_offset is null
        or p_reason is null or p_raw is null then
        raise exception 'a dead letter names its consumer, topic, offset, reason and record'
            using errcode = '22023';
    end if;
    insert into masterdata.event_dead_letter (consumer, topic, log_offset, tenant_id, reason, raw)
    values (p_consumer, p_topic, p_log_offset, coalesce(p_tenant_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid), p_reason, p_raw)
    returning id into recorded;
    return recorded;
end
$$;
grant create on schema masterdata to masterdata_sys;
alter function masterdata.event_dead_letter_record(text, text, bigint, uuid, text, text) owner to masterdata_sys;
revoke all on function masterdata.event_dead_letter_record(text, text, bigint, uuid, text, text) from public;
grant execute on function masterdata.event_dead_letter_record(text, text, bigint, uuid, text, text) to masterdata_runtime;
revoke create on schema masterdata from masterdata_sys;
create function masterdata.event_dead_letter_resolve(p_id bigint, p_status text, p_resolved_at timestamptz, p_note text,
    p_replayed_to text) returns boolean
language plpgsql security definer set search_path = pg_catalog, masterdata, pg_temp as $$
begin
    if p_status is null or p_status not in ('REPLAYED', 'DISCARDED') or p_resolved_at is null then
        raise exception 'a dead letter is resolved as REPLAYED or DISCARDED at a time' using errcode = '22023';
    end if;
    update masterdata.event_dead_letter set status = p_status, resolved_at = p_resolved_at, note = p_note,
        replayed_to = p_replayed_to
    where id = p_id and status = 'OPEN';
    return found;
end
$$;
grant create on schema masterdata to masterdata_sys;
alter function masterdata.event_dead_letter_resolve(bigint, text, timestamptz, text, text) owner to masterdata_sys;
revoke all on function masterdata.event_dead_letter_resolve(bigint, text, timestamptz, text, text) from public;
grant execute on function masterdata.event_dead_letter_resolve(bigint, text, timestamptz, text, text) to masterdata_runtime;
revoke create on schema masterdata from masterdata_sys;