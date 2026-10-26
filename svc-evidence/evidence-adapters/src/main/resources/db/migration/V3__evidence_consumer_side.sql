-- Generated: OutboxSchema.ddl("evidence", "evidence_runtime", "evidence_sys") of the pinned platform-libs version
-- (INV-T-7218, coordinator ruling INV-Q-10812 A): the statements V1__evidence_schema.sql does not contain, in the
-- generator order, i.e. the consumer half (inbox, consumer_sequence, consumer_offset, event_dead_letter, the replay
-- tables and the functions around them). Not written by hand; ConsumerSideMigrationTest compares the schema V1 plus
-- this file builds with the generator output. V1 is not edited. The grant on outbox_sequence is the generator's full
-- statement: V1 holds only its producer part, and granting again what V1 granted changes nothing.
create table evidence.inbox (
    consumer text not null,
    event_id text not null,
    tenant_id uuid not null,
    received_at timestamptz not null default now(),
    primary key (consumer, event_id)
);
create index inbox_received_at on evidence.inbox (received_at);
create table evidence.consumer_sequence (
    consumer text not null,
    source text not null,
    subject text not null,
    tenant_id uuid not null,
    last_value bigint not null,
    primary key (consumer, tenant_id, source, subject)
);
create table evidence.consumer_offset (
    consumer text not null,
    topic text not null,
    next_offset bigint not null,
    attempts int not null default 0,
    infra_failures int not null default 0,
    first_infra_failure_at timestamptz,
    retry_not_before timestamptz,
    primary key (consumer, topic)
);
comment on table evidence.consumer_offset is 'platform-scoped: no tenant data';
create table evidence.event_dead_letter (
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
create index event_dead_letter_resolved_at on evidence.event_dead_letter (resolved_at) where resolved_at is not null;
comment on column evidence.event_dead_letter.raw is 'class: content';
ALTER TABLE evidence.inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.inbox FORCE ROW LEVEL SECURITY;
CREATE POLICY inbox_tenant_isolation ON evidence.inbox
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
ALTER TABLE evidence.consumer_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.consumer_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY consumer_sequence_tenant_isolation ON evidence.consumer_sequence
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
ALTER TABLE evidence.event_dead_letter ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.event_dead_letter FORCE ROW LEVEL SECURITY;
CREATE POLICY event_dead_letter_tenant_isolation ON evidence.event_dead_letter
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
create table if not exists evidence.consumer_replay (
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
comment on table evidence.consumer_replay is 'platform-scoped: no tenant data';
create unique index if not exists consumer_replay_pending on evidence.consumer_replay (consumer, producer)
    where closed_at is null;
create table if not exists evidence.consumer_replay_marker (
    replay_id uuid not null,
    source text not null,
    applied_at timestamptz not null default now(),
    primary key (replay_id, source)
);
comment on table evidence.consumer_replay_marker is 'platform-scoped: no tenant data';
create table if not exists evidence.consumer_source_seen (
    consumer text not null,
    producer text not null,
    source text not null,
    primary key (consumer, producer, source)
);
comment on table evidence.consumer_source_seen is 'platform-scoped: no tenant data';
grant select, insert, update on evidence.outbox_sequence, evidence.consumer_sequence, evidence.consumer_offset to evidence_runtime;
grant select, insert on evidence.inbox to evidence_runtime;
grant select on evidence.event_dead_letter to evidence_runtime;
create policy event_dead_letter_no_platform_tenant on evidence.event_dead_letter as restrictive for select to evidence_runtime
    using (tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
grant select, insert, update on evidence.consumer_replay, evidence.consumer_replay_marker, evidence.consumer_source_seen
    to evidence_runtime;
create policy inbox_sys_select on evidence.inbox for select to evidence_sys using (true);
create policy inbox_sys_purge on evidence.inbox for delete to evidence_sys using (true);
grant select (consumer, event_id, received_at) on evidence.inbox to evidence_sys;
grant delete on evidence.inbox to evidence_sys;
create policy consumer_sequence_sys_select on evidence.consumer_sequence for select to evidence_sys using (true);
create policy consumer_sequence_sys_full_sync on evidence.consumer_sequence for delete to evidence_sys using (true);
grant select (consumer, source, tenant_id) on evidence.consumer_sequence to evidence_sys;
grant delete on evidence.consumer_sequence to evidence_sys;
create policy event_dead_letter_sys_select on evidence.event_dead_letter for select to evidence_sys using (true);
create policy event_dead_letter_sys_insert on evidence.event_dead_letter for insert to evidence_sys with check (true);
create policy event_dead_letter_sys_update on evidence.event_dead_letter for update to evidence_sys using (true) with check (true);
create policy event_dead_letter_sys_purge on evidence.event_dead_letter for delete to evidence_sys using (resolved_at is not null);
create policy event_dead_letter_sys_purge_triaged on evidence.event_dead_letter as restrictive for delete to evidence_sys
    using (resolved_at is not null and status <> 'OPEN');
grant select (id, consumer, topic, log_offset, tenant_id, reason, recorded_at, status, resolved_at, note, replayed_to) on evidence.event_dead_letter to evidence_sys;
grant insert on evidence.event_dead_letter to evidence_sys;
grant update (status, resolved_at, note, replayed_to) on evidence.event_dead_letter to evidence_sys;
grant delete on evidence.event_dead_letter to evidence_sys;
grant select, insert, update on evidence.consumer_offset to evidence_sys;
grant insert on evidence.consumer_source_seen to evidence_sys;
create view evidence.event_dead_letter_triage_v as select id, consumer, topic, log_offset, tenant_id, reason, recorded_at, status, resolved_at, note, replayed_to from evidence.event_dead_letter;
grant create on schema evidence to evidence_sys;
alter view evidence.event_dead_letter_triage_v owner to evidence_sys;
revoke all on evidence.event_dead_letter_triage_v from public;
grant select on evidence.event_dead_letter_triage_v to evidence_runtime;
revoke create on schema evidence from evidence_sys;
create function evidence.consumer_offset_lock(p_consumer text, p_topic text) returns bigint
language plpgsql volatile security definer set search_path = pg_catalog, evidence, pg_temp as $$
declare
    v_next bigint;
begin
    insert into evidence.consumer_offset (consumer, topic, next_offset) values (p_consumer, p_topic, 1)
        on conflict do nothing;
    select o.next_offset into v_next from evidence.consumer_offset o
        where o.consumer = p_consumer and o.topic = p_topic for update;
    if not found then
        raise exception 'offset row missing';
    end if;
    return v_next;
end
$$;
create function evidence.consumer_offset_advance(p_consumer text, p_topic text, p_next_offset bigint) returns void
language plpgsql volatile security definer set search_path = pg_catalog, evidence, pg_temp as $$
begin
    update evidence.consumer_offset set next_offset = p_next_offset, attempts = 0, infra_failures = 0,
        first_infra_failure_at = null, retry_not_before = null
    where consumer = p_consumer and topic = p_topic;
end
$$;
create function evidence.consumer_source_seen_record(p_consumer text, p_producer text, p_source text) returns void
language plpgsql volatile security definer set search_path = pg_catalog, evidence, pg_temp as $$
begin
    insert into evidence.consumer_source_seen (consumer, producer, source)
        values (p_consumer, p_producer, p_source) on conflict do nothing;
end
$$;
grant create on schema evidence to evidence_sys;
alter function evidence.consumer_offset_lock(text, text) owner to evidence_sys;
revoke all on function evidence.consumer_offset_lock(text, text) from public;
grant execute on function evidence.consumer_offset_lock(text, text) to evidence_runtime;
alter function evidence.consumer_offset_advance(text, text, bigint) owner to evidence_sys;
revoke all on function evidence.consumer_offset_advance(text, text, bigint) from public;
grant execute on function evidence.consumer_offset_advance(text, text, bigint) to evidence_runtime;
alter function evidence.consumer_source_seen_record(text, text, text) owner to evidence_sys;
revoke all on function evidence.consumer_source_seen_record(text, text, text) from public;
grant execute on function evidence.consumer_source_seen_record(text, text, text) to evidence_runtime;
revoke create on schema evidence from evidence_sys;
create function evidence.event_dead_letter_triage_page(p_after bigint, p_status text, p_ids bigint[], p_tenant uuid,
    p_consumer text, p_topic text, p_failure text, p_from timestamptz, p_before timestamptz,
    p_with_tenant boolean, p_platform uuid, p_limit integer)
returns table (id bigint, consumer text, topic text, log_offset bigint, tenant_id uuid, reason text,
    status text, recorded_at timestamptz, resolved_at timestamptz, note text, replayed_to text)
language plpgsql stable security definer set search_path = pg_catalog, evidence, pg_temp as $$
begin
    if p_limit is null or p_limit < 1 or p_limit > 1000 then
        raise exception 'the triage page limit must be in 1..1000' using errcode = '22023';
    end if;
    return query select v.id, v.consumer, v.topic, v.log_offset, v.tenant_id, v.reason, v.status,
            v.recorded_at, v.resolved_at, v.note, v.replayed_to
        from evidence.event_dead_letter_triage_v v
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
grant create on schema evidence to evidence_sys;
alter function evidence.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) owner to evidence_sys;
revoke all on function evidence.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) from public;
grant execute on function evidence.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) to evidence_runtime;
revoke create on schema evidence from evidence_sys;
create function evidence.inbox_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
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
    delete from evidence.inbox where (consumer, event_id) in
        (select consumer, event_id from evidence.inbox where received_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema evidence to evidence_sys;
alter function evidence.inbox_purge_expired(timestamptz, integer) owner to evidence_sys;
revoke all on function evidence.inbox_purge_expired(timestamptz, integer) from public;
grant execute on function evidence.inbox_purge_expired(timestamptz, integer) to evidence_runtime;
revoke create on schema evidence from evidence_sys;
create function evidence.event_dead_letter_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
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
    delete from evidence.event_dead_letter where id in
        (select id from evidence.event_dead_letter
         where status <> 'OPEN' and resolved_at is not null and resolved_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema evidence to evidence_sys;
alter function evidence.event_dead_letter_purge_expired(timestamptz, integer) owner to evidence_sys;
revoke all on function evidence.event_dead_letter_purge_expired(timestamptz, integer) from public;
grant execute on function evidence.event_dead_letter_purge_expired(timestamptz, integer) to evidence_runtime;
revoke create on schema evidence from evidence_sys;
create function evidence.full_sync_delete(p_consumer text, p_source text) returns bigint
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
declare
    deleted bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        or p_source is null or p_source ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$' then
        raise exception 'a full sync names one consumer and one source' using errcode = '22023';
    end if;
    delete from evidence.consumer_sequence where consumer = p_consumer and source = p_source;
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema evidence to evidence_sys;
alter function evidence.full_sync_delete(text, text) owner to evidence_sys;
revoke all on function evidence.full_sync_delete(text, text) from public;
grant execute on function evidence.full_sync_delete(text, text) to evidence_runtime;
revoke create on schema evidence from evidence_sys;
create function evidence.event_dead_letter_record(p_consumer text, p_topic text, p_log_offset bigint, p_tenant_id uuid,
    p_reason text, p_raw text) returns bigint
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
declare
    recorded bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        or p_topic is null or p_log_offset is null
        or p_reason is null or p_raw is null then
        raise exception 'a dead letter names its consumer, topic, offset, reason and record'
            using errcode = '22023';
    end if;
    insert into evidence.event_dead_letter (consumer, topic, log_offset, tenant_id, reason, raw)
    values (p_consumer, p_topic, p_log_offset, coalesce(p_tenant_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid), p_reason, p_raw)
    returning id into recorded;
    return recorded;
end
$$;
grant create on schema evidence to evidence_sys;
alter function evidence.event_dead_letter_record(text, text, bigint, uuid, text, text) owner to evidence_sys;
revoke all on function evidence.event_dead_letter_record(text, text, bigint, uuid, text, text) from public;
grant execute on function evidence.event_dead_letter_record(text, text, bigint, uuid, text, text) to evidence_runtime;
revoke create on schema evidence from evidence_sys;
create function evidence.event_dead_letter_resolve(p_id bigint, p_status text, p_resolved_at timestamptz, p_note text,
    p_replayed_to text) returns boolean
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
begin
    if p_status is null or p_status not in ('REPLAYED', 'DISCARDED') or p_resolved_at is null then
        raise exception 'a dead letter is resolved as REPLAYED or DISCARDED at a time' using errcode = '22023';
    end if;
    update evidence.event_dead_letter set status = p_status, resolved_at = p_resolved_at, note = p_note,
        replayed_to = p_replayed_to
    where id = p_id and status = 'OPEN';
    return found;
end
$$;
grant create on schema evidence to evidence_sys;
alter function evidence.event_dead_letter_resolve(bigint, text, timestamptz, text, text) owner to evidence_sys;
revoke all on function evidence.event_dead_letter_resolve(bigint, text, timestamptz, text, text) from public;
grant execute on function evidence.event_dead_letter_resolve(bigint, text, timestamptz, text, text) to evidence_runtime;
revoke create on schema evidence from evidence_sys;
