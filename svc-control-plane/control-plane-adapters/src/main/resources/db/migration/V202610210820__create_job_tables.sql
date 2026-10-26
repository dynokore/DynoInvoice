-- V202610210820__create_job_tables.sql: platform-generated DDL (TPL-12); regenerate with GeneratedMigrationsTest, never edit by hand.
create table controlplane.shedlock (
    name varchar(255) not null primary key,
    lock_until timestamp not null,
    locked_at timestamp not null,
    locked_by varchar(255) not null
);
comment on table controlplane.shedlock is 'platform-scoped: no tenant data';
create table controlplane.job_run (
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
create table controlplane.job_pause (
    tenant_id uuid not null,
    service text not null,
    job text not null,
    entity_ref text not null,
    paused_at timestamptz not null,
    paused_until timestamptz not null,
    ended_at timestamptz,
    check (paused_until > paused_at)
);
create unique index job_pause_open on controlplane.job_pause (tenant_id, service, job, entity_ref)
    where ended_at is null;
create table controlplane.job_retention (
    id boolean primary key default true check (id),
    job_window interval not null check (job_window > interval '0'),
    updated_at timestamptz not null default now()
);
comment on table controlplane.job_retention is 'platform-scoped: no tenant data';
revoke all on controlplane.job_retention from public;
ALTER TABLE controlplane.job_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE controlplane.job_run FORCE ROW LEVEL SECURITY;
CREATE POLICY job_run_tenant_isolation ON controlplane.job_run
    USING      (tenant_id = (select controlplane.bound_tenant()))
    WITH CHECK (tenant_id = (select controlplane.bound_tenant()));
ALTER TABLE controlplane.job_pause ENABLE ROW LEVEL SECURITY;
ALTER TABLE controlplane.job_pause FORCE ROW LEVEL SECURITY;
CREATE POLICY job_pause_tenant_isolation ON controlplane.job_pause
    USING      (tenant_id = (select controlplane.bound_tenant()))
    WITH CHECK (tenant_id = (select controlplane.bound_tenant()));
grant usage on schema controlplane to controlplane_runtime;
grant select, insert, update on controlplane.shedlock to controlplane_runtime;
grant select, insert, update on controlplane.job_run to controlplane_runtime;
grant select, insert, update on controlplane.job_pause to controlplane_runtime;
grant usage on schema controlplane to controlplane_sys;
create policy job_run_sys_select on controlplane.job_run for select to controlplane_sys using (true);
create policy job_run_sys_purge on controlplane.job_run for delete to controlplane_sys using (status <> 'IN_PROGRESS');
create policy job_run_sys_purge_finished on controlplane.job_run as restrictive for delete to controlplane_sys
    using (status <> 'IN_PROGRESS');
grant select (tenant_id, service, job, entity_ref, tick, trigger, attempt, status, started_at)
    on controlplane.job_run to controlplane_sys;
grant delete on controlplane.job_run to controlplane_sys;
create policy job_pause_sys_select on controlplane.job_pause for select to controlplane_sys using (true);
create policy job_pause_sys_purge on controlplane.job_pause for delete to controlplane_sys using (true);
grant select (tenant_id, service, job, entity_ref, paused_at, paused_until, ended_at) on controlplane.job_pause to controlplane_sys;
grant delete on controlplane.job_pause to controlplane_sys;
grant select on controlplane.job_retention to controlplane_sys;
create function controlplane.job_run_in_progress(batch_size integer) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, controlplane, pg_temp as $$
begin
    if batch_size is null or batch_size < 1 or batch_size > 500 then
        raise exception 'batch size % is outside 1..500', batch_size using errcode = '22023';
    end if;
    return coalesce((
        select jsonb_agg(jsonb_build_object(
            'tenant_id', r.tenant_id, 'job', r.job, 'entity_ref', r.entity_ref, 'tick', r.tick,
            'trigger', r.trigger, 'attempt', r.attempt, 'started_at', r.started_at) order by r.started_at)
        from (select tenant_id, job, entity_ref, tick, trigger, attempt, started_at from controlplane.job_run
              where status = 'IN_PROGRESS' order by started_at limit batch_size) r
    ), '[]'::jsonb);
end
$$;
grant create on schema controlplane to controlplane_sys;
alter function controlplane.job_run_in_progress(integer) owner to controlplane_sys;
revoke all on function controlplane.job_run_in_progress(integer) from public;
grant execute on function controlplane.job_run_in_progress(integer) to controlplane_runtime;
revoke create on schema controlplane from controlplane_sys;
create function controlplane.job_state_purge_expired(batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, controlplane, pg_temp as $$
declare
    v_window interval;
    v_cutoff timestamptz;
    runs bigint;
    pauses bigint;
begin
    if batch_size is null or batch_size < 1 or batch_size > 10000 then
        raise exception 'batch size % is outside 1..10000', batch_size using errcode = '22023';
    end if;
    select r.job_window into v_window from controlplane.job_retention r where r.id;
    if not found or v_window is null or v_window <= interval '0' then
        raise warning 'job retention row is missing: nothing is purged (INV-Q-4346)';
        return null;
    end if;
    v_cutoff := now() - v_window;
    delete from controlplane.job_run where (tenant_id, service, job, entity_ref, tick, trigger, attempt) in
        (select tenant_id, service, job, entity_ref, tick, trigger, attempt from controlplane.job_run
         where status <> 'IN_PROGRESS' and started_at < v_cutoff limit batch_size);
    get diagnostics runs = row_count;
    pauses := 0;
    if runs < batch_size then
        delete from controlplane.job_pause where coalesce(ended_at, paused_until) < v_cutoff
            and (tenant_id, service, job, entity_ref, paused_at) in
            (select tenant_id, service, job, entity_ref, paused_at from controlplane.job_pause
             where coalesce(ended_at, paused_until) < v_cutoff limit batch_size - runs);
        get diagnostics pauses = row_count;
    end if;
    return runs + pauses;
end
$$;
grant create on schema controlplane to controlplane_sys;
alter function controlplane.job_state_purge_expired(integer) owner to controlplane_sys;
revoke all on function controlplane.job_state_purge_expired(integer) from public;
grant execute on function controlplane.job_state_purge_expired(integer) to controlplane_runtime;
revoke create on schema controlplane from controlplane_sys;
create function controlplane.job_retention_window() returns interval
language sql stable security definer set search_path = pg_catalog, controlplane, pg_temp as $$
    select r.job_window from controlplane.job_retention r where r.id
$$;
grant create on schema controlplane to controlplane_sys;
alter function controlplane.job_retention_window() owner to controlplane_sys;
revoke all on function controlplane.job_retention_window() from public;
grant execute on function controlplane.job_retention_window() to controlplane_runtime;
revoke create on schema controlplane from controlplane_sys;
