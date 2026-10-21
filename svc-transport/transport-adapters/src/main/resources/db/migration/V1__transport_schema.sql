-- V1__transport_schema.sql (TPL-12; INV-T-4981, INV-REQ-538, INV-REQ-1317).
--
-- Roles (INV-AC-2175): transport_migrate owns the tables and runs this file; transport_runtime logs in;
-- transport_sys is NOLOGIN. No role named "transport" exists.
-- Precondition (QueueSchema.ddl KDoc, coordinator rulings INV-Q-6586 and INV-Q-6663): transport.bound_tenant() and
-- transport.bound_actor() already exist in the schema, installed once per schema by the sys login from
-- tenancy-kernel's BindingKeyMigration.ddl; this file does not run that generator and does not switch role.
-- The schema itself is created by Flyway (the `schemas` setting) or by the installer, owned by transport_migrate.
-- The Access Point connection record's rate fields (INV-REQ-1317): the rate agreed with the AP, captured at connection
-- set-up, and an optional lower tenant value (tighten-only: the check refuses a value above the agreed rate).
create table transport.ap_connection (
    tenant_id uuid not null,
    adapter_id text not null check (char_length(adapter_id) between 1 and 40),
    agreed_rate numeric(12, 3) not null check (agreed_rate > 0),
    tenant_ceiling numeric(12, 3) check (tenant_ceiling > 0 and tenant_ceiling <= agreed_rate),
    version bigint not null default 0,
    primary key (tenant_id, adapter_id)
);
-- The adapter an entity submits through (INV-REQ-796): at most one per entity of a tenant.
create table transport.entity_adapter (
    tenant_id uuid not null,
    entity_ref text not null check (char_length(entity_ref) between 1 and 40),
    adapter_id text not null check (char_length(adapter_id) between 1 and 40),
    assigned_at timestamptz not null,
    primary key (tenant_id, entity_ref)
);
-- The attempt log (INV-REQ-538): one row per call of the Access Point port.
create table transport.submission_attempt (
    id uuid primary key,
    tenant_id uuid not null,
    entity_ref text not null,
    doc_uuid uuid not null,
    version integer not null check (version >= 1),
    adapter_id text not null,
    started_at timestamptz not null,
    finished_at timestamptz,
    outcome text check (outcome in ('ACCEPTED', 'REJECTED', 'TRANSIENT_FAILURE')),
    acknowledgement_id text,
    reasons text,
    check ((outcome is null) = (finished_at is null))
);
comment on column transport.submission_attempt.reasons is 'class: operational; JSON list of {code, field, message}';
create index submission_attempt_doc on transport.submission_attempt (tenant_id, doc_uuid, version);
create index submission_attempt_adapter_started on transport.submission_attempt (adapter_id, started_at);
-- One egress record per attempt: the single send of the attempt through its adapter.
create table transport.attempt_egress (
    id uuid primary key,
    tenant_id uuid not null,
    attempt_id uuid not null references transport.submission_attempt (id),
    adapter_id text not null,
    sent_at timestamptz not null
);
create index attempt_egress_attempt on transport.attempt_egress (attempt_id);

alter table transport.ap_connection enable row level security;
alter table transport.ap_connection force row level security;
create policy ap_connection_tenant_isolation on transport.ap_connection
    using      (tenant_id = (select transport.bound_tenant()))
    with check (tenant_id = (select transport.bound_tenant()));
alter table transport.entity_adapter enable row level security;
alter table transport.entity_adapter force row level security;
create policy entity_adapter_tenant_isolation on transport.entity_adapter
    using      (tenant_id = (select transport.bound_tenant()))
    with check (tenant_id = (select transport.bound_tenant()));
alter table transport.submission_attempt enable row level security;
alter table transport.submission_attempt force row level security;
create policy submission_attempt_tenant_isolation on transport.submission_attempt
    using      (tenant_id = (select transport.bound_tenant()))
    with check (tenant_id = (select transport.bound_tenant()));
alter table transport.attempt_egress enable row level security;
alter table transport.attempt_egress force row level security;
create policy attempt_egress_tenant_isolation on transport.attempt_egress
    using      (tenant_id = (select transport.bound_tenant()))
    with check (tenant_id = (select transport.bound_tenant()));
grant usage on schema transport to transport_runtime;
grant select, insert, update on transport.ap_connection, transport.entity_adapter, transport.submission_attempt to transport_runtime;
grant select, insert on transport.attempt_egress to transport_runtime;

-- OutboxSchema.ddl("transport", "transport_runtime", "transport_sys")
create table transport.outbox (
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
create index outbox_unpublished on transport.outbox (seq) where published_at is null;
create index outbox_published_at on transport.outbox (published_at) where published_at is not null;
create table transport.outbox_sequence (
    tenant_id uuid not null,
    aggregate_key text not null,
    last_value bigint not null,
    primary key (tenant_id, aggregate_key)
);
create table transport.inbox (
    consumer text not null,
    event_id text not null,
    tenant_id uuid not null,
    received_at timestamptz not null default now(),
    primary key (consumer, event_id)
);
create index inbox_received_at on transport.inbox (received_at);
create table transport.consumer_sequence (
    consumer text not null,
    source text not null,
    subject text not null,
    tenant_id uuid not null,
    last_value bigint not null,
    primary key (consumer, tenant_id, source, subject)
);
create table transport.consumer_offset (
    consumer text not null,
    topic text not null,
    next_offset bigint not null,
    attempts int not null default 0,
    infra_failures int not null default 0,
    first_infra_failure_at timestamptz,
    retry_not_before timestamptz,
    primary key (consumer, topic)
);
comment on table transport.consumer_offset is 'platform-scoped: no tenant data';
create table transport.event_dead_letter (
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
create index event_dead_letter_resolved_at on transport.event_dead_letter (resolved_at) where resolved_at is not null;
comment on column transport.outbox.data is 'class: content';
comment on column transport.event_dead_letter.raw is 'class: content';
ALTER TABLE transport.outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport.outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_tenant_isolation ON transport.outbox
    USING      (tenant_id = (select transport.bound_tenant()))
    WITH CHECK (tenant_id = (select transport.bound_tenant()));
ALTER TABLE transport.outbox_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport.outbox_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_sequence_tenant_isolation ON transport.outbox_sequence
    USING      (tenant_id = (select transport.bound_tenant()))
    WITH CHECK (tenant_id = (select transport.bound_tenant()));
ALTER TABLE transport.inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport.inbox FORCE ROW LEVEL SECURITY;
CREATE POLICY inbox_tenant_isolation ON transport.inbox
    USING      (tenant_id = (select transport.bound_tenant()))
    WITH CHECK (tenant_id = (select transport.bound_tenant()));
ALTER TABLE transport.consumer_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport.consumer_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY consumer_sequence_tenant_isolation ON transport.consumer_sequence
    USING      (tenant_id = (select transport.bound_tenant()))
    WITH CHECK (tenant_id = (select transport.bound_tenant()));
ALTER TABLE transport.event_dead_letter ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport.event_dead_letter FORCE ROW LEVEL SECURITY;
CREATE POLICY event_dead_letter_tenant_isolation ON transport.event_dead_letter
    USING      (tenant_id = (select transport.bound_tenant()))
    WITH CHECK (tenant_id = (select transport.bound_tenant()));
create table if not exists transport.consumer_replay (
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
comment on table transport.consumer_replay is 'platform-scoped: no tenant data';
create unique index if not exists consumer_replay_pending on transport.consumer_replay (consumer, producer)
    where closed_at is null;
create table if not exists transport.consumer_replay_marker (
    replay_id uuid not null,
    source text not null,
    applied_at timestamptz not null default now(),
    primary key (replay_id, source)
);
comment on table transport.consumer_replay_marker is 'platform-scoped: no tenant data';
create table if not exists transport.consumer_source_seen (
    consumer text not null,
    producer text not null,
    source text not null,
    primary key (consumer, producer, source)
);
comment on table transport.consumer_source_seen is 'platform-scoped: no tenant data';
create function transport.outbox_publish_once() returns trigger
language plpgsql set search_path = pg_catalog, transport, pg_temp as $$
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
revoke all on function transport.outbox_publish_once() from public;
create trigger outbox_publish_once before update on transport.outbox
    for each row execute function transport.outbox_publish_once();
grant usage on schema transport to transport_runtime;
grant select, insert, update (published_at) on transport.outbox to transport_runtime;
grant select, insert, update on transport.outbox_sequence, transport.consumer_sequence, transport.consumer_offset to transport_runtime;
grant select, insert on transport.inbox to transport_runtime;
grant select on transport.event_dead_letter to transport_runtime;
create policy event_dead_letter_no_platform_tenant on transport.event_dead_letter as restrictive for select to transport_runtime
    using (tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
grant select, insert, update on transport.consumer_replay, transport.consumer_replay_marker, transport.consumer_source_seen
    to transport_runtime;
grant usage on schema transport to transport_sys;
create policy outbox_sys_select on transport.outbox for select to transport_sys using (true);
create policy outbox_sys_purge on transport.outbox for delete to transport_sys using (published_at is not null);
create policy outbox_sys_purge_published on transport.outbox as restrictive for delete to transport_sys
    using (published_at is not null);
grant select (seq, tenant_id, published_at) on transport.outbox to transport_sys;
grant delete on transport.outbox to transport_sys;
grant insert on transport.outbox to transport_sys;
create policy inbox_sys_select on transport.inbox for select to transport_sys using (true);
create policy inbox_sys_purge on transport.inbox for delete to transport_sys using (true);
grant select (consumer, event_id, received_at) on transport.inbox to transport_sys;
grant delete on transport.inbox to transport_sys;
create policy consumer_sequence_sys_select on transport.consumer_sequence for select to transport_sys using (true);
create policy consumer_sequence_sys_full_sync on transport.consumer_sequence for delete to transport_sys using (true);
grant select (consumer, source, tenant_id) on transport.consumer_sequence to transport_sys;
grant delete on transport.consumer_sequence to transport_sys;
create policy event_dead_letter_sys_select on transport.event_dead_letter for select to transport_sys using (true);
create policy event_dead_letter_sys_insert on transport.event_dead_letter for insert to transport_sys with check (true);
create policy event_dead_letter_sys_update on transport.event_dead_letter for update to transport_sys using (true) with check (true);
create policy event_dead_letter_sys_purge on transport.event_dead_letter for delete to transport_sys using (resolved_at is not null);
create policy event_dead_letter_sys_purge_triaged on transport.event_dead_letter as restrictive for delete to transport_sys
    using (resolved_at is not null and status <> 'OPEN');
grant select (id, consumer, topic, log_offset, tenant_id, reason, recorded_at, status, resolved_at, note, replayed_to) on transport.event_dead_letter to transport_sys;
grant insert on transport.event_dead_letter to transport_sys;
grant update (status, resolved_at, note, replayed_to) on transport.event_dead_letter to transport_sys;
grant delete on transport.event_dead_letter to transport_sys;
grant select, insert, update on transport.consumer_offset to transport_sys;
grant insert on transport.consumer_source_seen to transport_sys;
create view transport.outbox_claim_v as select seq, tenant_id, published_at from transport.outbox;
create view transport.event_dead_letter_triage_v as select id, consumer, topic, log_offset, tenant_id, reason, recorded_at, status, resolved_at, note, replayed_to from transport.event_dead_letter;
grant create on schema transport to transport_sys;
alter view transport.outbox_claim_v owner to transport_sys;
revoke all on transport.outbox_claim_v from public;
grant select on transport.outbox_claim_v to transport_runtime;
alter view transport.event_dead_letter_triage_v owner to transport_sys;
revoke all on transport.event_dead_letter_triage_v from public;
grant select on transport.event_dead_letter_triage_v to transport_runtime;
revoke create on schema transport from transport_sys;
create function transport.outbox_claim_page(after_seq bigint, page_size integer, unpublished_only boolean)
returns table (seq bigint, tenant_id uuid, published_at timestamptz)
language plpgsql stable security definer set search_path = pg_catalog, transport, pg_temp as $$
begin
    return query select v.seq, v.tenant_id, v.published_at from transport.outbox_claim_v v
        where v.seq > after_seq and (not unpublished_only or v.published_at is null)
        order by v.seq limit page_size;
end
$$;
create function transport.outbox_claim_pending() returns bigint
language plpgsql stable security definer set search_path = pg_catalog, transport, pg_temp as $$
begin
    return (select count(*) from transport.outbox_claim_v v where v.published_at is null);
end
$$;
grant create on schema transport to transport_sys;
alter function transport.outbox_claim_page(bigint, integer, boolean) owner to transport_sys;
revoke all on function transport.outbox_claim_page(bigint, integer, boolean) from public;
grant execute on function transport.outbox_claim_page(bigint, integer, boolean) to transport_runtime;
alter function transport.outbox_claim_pending() owner to transport_sys;
revoke all on function transport.outbox_claim_pending() from public;
grant execute on function transport.outbox_claim_pending() to transport_runtime;
revoke create on schema transport from transport_sys;
create function transport.consumer_offset_lock(p_consumer text, p_topic text) returns bigint
language plpgsql volatile security definer set search_path = pg_catalog, transport, pg_temp as $$
declare
    v_next bigint;
begin
    insert into transport.consumer_offset (consumer, topic, next_offset) values (p_consumer, p_topic, 1)
        on conflict do nothing;
    select o.next_offset into v_next from transport.consumer_offset o
        where o.consumer = p_consumer and o.topic = p_topic for update;
    if not found then
        raise exception 'offset row missing';
    end if;
    return v_next;
end
$$;
create function transport.consumer_offset_advance(p_consumer text, p_topic text, p_next_offset bigint) returns void
language plpgsql volatile security definer set search_path = pg_catalog, transport, pg_temp as $$
begin
    update transport.consumer_offset set next_offset = p_next_offset, attempts = 0, infra_failures = 0,
        first_infra_failure_at = null, retry_not_before = null
    where consumer = p_consumer and topic = p_topic;
end
$$;
create function transport.consumer_source_seen_record(p_consumer text, p_producer text, p_source text) returns void
language plpgsql volatile security definer set search_path = pg_catalog, transport, pg_temp as $$
begin
    insert into transport.consumer_source_seen (consumer, producer, source)
        values (p_consumer, p_producer, p_source) on conflict do nothing;
end
$$;
grant create on schema transport to transport_sys;
alter function transport.consumer_offset_lock(text, text) owner to transport_sys;
revoke all on function transport.consumer_offset_lock(text, text) from public;
grant execute on function transport.consumer_offset_lock(text, text) to transport_runtime;
alter function transport.consumer_offset_advance(text, text, bigint) owner to transport_sys;
revoke all on function transport.consumer_offset_advance(text, text, bigint) from public;
grant execute on function transport.consumer_offset_advance(text, text, bigint) to transport_runtime;
alter function transport.consumer_source_seen_record(text, text, text) owner to transport_sys;
revoke all on function transport.consumer_source_seen_record(text, text, text) from public;
grant execute on function transport.consumer_source_seen_record(text, text, text) to transport_runtime;
revoke create on schema transport from transport_sys;
create function transport.event_dead_letter_triage_page(p_after bigint, p_status text, p_ids bigint[], p_tenant uuid,
    p_consumer text, p_topic text, p_failure text, p_from timestamptz, p_before timestamptz,
    p_with_tenant boolean, p_platform uuid, p_limit integer)
returns table (id bigint, consumer text, topic text, log_offset bigint, tenant_id uuid, reason text,
    status text, recorded_at timestamptz, resolved_at timestamptz, note text, replayed_to text)
language plpgsql stable security definer set search_path = pg_catalog, transport, pg_temp as $$
begin
    if p_limit is null or p_limit < 1 or p_limit > 1000 then
        raise exception 'the triage page limit must be in 1..1000' using errcode = '22023';
    end if;
    return query select v.id, v.consumer, v.topic, v.log_offset, v.tenant_id, v.reason, v.status,
            v.recorded_at, v.resolved_at, v.note, v.replayed_to
        from transport.event_dead_letter_triage_v v
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
grant create on schema transport to transport_sys;
alter function transport.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) owner to transport_sys;
revoke all on function transport.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) from public;
grant execute on function transport.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) to transport_runtime;
revoke create on schema transport from transport_sys;
create function transport.outbox_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, transport, pg_temp as $$
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
    delete from transport.outbox where seq in
        (select seq from transport.outbox where published_at is not null and published_at < cutoff
         limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema transport to transport_sys;
alter function transport.outbox_purge_expired(timestamptz, integer) owner to transport_sys;
revoke all on function transport.outbox_purge_expired(timestamptz, integer) from public;
grant execute on function transport.outbox_purge_expired(timestamptz, integer) to transport_runtime;
revoke create on schema transport from transport_sys;
create function transport.inbox_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, transport, pg_temp as $$
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
    delete from transport.inbox where (consumer, event_id) in
        (select consumer, event_id from transport.inbox where received_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema transport to transport_sys;
alter function transport.inbox_purge_expired(timestamptz, integer) owner to transport_sys;
revoke all on function transport.inbox_purge_expired(timestamptz, integer) from public;
grant execute on function transport.inbox_purge_expired(timestamptz, integer) to transport_runtime;
revoke create on schema transport from transport_sys;
create function transport.event_dead_letter_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, transport, pg_temp as $$
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
    delete from transport.event_dead_letter where id in
        (select id from transport.event_dead_letter
         where status <> 'OPEN' and resolved_at is not null and resolved_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema transport to transport_sys;
alter function transport.event_dead_letter_purge_expired(timestamptz, integer) owner to transport_sys;
revoke all on function transport.event_dead_letter_purge_expired(timestamptz, integer) from public;
grant execute on function transport.event_dead_letter_purge_expired(timestamptz, integer) to transport_runtime;
revoke create on schema transport from transport_sys;
create function transport.full_sync_delete(p_consumer text, p_source text) returns bigint
language plpgsql security definer set search_path = pg_catalog, transport, pg_temp as $$
declare
    deleted bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'
        or p_source is null or p_source ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$' then
        raise exception 'a full sync names one consumer and one source' using errcode = '22023';
    end if;
    delete from transport.consumer_sequence where consumer = p_consumer and source = p_source;
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema transport to transport_sys;
alter function transport.full_sync_delete(text, text) owner to transport_sys;
revoke all on function transport.full_sync_delete(text, text) from public;
grant execute on function transport.full_sync_delete(text, text) to transport_runtime;
revoke create on schema transport from transport_sys;
create function transport.event_dead_letter_record(p_consumer text, p_topic text, p_log_offset bigint, p_tenant_id uuid,
    p_reason text, p_raw text) returns bigint
language plpgsql security definer set search_path = pg_catalog, transport, pg_temp as $$
declare
    recorded bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'
        or p_topic is null or p_log_offset is null
        or p_reason is null or p_raw is null then
        raise exception 'a dead letter names its consumer, topic, offset, reason and record'
            using errcode = '22023';
    end if;
    insert into transport.event_dead_letter (consumer, topic, log_offset, tenant_id, reason, raw)
    values (p_consumer, p_topic, p_log_offset, coalesce(p_tenant_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid), p_reason, p_raw)
    returning id into recorded;
    return recorded;
end
$$;
grant create on schema transport to transport_sys;
alter function transport.event_dead_letter_record(text, text, bigint, uuid, text, text) owner to transport_sys;
revoke all on function transport.event_dead_letter_record(text, text, bigint, uuid, text, text) from public;
grant execute on function transport.event_dead_letter_record(text, text, bigint, uuid, text, text) to transport_runtime;
revoke create on schema transport from transport_sys;
create function transport.event_dead_letter_resolve(p_id bigint, p_status text, p_resolved_at timestamptz, p_note text,
    p_replayed_to text) returns boolean
language plpgsql security definer set search_path = pg_catalog, transport, pg_temp as $$
begin
    if p_status is null or p_status not in ('REPLAYED', 'DISCARDED') or p_resolved_at is null then
        raise exception 'a dead letter is resolved as REPLAYED or DISCARDED at a time' using errcode = '22023';
    end if;
    update transport.event_dead_letter set status = p_status, resolved_at = p_resolved_at, note = p_note,
        replayed_to = p_replayed_to
    where id = p_id and status = 'OPEN';
    return found;
end
$$;
grant create on schema transport to transport_sys;
alter function transport.event_dead_letter_resolve(bigint, text, timestamptz, text, text) owner to transport_sys;
revoke all on function transport.event_dead_letter_resolve(bigint, text, timestamptz, text, text) from public;
grant execute on function transport.event_dead_letter_resolve(bigint, text, timestamptz, text, text) to transport_runtime;
revoke create on schema transport from transport_sys;
-- The failure rate per adapter is counted across tenants and carries no tenant data (INV-REQ-565): a sys-owned
-- function reads the finished attempts through a sys-only select policy, and the runtime login may only call it.
create policy submission_attempt_sys_count on transport.submission_attempt for select to transport_sys using (true);
grant select (adapter_id, started_at, outcome) on transport.submission_attempt to transport_sys;
grant create on schema transport to transport_sys;
create function transport.adapter_attempt_counts(p_adapter text, p_since timestamptz)
returns table (total bigint, failed bigint)
language sql stable security definer set search_path = pg_catalog, transport, pg_temp as $$
    select count(*), count(*) filter (where outcome <> 'ACCEPTED')
    from transport.submission_attempt
    where adapter_id = p_adapter and started_at >= p_since and outcome is not null
$$;
alter function transport.adapter_attempt_counts(text, timestamptz) owner to transport_sys;
revoke all on function transport.adapter_attempt_counts(text, timestamptz) from public;
grant execute on function transport.adapter_attempt_counts(text, timestamptz) to transport_runtime;
revoke create on schema transport from transport_sys;
