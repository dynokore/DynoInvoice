-- Generated: QueueSchema.ddl("rules", "rules_runtime", "rules_sys") of outbox-and-queue (INV-T-4978, TPL-12),
-- committed as text; MigrationDdlTest fails when it differs. The work queue and the lane rate window of the
-- reprocessing lane (INV-REQ-1004); the run and the resubmission list follow the generated text.
create table rules.work_queue (
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
    source_dead_letter_id bigint,
    drain_tier smallint not null default 3 check (drain_tier in (1, 2, 3)),
    drain_rank int
);
create index work_queue_partition on rules.work_queue (tenant_id, partition_key, lane, seq);
create index work_queue_lane on rules.work_queue (lane, seq);
create unique index work_queue_replay_once on rules.work_queue (source_dead_letter_id)
    where source_dead_letter_id is not null;
comment on column rules.work_queue.payload is 'class: content';
ALTER TABLE rules.work_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE rules.work_queue FORCE ROW LEVEL SECURITY;
CREATE POLICY work_queue_tenant_isolation ON rules.work_queue
    USING      (tenant_id = (select rules.bound_tenant()))
    WITH CHECK (tenant_id = (select rules.bound_tenant()));
grant usage on schema rules to rules_runtime;
grant select, insert, update, delete on rules.work_queue to rules_runtime;
grant usage on schema rules to rules_sys;
create policy work_queue_sys_select on rules.work_queue for select to rules_sys using (true);
create policy work_queue_sys_claim on rules.work_queue for update to rules_sys using (true) with check (true);
grant select (seq, tenant_id, partition_key, lane, consumer, state, attempts, claimed_at, drain_tier,
    drain_rank) on rules.work_queue to rules_sys;
grant update (state, attempts, claimed_at) on rules.work_queue to rules_sys;
create or replace function rules.claim_due(p_lane text, p_consumer text, p_claimed_at timestamptz,
    p_reclaim_before timestamptz)
returns table (claimed_seq bigint, claimed_tenant uuid, claimed_partition text)
language plpgsql security definer set search_path = pg_catalog, rules, pg_temp as $$
declare
    c record;
begin
    if p_lane is null or p_lane not in ('DEADLINE_NEAR', 'LIVE', 'REPROCESSING', 'BACKFILL', 'REPLAY') or p_claimed_at is null or p_reclaim_before is null then
        raise exception 'a claim names a lane, its time and the reclaim time' using errcode = '22023';
    end if;
    for c in
        select q.seq, q.tenant_id, q.partition_key from rules.work_queue q
        where q.lane = p_lane and q.consumer is not distinct from p_consumer
          and (q.state = 'PENDING' or (q.state = 'CLAIMED' and q.claimed_at < p_reclaim_before))
          and not exists (
              select 1 from rules.work_queue p
              where p.tenant_id = q.tenant_id and p.partition_key = q.partition_key and p.lane = q.lane
                and p.consumer is not distinct from q.consumer and p.seq < q.seq)
        order by q.drain_tier, q.drain_rank nulls last, q.seq limit 20
    loop
        continue when not pg_try_advisory_xact_lock(hashtextextended(
            'rules.work_queue|' || c.tenant_id || '|' || c.partition_key || '|' || p_lane || '|' ||
            coalesce(p_consumer, ''), 0));
        perform 1 from rules.work_queue q
        where q.seq = c.seq
          and (q.state = 'PENDING' or (q.state = 'CLAIMED' and q.claimed_at < p_reclaim_before))
          and not exists (
              select 1 from rules.work_queue p
              where p.tenant_id = q.tenant_id and p.partition_key = q.partition_key and p.lane = q.lane
                and p.consumer is not distinct from q.consumer and p.seq < q.seq)
        for update of q skip locked;
        continue when not found;
        update rules.work_queue set state = 'CLAIMED', claimed_at = p_claimed_at, attempts = attempts + 1
        where seq = c.seq;
        claimed_seq := c.seq;
        claimed_tenant := c.tenant_id;
        claimed_partition := c.partition_key;
        return next;
        return;
    end loop;
end
$$;
grant create on schema rules to rules_sys;
alter function rules.claim_due(text, text, timestamptz, timestamptz) owner to rules_sys;
revoke all on function rules.claim_due(text, text, timestamptz, timestamptz) from public;
grant execute on function rules.claim_due(text, text, timestamptz, timestamptz) to rules_runtime;
revoke create on schema rules from rules_sys;
create table if not exists rules.lane_rate_window (
    lane text not null check (lane in ('DEADLINE_NEAR', 'LIVE', 'REPROCESSING', 'BACKFILL', 'REPLAY')),
    window_start timestamptz not null,
    used int not null check (used >= 0),
    primary key (lane)
);
comment on table rules.lane_rate_window is 'platform-scoped: no tenant data';
grant usage on schema rules to rules_runtime;
grant select, insert, update, delete on rules.lane_rate_window to rules_runtime;

-- INV-T-4978 (INV-REQ-1003, INV-REQ-1004): the reprocessing run and the list of documents to resubmit. A document is
-- never stored here except as a row of rules.reprocessing_resubmission (INV-REQ-1196). Run as rules_migrate.

-- 1. rules.reprocessing_run: one row per tenant and superseding pack version (the run key). Counters only.
create table rules.reprocessing_run (
    tenant_id         uuid        not null,
    reprocessing_id   text        not null check (reprocessing_id !~ '^\s*$' and char_length(reprocessing_id) <= 40),
    pack_type         text        not null check (pack_type !~ '^\s*$'),
    pack_version      text        not null check (pack_version !~ '^\s*$'),
    state             text        not null check (state in ('RUNNING', 'COMPLETE')),
    last_page_arrived boolean     not null default false,
    received          integer     not null default 0 check (received >= 0),
    processed         integer     not null default 0 check (processed >= 0 and processed <= received),
    started_at        timestamptz not null default clock_timestamp(),
    primary key (tenant_id, pack_type, pack_version),
    constraint reprocessing_run_id_key unique (reprocessing_id)
);

-- 2. rules.reprocessing_run_entity: the entities requested for a run, and whether the last page of each has arrived.
create table rules.reprocessing_run_entity (
    tenant_id         uuid    not null,
    pack_type         text    not null,
    pack_version      text    not null,
    entity_ref        text    not null check (entity_ref !~ '^\s*$' and char_length(entity_ref) <= 40),
    last_page_arrived boolean not null default false,
    primary key (tenant_id, pack_type, pack_version, entity_ref),
    foreign key (tenant_id, pack_type, pack_version)
        references rules.reprocessing_run (tenant_id, pack_type, pack_version)
);

-- 3. rules.reprocessing_run_page: the page numbers seen per run and entity; a repeated page enqueues nothing.
create table rules.reprocessing_run_page (
    tenant_id    uuid    not null,
    pack_type    text    not null,
    pack_version text    not null,
    entity_ref   text    not null,
    page_number  integer not null check (page_number >= 1),
    primary key (tenant_id, pack_type, pack_version, entity_ref, page_number),
    foreign key (tenant_id, pack_type, pack_version, entity_ref)
        references rules.reprocessing_run_entity (tenant_id, pack_type, pack_version, entity_ref)
);

-- 4. rules.reprocessing_resubmission: the documents to resubmit under their own Doc UUID (INV-REQ-1003). Written once
-- and never changed: select and insert only, as rules.pack_artefact (V3, V4).
create table rules.reprocessing_resubmission (
    tenant_id          uuid    not null,
    pack_type          text    not null check (pack_type !~ '^\s*$'),
    pack_version       text    not null check (pack_version !~ '^\s*$'),
    doc_uuid           uuid    not null,
    superseded_version integer not null check (superseded_version >= 1),
    constraint reprocessing_resubmission_key unique (pack_type, pack_version, doc_uuid)
);

alter table rules.reprocessing_run enable row level security;
alter table rules.reprocessing_run force row level security;
alter table rules.reprocessing_run_entity enable row level security;
alter table rules.reprocessing_run_entity force row level security;
alter table rules.reprocessing_run_page enable row level security;
alter table rules.reprocessing_run_page force row level security;
alter table rules.reprocessing_resubmission enable row level security;
alter table rules.reprocessing_resubmission force row level security;

create policy reprocessing_run_tenant_read on rules.reprocessing_run for select
    using (tenant_id = (select rules.bound_tenant()));
create policy reprocessing_run_tenant_insert on rules.reprocessing_run for insert
    with check (tenant_id = (select rules.bound_tenant()));
create policy reprocessing_run_tenant_update on rules.reprocessing_run for update
    using (tenant_id = (select rules.bound_tenant())) with check (tenant_id = (select rules.bound_tenant()));
create policy reprocessing_run_entity_tenant_read on rules.reprocessing_run_entity for select
    using (tenant_id = (select rules.bound_tenant()));
create policy reprocessing_run_entity_tenant_insert on rules.reprocessing_run_entity for insert
    with check (tenant_id = (select rules.bound_tenant()));
create policy reprocessing_run_entity_tenant_update on rules.reprocessing_run_entity for update
    using (tenant_id = (select rules.bound_tenant())) with check (tenant_id = (select rules.bound_tenant()));
create policy reprocessing_run_page_tenant_read on rules.reprocessing_run_page for select
    using (tenant_id = (select rules.bound_tenant()));
create policy reprocessing_run_page_tenant_insert on rules.reprocessing_run_page for insert
    with check (tenant_id = (select rules.bound_tenant()));
create policy reprocessing_resubmission_tenant_read on rules.reprocessing_resubmission for select
    using (tenant_id = (select rules.bound_tenant()));
create policy reprocessing_resubmission_tenant_insert on rules.reprocessing_resubmission for insert
    with check (tenant_id = (select rules.bound_tenant()));

revoke all on rules.reprocessing_run, rules.reprocessing_run_entity, rules.reprocessing_run_page,
    rules.reprocessing_resubmission from public;
grant select, insert, update on rules.reprocessing_run to rules_runtime;
grant select, insert, update on rules.reprocessing_run_entity to rules_runtime;
grant select, insert on rules.reprocessing_run_page to rules_runtime;
grant select, insert on rules.reprocessing_resubmission to rules_runtime;

-- The superseding registry row of a pack correction (INV-T-4978; INV-REQ-962, INV-REQ-1002). It carries no artefact row
-- of its own: artefact_version names the earlier pack whose signed artefact rows it uses, unchanged (set only on a
-- superseding row). The row is inserted ARMED in one statement, already holding artefact_version, because an ARMED
-- pack freezes its entries (pack_entry_source_frozen) and the bad pack row is never touched.
alter table rules.pack_registry add column artefact_version text null
    constraint pack_registry_artefact_version_not_blank check (artefact_version is null or artefact_version !~ '^\s*$');

create function rules.register_superseding_pack(p_id uuid, p_pack_id text, p_pack_type text, p_pack_version text,
    p_effective_from date, p_min_engine_version text, p_supersedes text, p_artefact_version text,
    p_activated_by text)
    returns uuid
language plpgsql security definer set search_path = pg_catalog, rules, pg_temp as $$
declare
    v_actor text := (select rules.bound_actor());
begin
    if (select rules.bound_tenant()) is distinct from 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid then
        raise exception 'a superseding platform pack is registered under the platform tenant only' using errcode = '42501';
    end if;
    if v_actor is null or v_actor ~ '^\s*$|[[:cntrl:]]' then
        raise exception 'the bound actor is not set: a superseding pack records its actor' using errcode = '42501';
    end if;
    if p_id is null or p_effective_from is null or coalesce(p_pack_id, '') ~ '^\s*$'
        or coalesce(p_pack_type, '') ~ '^\s*$' or coalesce(p_pack_version, '') ~ '^\s*$'
        or coalesce(p_min_engine_version, '') ~ '^\s*$' or coalesce(p_artefact_version, '') ~ '^\s*$'
        or coalesce(p_activated_by, '') ~ '^\s*$|[[:cntrl:]]' then
        raise exception 'every field of a superseding pack but supersedes is required and not blank' using errcode = '22023';
    end if;
    insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, effective_from,
        effective_to, min_engine_version, supersedes, registered_by, artefact_version, activated_by)
    values (p_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, p_pack_id, p_pack_type, p_pack_version, 'REGULATORY',
        'ARMED', p_effective_from, null, p_min_engine_version, p_supersedes, v_actor, p_artefact_version, p_activated_by);
    return p_id;
end
$$;
grant create on schema rules to rules_sys;
alter function rules.register_superseding_pack(uuid, text, text, text, date, text, text, text, text) owner to rules_sys;
revoke create on schema rules from rules_sys;
revoke execute on function rules.register_superseding_pack(uuid, text, text, text, date, text, text, text, text) from public;
grant execute on function rules.register_superseding_pack(uuid, text, text, text, date, text, text, text, text) to rules_runtime;

-- The row lock of a rollback (INV-T-4978): the runtime role has no UPDATE policy on a platform row, so SELECT ... FOR UPDATE
-- through it would silently see nothing. This sys-owned function locks the current active row of the type (the latest
-- ARMED platform row that is not a superseding row) and returns its version, platform binding only.
create function rules.lock_active_platform_pack(p_pack_type text)
    returns text
language plpgsql security definer set search_path = pg_catalog, rules, pg_temp as $$
declare
    v_version text;
begin
    if (select rules.bound_tenant()) is distinct from 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid then
        raise exception 'the active platform pack is locked under the platform tenant only' using errcode = '42501';
    end if;
    select pack_version into v_version from rules.pack_registry
        where pack_type = p_pack_type and tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid
            and status = 'ARMED' and artefact_version is null
        order by effective_from desc, registered_at desc, id desc
        limit 1
        for update;
    return v_version;
end
$$;
grant create on schema rules to rules_sys;
alter function rules.lock_active_platform_pack(text) owner to rules_sys;
revoke create on schema rules from rules_sys;
revoke execute on function rules.lock_active_platform_pack(text) from public;
grant execute on function rules.lock_active_platform_pack(text) to rules_runtime;
