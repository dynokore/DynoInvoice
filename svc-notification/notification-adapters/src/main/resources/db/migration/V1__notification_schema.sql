-- V1__notification_schema.sql (TPL-12; INV-T-5972, INV-REQ-820).
--
-- Roles (INV-AC-2175): notification_migrate owns the tables and runs this file; notification_runtime logs in;
-- notification_sys is NOLOGIN. No role named "notification" exists.
-- Precondition (QueueSchema.ddl KDoc, coordinator rulings INV-Q-6586 and INV-Q-6663): notification.bound_tenant() and
-- notification.bound_actor() already exist in the schema, installed once per schema by the sys login from
-- tenancy-kernel's BindingKeyMigration.ddl; this file does not run that generator and does not switch role.
-- The schema itself is created by Flyway (the `schemas` setting) or by the installer, owned by notification_migrate.
-- Webhook subscriptions (INV-REQ-820): a tenant registers an endpoint per event type, scoped to entities. The endpoint
-- is a reference to the secret store, never the secret or the URL itself; signing and delivery are later tasks.
create table notification.webhook_subscription (
    id uuid primary key,
    tenant_id uuid not null,
    event_type text not null,
    endpoint_ref text not null,
    version bigint not null default 0
);
comment on column notification.webhook_subscription.event_type is 'class: operational';
comment on column notification.webhook_subscription.endpoint_ref is 'class: operational';
create index webhook_subscription_tenant_event on notification.webhook_subscription (tenant_id, event_type);
create table notification.webhook_subscription_scope (
    subscription_id uuid not null references notification.webhook_subscription (id) on delete cascade,
    tenant_id uuid not null,
    entity_ref text not null,
    primary key (subscription_id, entity_ref)
);
comment on column notification.webhook_subscription_scope.entity_ref is 'class: operational';

alter table notification.webhook_subscription enable row level security;
alter table notification.webhook_subscription force row level security;
create policy webhook_subscription_tenant_isolation on notification.webhook_subscription
    using      (tenant_id = (select notification.bound_tenant()))
    with check (tenant_id = (select notification.bound_tenant()));
alter table notification.webhook_subscription_scope enable row level security;
alter table notification.webhook_subscription_scope force row level security;
create policy webhook_subscription_scope_tenant_isolation on notification.webhook_subscription_scope
    using      (tenant_id = (select notification.bound_tenant()))
    with check (tenant_id = (select notification.bound_tenant()));
grant usage on schema notification to notification_runtime;
grant select, insert, update, delete on notification.webhook_subscription, notification.webhook_subscription_scope to notification_runtime;

-- OutboxSchema.ddl("notification", "notification_runtime", "notification_sys")
create table notification.outbox (
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
create index outbox_unpublished on notification.outbox (seq) where published_at is null;
create index outbox_published_at on notification.outbox (published_at) where published_at is not null;
create table notification.outbox_sequence (
    tenant_id uuid not null,
    aggregate_key text not null,
    last_value bigint not null,
    primary key (tenant_id, aggregate_key)
);
create table notification.inbox (
    consumer text not null,
    event_id text not null,
    tenant_id uuid not null,
    received_at timestamptz not null default now(),
    primary key (consumer, event_id)
);
create index inbox_received_at on notification.inbox (received_at);
create table notification.consumer_sequence (
    consumer text not null,
    source text not null,
    subject text not null,
    tenant_id uuid not null,
    last_value bigint not null,
    primary key (consumer, tenant_id, source, subject)
);
create table notification.consumer_offset (
    consumer text not null,
    topic text not null,
    next_offset bigint not null,
    attempts int not null default 0,
    infra_failures int not null default 0,
    first_infra_failure_at timestamptz,
    retry_not_before timestamptz,
    primary key (consumer, topic)
);
comment on table notification.consumer_offset is 'platform-scoped: no tenant data';
create table notification.event_dead_letter (
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
create index event_dead_letter_resolved_at on notification.event_dead_letter (resolved_at) where resolved_at is not null;
comment on column notification.outbox.data is 'class: content';
comment on column notification.event_dead_letter.raw is 'class: content';
ALTER TABLE notification.outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_tenant_isolation ON notification.outbox
    USING      (tenant_id = (select notification.bound_tenant()))
    WITH CHECK (tenant_id = (select notification.bound_tenant()));
ALTER TABLE notification.outbox_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.outbox_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_sequence_tenant_isolation ON notification.outbox_sequence
    USING      (tenant_id = (select notification.bound_tenant()))
    WITH CHECK (tenant_id = (select notification.bound_tenant()));
ALTER TABLE notification.inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.inbox FORCE ROW LEVEL SECURITY;
CREATE POLICY inbox_tenant_isolation ON notification.inbox
    USING      (tenant_id = (select notification.bound_tenant()))
    WITH CHECK (tenant_id = (select notification.bound_tenant()));
ALTER TABLE notification.consumer_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.consumer_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY consumer_sequence_tenant_isolation ON notification.consumer_sequence
    USING      (tenant_id = (select notification.bound_tenant()))
    WITH CHECK (tenant_id = (select notification.bound_tenant()));
ALTER TABLE notification.event_dead_letter ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.event_dead_letter FORCE ROW LEVEL SECURITY;
CREATE POLICY event_dead_letter_tenant_isolation ON notification.event_dead_letter
    USING      (tenant_id = (select notification.bound_tenant()))
    WITH CHECK (tenant_id = (select notification.bound_tenant()));
create table if not exists notification.consumer_replay (
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
comment on table notification.consumer_replay is 'platform-scoped: no tenant data';
create unique index if not exists consumer_replay_pending on notification.consumer_replay (consumer, producer)
    where closed_at is null;
create table if not exists notification.consumer_replay_marker (
    replay_id uuid not null,
    source text not null,
    applied_at timestamptz not null default now(),
    primary key (replay_id, source)
);
comment on table notification.consumer_replay_marker is 'platform-scoped: no tenant data';
create table if not exists notification.consumer_source_seen (
    consumer text not null,
    producer text not null,
    source text not null,
    primary key (consumer, producer, source)
);
comment on table notification.consumer_source_seen is 'platform-scoped: no tenant data';
create function notification.outbox_publish_once() returns trigger
language plpgsql set search_path = pg_catalog, notification, pg_temp as $$
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
revoke all on function notification.outbox_publish_once() from public;
create trigger outbox_publish_once before update on notification.outbox
    for each row execute function notification.outbox_publish_once();
grant usage on schema notification to notification_runtime;
grant select, insert, update (published_at) on notification.outbox to notification_runtime;
grant select, insert, update on notification.outbox_sequence, notification.consumer_sequence, notification.consumer_offset to notification_runtime;
grant select, insert on notification.inbox to notification_runtime;
grant select on notification.event_dead_letter to notification_runtime;
create policy event_dead_letter_no_platform_tenant on notification.event_dead_letter as restrictive for select to notification_runtime
    using (tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
grant select, insert, update on notification.consumer_replay, notification.consumer_replay_marker, notification.consumer_source_seen
    to notification_runtime;
grant usage on schema notification to notification_sys;
create policy outbox_sys_select on notification.outbox for select to notification_sys using (true);
create policy outbox_sys_purge on notification.outbox for delete to notification_sys using (published_at is not null);
create policy outbox_sys_purge_published on notification.outbox as restrictive for delete to notification_sys
    using (published_at is not null);
grant select (seq, tenant_id, published_at) on notification.outbox to notification_sys;
grant delete on notification.outbox to notification_sys;
grant insert on notification.outbox to notification_sys;
create policy inbox_sys_select on notification.inbox for select to notification_sys using (true);
create policy inbox_sys_purge on notification.inbox for delete to notification_sys using (true);
grant select (consumer, event_id, received_at) on notification.inbox to notification_sys;
grant delete on notification.inbox to notification_sys;
create policy consumer_sequence_sys_select on notification.consumer_sequence for select to notification_sys using (true);
create policy consumer_sequence_sys_full_sync on notification.consumer_sequence for delete to notification_sys using (true);
grant select (consumer, source, tenant_id) on notification.consumer_sequence to notification_sys;
grant delete on notification.consumer_sequence to notification_sys;
create policy event_dead_letter_sys_select on notification.event_dead_letter for select to notification_sys using (true);
create policy event_dead_letter_sys_insert on notification.event_dead_letter for insert to notification_sys with check (true);
create policy event_dead_letter_sys_update on notification.event_dead_letter for update to notification_sys using (true) with check (true);
create policy event_dead_letter_sys_purge on notification.event_dead_letter for delete to notification_sys using (resolved_at is not null);
create policy event_dead_letter_sys_purge_triaged on notification.event_dead_letter as restrictive for delete to notification_sys
    using (resolved_at is not null and status <> 'OPEN');
grant select (id, consumer, topic, log_offset, tenant_id, reason, recorded_at, status, resolved_at, note, replayed_to) on notification.event_dead_letter to notification_sys;
grant insert on notification.event_dead_letter to notification_sys;
grant update (status, resolved_at, note, replayed_to) on notification.event_dead_letter to notification_sys;
grant delete on notification.event_dead_letter to notification_sys;
grant select, insert, update on notification.consumer_offset to notification_sys;
grant insert on notification.consumer_source_seen to notification_sys;
create view notification.outbox_claim_v as select seq, tenant_id, published_at from notification.outbox;
create view notification.event_dead_letter_triage_v as select id, consumer, topic, log_offset, tenant_id, reason, recorded_at, status, resolved_at, note, replayed_to from notification.event_dead_letter;
grant create on schema notification to notification_sys;
alter view notification.outbox_claim_v owner to notification_sys;
revoke all on notification.outbox_claim_v from public;
grant select on notification.outbox_claim_v to notification_runtime;
alter view notification.event_dead_letter_triage_v owner to notification_sys;
revoke all on notification.event_dead_letter_triage_v from public;
grant select on notification.event_dead_letter_triage_v to notification_runtime;
revoke create on schema notification from notification_sys;
create function notification.outbox_claim_page(after_seq bigint, page_size integer, unpublished_only boolean)
returns table (seq bigint, tenant_id uuid, published_at timestamptz)
language plpgsql stable security definer set search_path = pg_catalog, notification, pg_temp as $$
begin
    return query select v.seq, v.tenant_id, v.published_at from notification.outbox_claim_v v
        where v.seq > after_seq and (not unpublished_only or v.published_at is null)
        order by v.seq limit page_size;
end
$$;
create function notification.outbox_claim_pending() returns bigint
language plpgsql stable security definer set search_path = pg_catalog, notification, pg_temp as $$
begin
    return (select count(*) from notification.outbox_claim_v v where v.published_at is null);
end
$$;
grant create on schema notification to notification_sys;
alter function notification.outbox_claim_page(bigint, integer, boolean) owner to notification_sys;
revoke all on function notification.outbox_claim_page(bigint, integer, boolean) from public;
grant execute on function notification.outbox_claim_page(bigint, integer, boolean) to notification_runtime;
alter function notification.outbox_claim_pending() owner to notification_sys;
revoke all on function notification.outbox_claim_pending() from public;
grant execute on function notification.outbox_claim_pending() to notification_runtime;
revoke create on schema notification from notification_sys;
create function notification.consumer_offset_lock(p_consumer text, p_topic text) returns bigint
language plpgsql volatile security definer set search_path = pg_catalog, notification, pg_temp as $$
declare
    v_next bigint;
begin
    insert into notification.consumer_offset (consumer, topic, next_offset) values (p_consumer, p_topic, 1)
        on conflict do nothing;
    select o.next_offset into v_next from notification.consumer_offset o
        where o.consumer = p_consumer and o.topic = p_topic for update;
    if not found then
        raise exception 'offset row missing';
    end if;
    return v_next;
end
$$;
create function notification.consumer_offset_advance(p_consumer text, p_topic text, p_next_offset bigint) returns void
language plpgsql volatile security definer set search_path = pg_catalog, notification, pg_temp as $$
begin
    update notification.consumer_offset set next_offset = p_next_offset, attempts = 0, infra_failures = 0,
        first_infra_failure_at = null, retry_not_before = null
    where consumer = p_consumer and topic = p_topic;
end
$$;
create function notification.consumer_source_seen_record(p_consumer text, p_producer text, p_source text) returns void
language plpgsql volatile security definer set search_path = pg_catalog, notification, pg_temp as $$
begin
    insert into notification.consumer_source_seen (consumer, producer, source)
        values (p_consumer, p_producer, p_source) on conflict do nothing;
end
$$;
grant create on schema notification to notification_sys;
alter function notification.consumer_offset_lock(text, text) owner to notification_sys;
revoke all on function notification.consumer_offset_lock(text, text) from public;
grant execute on function notification.consumer_offset_lock(text, text) to notification_runtime;
alter function notification.consumer_offset_advance(text, text, bigint) owner to notification_sys;
revoke all on function notification.consumer_offset_advance(text, text, bigint) from public;
grant execute on function notification.consumer_offset_advance(text, text, bigint) to notification_runtime;
alter function notification.consumer_source_seen_record(text, text, text) owner to notification_sys;
revoke all on function notification.consumer_source_seen_record(text, text, text) from public;
grant execute on function notification.consumer_source_seen_record(text, text, text) to notification_runtime;
revoke create on schema notification from notification_sys;
create function notification.event_dead_letter_triage_page(p_after bigint, p_status text, p_ids bigint[], p_tenant uuid,
    p_consumer text, p_topic text, p_failure text, p_from timestamptz, p_before timestamptz,
    p_with_tenant boolean, p_platform uuid, p_limit integer)
returns table (id bigint, consumer text, topic text, log_offset bigint, tenant_id uuid, reason text,
    status text, recorded_at timestamptz, resolved_at timestamptz, note text, replayed_to text)
language plpgsql stable security definer set search_path = pg_catalog, notification, pg_temp as $$
begin
    if p_limit is null or p_limit < 1 or p_limit > 1000 then
        raise exception 'the triage page limit must be in 1..1000' using errcode = '22023';
    end if;
    return query select v.id, v.consumer, v.topic, v.log_offset, v.tenant_id, v.reason, v.status,
            v.recorded_at, v.resolved_at, v.note, v.replayed_to
        from notification.event_dead_letter_triage_v v
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
grant create on schema notification to notification_sys;
alter function notification.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) owner to notification_sys;
revoke all on function notification.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) from public;
grant execute on function notification.event_dead_letter_triage_page(bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer) to notification_runtime;
revoke create on schema notification from notification_sys;
create function notification.outbox_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, notification, pg_temp as $$
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
    delete from notification.outbox where seq in
        (select seq from notification.outbox where published_at is not null and published_at < cutoff
         limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema notification to notification_sys;
alter function notification.outbox_purge_expired(timestamptz, integer) owner to notification_sys;
revoke all on function notification.outbox_purge_expired(timestamptz, integer) from public;
grant execute on function notification.outbox_purge_expired(timestamptz, integer) to notification_runtime;
revoke create on schema notification from notification_sys;
create function notification.inbox_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, notification, pg_temp as $$
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
    delete from notification.inbox where (consumer, event_id) in
        (select consumer, event_id from notification.inbox where received_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema notification to notification_sys;
alter function notification.inbox_purge_expired(timestamptz, integer) owner to notification_sys;
revoke all on function notification.inbox_purge_expired(timestamptz, integer) from public;
grant execute on function notification.inbox_purge_expired(timestamptz, integer) to notification_runtime;
revoke create on schema notification from notification_sys;
create function notification.event_dead_letter_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, notification, pg_temp as $$
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
    delete from notification.event_dead_letter where id in
        (select id from notification.event_dead_letter
         where status <> 'OPEN' and resolved_at is not null and resolved_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema notification to notification_sys;
alter function notification.event_dead_letter_purge_expired(timestamptz, integer) owner to notification_sys;
revoke all on function notification.event_dead_letter_purge_expired(timestamptz, integer) from public;
grant execute on function notification.event_dead_letter_purge_expired(timestamptz, integer) to notification_runtime;
revoke create on schema notification from notification_sys;
create function notification.full_sync_delete(p_consumer text, p_source text) returns bigint
language plpgsql security definer set search_path = pg_catalog, notification, pg_temp as $$
declare
    deleted bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        or p_source is null or p_source ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$' then
        raise exception 'a full sync names one consumer and one source' using errcode = '22023';
    end if;
    delete from notification.consumer_sequence where consumer = p_consumer and source = p_source;
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema notification to notification_sys;
alter function notification.full_sync_delete(text, text) owner to notification_sys;
revoke all on function notification.full_sync_delete(text, text) from public;
grant execute on function notification.full_sync_delete(text, text) to notification_runtime;
revoke create on schema notification from notification_sys;
create function notification.event_dead_letter_record(p_consumer text, p_topic text, p_log_offset bigint, p_tenant_id uuid,
    p_reason text, p_raw text) returns bigint
language plpgsql security definer set search_path = pg_catalog, notification, pg_temp as $$
declare
    recorded bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        or p_topic is null or p_log_offset is null
        or p_reason is null or p_raw is null then
        raise exception 'a dead letter names its consumer, topic, offset, reason and record'
            using errcode = '22023';
    end if;
    insert into notification.event_dead_letter (consumer, topic, log_offset, tenant_id, reason, raw)
    values (p_consumer, p_topic, p_log_offset, coalesce(p_tenant_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid), p_reason, p_raw)
    returning id into recorded;
    return recorded;
end
$$;
grant create on schema notification to notification_sys;
alter function notification.event_dead_letter_record(text, text, bigint, uuid, text, text) owner to notification_sys;
revoke all on function notification.event_dead_letter_record(text, text, bigint, uuid, text, text) from public;
grant execute on function notification.event_dead_letter_record(text, text, bigint, uuid, text, text) to notification_runtime;
revoke create on schema notification from notification_sys;
create function notification.event_dead_letter_resolve(p_id bigint, p_status text, p_resolved_at timestamptz, p_note text,
    p_replayed_to text) returns boolean
language plpgsql security definer set search_path = pg_catalog, notification, pg_temp as $$
begin
    if p_status is null or p_status not in ('REPLAYED', 'DISCARDED') or p_resolved_at is null then
        raise exception 'a dead letter is resolved as REPLAYED or DISCARDED at a time' using errcode = '22023';
    end if;
    update notification.event_dead_letter set status = p_status, resolved_at = p_resolved_at, note = p_note,
        replayed_to = p_replayed_to
    where id = p_id and status = 'OPEN';
    return found;
end
$$;
grant create on schema notification to notification_sys;
alter function notification.event_dead_letter_resolve(bigint, text, timestamptz, text, text) owner to notification_sys;
revoke all on function notification.event_dead_letter_resolve(bigint, text, timestamptz, text, text) from public;
grant execute on function notification.event_dead_letter_resolve(bigint, text, timestamptz, text, text) to notification_runtime;
revoke create on schema notification from notification_sys;
-- QueueSchema.ddl("notification", "notification_runtime", "notification_sys")
create table notification.work_queue (
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
create index work_queue_partition on notification.work_queue (tenant_id, partition_key, lane, seq);
create index work_queue_lane on notification.work_queue (lane, seq);
create unique index work_queue_replay_once on notification.work_queue (source_dead_letter_id)
    where source_dead_letter_id is not null;
comment on column notification.work_queue.payload is 'class: content';
ALTER TABLE notification.work_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.work_queue FORCE ROW LEVEL SECURITY;
CREATE POLICY work_queue_tenant_isolation ON notification.work_queue
    USING      (tenant_id = (select notification.bound_tenant()))
    WITH CHECK (tenant_id = (select notification.bound_tenant()));
grant usage on schema notification to notification_runtime;
grant select, insert, update, delete on notification.work_queue to notification_runtime;
grant usage on schema notification to notification_sys;
create policy work_queue_sys_select on notification.work_queue for select to notification_sys using (true);
create policy work_queue_sys_claim on notification.work_queue for update to notification_sys using (true) with check (true);
grant select (seq, tenant_id, partition_key, lane, consumer, state, attempts, claimed_at)
    on notification.work_queue to notification_sys;
grant update (state, attempts, claimed_at) on notification.work_queue to notification_sys;
create function notification.claim_due(p_lane text, p_consumer text, p_claimed_at timestamptz,
    p_reclaim_before timestamptz)
returns table (claimed_seq bigint, claimed_tenant uuid, claimed_partition text)
language plpgsql security definer set search_path = pg_catalog, notification, pg_temp as $$
declare
    c record;
begin
    if p_lane is null or p_lane not in ('DEADLINE_NEAR', 'LIVE', 'REPROCESSING', 'BACKFILL', 'REPLAY') or p_claimed_at is null or p_reclaim_before is null then
        raise exception 'a claim names a lane, its time and the reclaim time' using errcode = '22023';
    end if;
    for c in
        select q.seq, q.tenant_id, q.partition_key from notification.work_queue q
        where q.lane = p_lane and q.consumer is not distinct from p_consumer
          and (q.state = 'PENDING' or (q.state = 'CLAIMED' and q.claimed_at < p_reclaim_before))
          and not exists (
              select 1 from notification.work_queue p
              where p.tenant_id = q.tenant_id and p.partition_key = q.partition_key and p.lane = q.lane
                and p.consumer is not distinct from q.consumer and p.seq < q.seq)
        order by q.seq limit 20 for update of q skip locked
    loop
        continue when not pg_try_advisory_xact_lock(hashtextextended(
            'notification.work_queue|' || c.tenant_id || '|' || c.partition_key || '|' || p_lane || '|' ||
            coalesce(p_consumer, ''), 0));
        perform 1 from notification.work_queue q
        where q.seq = c.seq
          and (q.state = 'PENDING' or (q.state = 'CLAIMED' and q.claimed_at < p_reclaim_before))
          and not exists (
              select 1 from notification.work_queue p
              where p.tenant_id = q.tenant_id and p.partition_key = q.partition_key and p.lane = q.lane
                and p.consumer is not distinct from q.consumer and p.seq < q.seq)
        for update of q skip locked;
        continue when not found;
        update notification.work_queue set state = 'CLAIMED', claimed_at = p_claimed_at, attempts = attempts + 1
        where seq = c.seq;
        claimed_seq := c.seq;
        claimed_tenant := c.tenant_id;
        claimed_partition := c.partition_key;
        return next;
        return;
    end loop;
end
$$;
grant create on schema notification to notification_sys;
alter function notification.claim_due(text, text, timestamptz, timestamptz) owner to notification_sys;
revoke all on function notification.claim_due(text, text, timestamptz, timestamptz) from public;
grant execute on function notification.claim_due(text, text, timestamptz, timestamptz) to notification_runtime;
revoke create on schema notification from notification_sys;
create table if not exists notification.lane_rate_window (
    lane text not null check (lane in ('DEADLINE_NEAR', 'LIVE', 'REPROCESSING', 'BACKFILL', 'REPLAY')),
    window_start timestamptz not null,
    used int not null check (used >= 0),
    primary key (lane)
);
comment on table notification.lane_rate_window is 'platform-scoped: no tenant data';
grant usage on schema notification to notification_runtime;
grant select, insert, update, delete on notification.lane_rate_window to notification_runtime;
