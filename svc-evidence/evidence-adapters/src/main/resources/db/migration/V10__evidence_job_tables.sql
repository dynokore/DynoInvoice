-- V10__evidence_job_tables.sql: platform-generated DDL (TPL-12); regenerate with GeneratedJobMigrationTest, never edit by hand.
-- Generated: service-runtime's JobSchema.ddl("evidence", "evidence_runtime", "evidence_sys") of the platform-libs-bom 0.137.0 train.
create table evidence.shedlock (
    name varchar(255) not null primary key,
    lock_until timestamp not null,
    locked_at timestamp not null,
    locked_by varchar(255) not null
);
comment on table evidence.shedlock is 'platform-scoped: no tenant data';
create table evidence.job_run (
    tenant_id uuid not null,
    service text not null,
    job text not null,
    entity_ref text not null,
    tick timestamptz not null,
    trigger text not null check (trigger in ('SCHEDULED', 'MANUAL')),
    attempt integer not null check (attempt >= 1),
    status text not null check (status in ('IN_PROGRESS', 'SUCCEEDED', 'FAILED')),
    reason text,
    started_at timestamptz not null,
    finished_at timestamptz,
    duration_millis bigint check (duration_millis >= 0),
    primary key (tenant_id, service, job, entity_ref, tick, trigger, attempt)
);
create table evidence.job_pause (
    tenant_id uuid not null,
    service text not null,
    job text not null,
    entity_ref text not null,
    paused_at timestamptz not null,
    paused_until timestamptz not null,
    ended_at timestamptz,
    check (paused_until > paused_at)
);
create unique index job_pause_open on evidence.job_pause (tenant_id, service, job, entity_ref)
    where ended_at is null;
create table evidence.job_retention (
    id boolean primary key default true check (id),
    job_window interval not null check (job_window > interval '0'),
    updated_at timestamptz not null default now()
);
comment on table evidence.job_retention is 'platform-scoped: no tenant data';
revoke all on evidence.job_retention from public;
ALTER TABLE evidence.job_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.job_run FORCE ROW LEVEL SECURITY;
CREATE POLICY job_run_tenant_isolation ON evidence.job_run
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
ALTER TABLE evidence.job_pause ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.job_pause FORCE ROW LEVEL SECURITY;
CREATE POLICY job_pause_tenant_isolation ON evidence.job_pause
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
grant usage on schema evidence to evidence_runtime;
grant select, insert, update on evidence.shedlock to evidence_runtime;
grant select, insert, update on evidence.job_run to evidence_runtime;
grant select, insert, update on evidence.job_pause to evidence_runtime;
grant usage on schema evidence to evidence_sys;
create policy job_run_sys_select on evidence.job_run for select to evidence_sys using (true);
create policy job_run_sys_purge on evidence.job_run for delete to evidence_sys using (status <> 'IN_PROGRESS');
create policy job_run_sys_purge_finished on evidence.job_run as restrictive for delete to evidence_sys
    using (status <> 'IN_PROGRESS');
grant select (tenant_id, service, job, entity_ref, tick, trigger, attempt, status, started_at)
    on evidence.job_run to evidence_sys;
grant delete on evidence.job_run to evidence_sys;
create policy job_pause_sys_select on evidence.job_pause for select to evidence_sys using (true);
create policy job_pause_sys_purge on evidence.job_pause for delete to evidence_sys using (true);
grant select (tenant_id, service, job, entity_ref, paused_at, paused_until, ended_at) on evidence.job_pause to evidence_sys;
grant delete on evidence.job_pause to evidence_sys;
grant select on evidence.job_retention to evidence_sys;
create function evidence.job_run_in_progress(batch_size integer) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, evidence, pg_temp as $$
begin
    if batch_size is null or batch_size < 1 or batch_size > 500 then
        raise exception 'batch size % is outside 1..500', batch_size using errcode = '22023';
    end if;
    return coalesce((
        select jsonb_agg(jsonb_build_object(
            'tenant_id', r.tenant_id, 'job', r.job, 'entity_ref', r.entity_ref, 'tick', r.tick,
            'trigger', r.trigger, 'attempt', r.attempt, 'started_at', r.started_at) order by r.started_at)
        from (select tenant_id, job, entity_ref, tick, trigger, attempt, started_at from evidence.job_run
              where status = 'IN_PROGRESS' order by started_at limit batch_size) r
    ), '[]'::jsonb);
end
$$;
grant create on schema evidence to evidence_sys;
alter function evidence.job_run_in_progress(integer) owner to evidence_sys;
revoke all on function evidence.job_run_in_progress(integer) from public;
grant execute on function evidence.job_run_in_progress(integer) to evidence_runtime;
revoke create on schema evidence from evidence_sys;
create function evidence.job_state_purge_expired(batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
declare
    v_window interval;
    v_cutoff timestamptz;
    runs bigint;
    pauses bigint;
begin
    if batch_size is null or batch_size < 1 or batch_size > 10000 then
        raise exception 'batch size % is outside 1..10000', batch_size using errcode = '22023';
    end if;
    select r.job_window into v_window from evidence.job_retention r where r.id;
    if not found or v_window is null or v_window <= interval '0' then
        raise warning 'job retention row is missing: nothing is purged (INV-Q-4346)';
        return null;
    end if;
    v_cutoff := now() - v_window;
    delete from evidence.job_run where (tenant_id, service, job, entity_ref, tick, trigger, attempt) in
        (select tenant_id, service, job, entity_ref, tick, trigger, attempt from evidence.job_run
         where status <> 'IN_PROGRESS' and started_at < v_cutoff limit batch_size);
    get diagnostics runs = row_count;
    pauses := 0;
    if runs < batch_size then
        delete from evidence.job_pause where coalesce(ended_at, paused_until) < v_cutoff
            and (tenant_id, service, job, entity_ref, paused_at) in
            (select tenant_id, service, job, entity_ref, paused_at from evidence.job_pause
             where coalesce(ended_at, paused_until) < v_cutoff limit batch_size - runs);
        get diagnostics pauses = row_count;
    end if;
    return runs + pauses;
end
$$;
grant create on schema evidence to evidence_sys;
alter function evidence.job_state_purge_expired(integer) owner to evidence_sys;
revoke all on function evidence.job_state_purge_expired(integer) from public;
grant execute on function evidence.job_state_purge_expired(integer) to evidence_runtime;
revoke create on schema evidence from evidence_sys;
create function evidence.job_retention_window() returns interval
language sql stable security definer set search_path = pg_catalog, evidence, pg_temp as $$
    select r.job_window from evidence.job_retention r where r.id
$$;
grant create on schema evidence to evidence_sys;
alter function evidence.job_retention_window() owner to evidence_sys;
revoke all on function evidence.job_retention_window() from public;
grant execute on function evidence.job_retention_window() to evidence_runtime;
revoke create on schema evidence from evidence_sys;