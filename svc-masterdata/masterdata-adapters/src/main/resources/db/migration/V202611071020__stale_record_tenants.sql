SET lock_timeout = '5s';

-- INV-T-4944, INV-Q-18051 A: the tenants that hold a RUNNING long-command record older than a cut-off, for the
-- long-command sweep. A job runs bound to no tenant, so the sweep cannot update the record tables (forced row-level
-- security on masterdata.bound_tenant()); this function returns tenant ids and nothing else, and the sweep then updates
-- each tenant's records under that tenant's own binding. Modelled on submission.waiting_tenants (INV-Q-15333 A).
-- masterdata_sys gains one select policy and the three columns the function reads on each record table, and no more.
grant usage on schema masterdata to masterdata_sys;

create policy stale_record_sys_select on masterdata.gap_report for select to masterdata_sys using (true);
grant select (tenant_id, state, started_at) on masterdata.gap_report to masterdata_sys;
create policy stale_record_sys_select on masterdata.counterparty_import_dry_run for select to masterdata_sys using (true);
grant select (tenant_id, state, started_at) on masterdata.counterparty_import_dry_run to masterdata_sys;
create policy stale_record_sys_select on masterdata.counterparty_import for select to masterdata_sys using (true);
grant select (tenant_id, state, started_at) on masterdata.counterparty_import to masterdata_sys;

create function masterdata.stale_record_tenants(p_before timestamptz)
returns table (stale_tenant uuid)
language sql stable security definer set search_path = pg_catalog, masterdata, pg_temp as $$
    select r.tenant_id from masterdata.gap_report r where r.state = 'RUNNING' and r.started_at < p_before
    union
    select r.tenant_id from masterdata.counterparty_import_dry_run r where r.state = 'RUNNING' and r.started_at < p_before
    union
    select r.tenant_id from masterdata.counterparty_import r where r.state = 'RUNNING' and r.started_at < p_before
$$;
grant create on schema masterdata to masterdata_sys;
alter function masterdata.stale_record_tenants(timestamptz) owner to masterdata_sys;
revoke create on schema masterdata from masterdata_sys;
revoke all on function masterdata.stale_record_tenants(timestamptz) from public;
revoke all on function masterdata.stale_record_tenants(timestamptz) from masterdata_sys;
grant execute on function masterdata.stale_record_tenants(timestamptz) to masterdata_runtime;
