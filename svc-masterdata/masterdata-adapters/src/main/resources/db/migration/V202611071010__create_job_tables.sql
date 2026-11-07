-- V202611071010__create_job_tables.sql: platform-generated DDL (TPL-12); regenerate with JobSchema.ddl of the pinned train, never edit by hand.
-- Generated: service-runtime's JobSchema.ddl("masterdata", "masterdata_runtime", "masterdata_sys") of the platform-libs-bom 0.173.0 train.
create table masterdata.shedlock (
    name varchar(255) not null primary key,
    lock_until timestamp not null,
    locked_at timestamp not null,
    locked_by varchar(255) not null
);
comment on table masterdata.shedlock is 'platform-scoped: no tenant data';
create table masterdata.job_run (
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
create table masterdata.job_pause (
    tenant_id uuid not null,
    service text not null,
    job text not null,
    entity_ref text not null,
    paused_at timestamptz not null,
    paused_until timestamptz not null,
    ended_at timestamptz,
    check (paused_until > paused_at)
);
create unique index job_pause_open on masterdata.job_pause (tenant_id, service, job, entity_ref)
    where ended_at is null;
create table masterdata.job_retention (
    id boolean primary key default true check (id),
    job_window interval not null check (job_window > interval '0'),
    updated_at timestamptz not null default now()
);
comment on table masterdata.job_retention is 'platform-scoped: no tenant data';
revoke all on masterdata.job_retention from public;
ALTER TABLE masterdata.job_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.job_run FORCE ROW LEVEL SECURITY;
CREATE POLICY job_run_tenant_isolation ON masterdata.job_run
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));
ALTER TABLE masterdata.job_pause ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.job_pause FORCE ROW LEVEL SECURITY;
CREATE POLICY job_pause_tenant_isolation ON masterdata.job_pause
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));
grant usage on schema masterdata to masterdata_runtime;
grant select, insert, update on masterdata.shedlock to masterdata_runtime;
grant select, insert, update on masterdata.job_run to masterdata_runtime;
grant select, insert, update on masterdata.job_pause to masterdata_runtime;
grant usage on schema masterdata to masterdata_sys;
create policy job_run_sys_select on masterdata.job_run for select to masterdata_sys using (true);
create policy job_run_sys_purge on masterdata.job_run for delete to masterdata_sys using (status <> 'IN_PROGRESS');
create policy job_run_sys_purge_finished on masterdata.job_run as restrictive for delete to masterdata_sys
    using (status <> 'IN_PROGRESS');
grant select (tenant_id, service, job, entity_ref, tick, trigger, attempt, status, started_at)
    on masterdata.job_run to masterdata_sys;
grant delete on masterdata.job_run to masterdata_sys;
create policy job_pause_sys_select on masterdata.job_pause for select to masterdata_sys using (true);
create policy job_pause_sys_purge on masterdata.job_pause for delete to masterdata_sys using (true);
grant select (tenant_id, service, job, entity_ref, paused_at, paused_until, ended_at) on masterdata.job_pause to masterdata_sys;
grant delete on masterdata.job_pause to masterdata_sys;
grant select on masterdata.job_retention to masterdata_sys;
create function masterdata.job_run_in_progress(batch_size integer) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, masterdata, pg_temp as $$
begin
    if batch_size is null or batch_size < 1 or batch_size > 500 then
        raise exception 'batch size % is outside 1..500', batch_size using errcode = '22023';
    end if;
    return coalesce((
        select jsonb_agg(jsonb_build_object(
            'tenant_id', r.tenant_id, 'job', r.job, 'entity_ref', r.entity_ref, 'tick', r.tick,
            'trigger', r.trigger, 'attempt', r.attempt, 'started_at', r.started_at) order by r.started_at)
        from (select tenant_id, job, entity_ref, tick, trigger, attempt, started_at from masterdata.job_run
              where status = 'IN_PROGRESS' order by started_at limit batch_size) r
    ), '[]'::jsonb);
end
$$;
grant create on schema masterdata to masterdata_sys;
alter function masterdata.job_run_in_progress(integer) owner to masterdata_sys;
revoke all on function masterdata.job_run_in_progress(integer) from public;
grant execute on function masterdata.job_run_in_progress(integer) to masterdata_runtime;
revoke create on schema masterdata from masterdata_sys;
create function masterdata.job_state_purge_expired(batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, masterdata, pg_temp as $$
declare
    v_window interval;
    v_cutoff timestamptz;
    runs bigint;
    pauses bigint;
begin
    if batch_size is null or batch_size < 1 or batch_size > 10000 then
        raise exception 'batch size % is outside 1..10000', batch_size using errcode = '22023';
    end if;
    select r.job_window into v_window from masterdata.job_retention r where r.id;
    if not found or v_window is null or v_window <= interval '0' then
        raise warning 'job retention row is missing: nothing is purged (INV-Q-4346)';
        return null;
    end if;
    v_cutoff := now() - v_window;
    delete from masterdata.job_run where (tenant_id, service, job, entity_ref, tick, trigger, attempt) in
        (select tenant_id, service, job, entity_ref, tick, trigger, attempt from masterdata.job_run
         where status <> 'IN_PROGRESS' and started_at < v_cutoff limit batch_size);
    get diagnostics runs = row_count;
    pauses := 0;
    if runs < batch_size then
        delete from masterdata.job_pause where coalesce(ended_at, paused_until) < v_cutoff
            and (tenant_id, service, job, entity_ref, paused_at) in
            (select tenant_id, service, job, entity_ref, paused_at from masterdata.job_pause
             where coalesce(ended_at, paused_until) < v_cutoff limit batch_size - runs);
        get diagnostics pauses = row_count;
    end if;
    return runs + pauses;
end
$$;
grant create on schema masterdata to masterdata_sys;
alter function masterdata.job_state_purge_expired(integer) owner to masterdata_sys;
revoke all on function masterdata.job_state_purge_expired(integer) from public;
grant execute on function masterdata.job_state_purge_expired(integer) to masterdata_runtime;
revoke create on schema masterdata from masterdata_sys;
create function masterdata.job_retention_window() returns interval
language sql stable security definer set search_path = pg_catalog, masterdata, pg_temp as $$
    select r.job_window from masterdata.job_retention r where r.id
$$;
grant create on schema masterdata to masterdata_sys;
alter function masterdata.job_retention_window() owner to masterdata_sys;
revoke all on function masterdata.job_retention_window() from public;
grant execute on function masterdata.job_retention_window() to masterdata_runtime;
revoke create on schema masterdata from masterdata_sys;