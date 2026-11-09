SET lock_timeout = '5s';

-- INV-T-18775, INV-REQ-506 / INV-REQ-833: the record of one accepted rate import (API-17), modelled on
-- masterdata.counterparty_import (V202611071000). A refused request leaves no row.
CREATE TABLE masterdata.rate_import (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    import_id     text        NOT NULL CHECK (length(import_id) BETWEEN 1 AND 40),
    entity_ref    text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 40),
    actor_ref     text        NOT NULL CHECK (length(actor_ref) BETWEEN 1 AND 128), -- never from the request
    source        text        NOT NULL CHECK (length(source) >= 1),
    range_from    date        NOT NULL,
    range_to      date        NOT NULL,
    state         text        NOT NULL CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    started_at    timestamptz NOT NULL,
    completed_at  timestamptz,
    applied_count integer     CHECK (applied_count >= 0),
    failed_count  integer     CHECK (failed_count >= 0),
    rejected_rows jsonb,
    error         jsonb,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, import_id),
    CHECK ((state = 'SUCCEEDED') = (applied_count IS NOT NULL AND failed_count IS NOT NULL
            AND rejected_rows IS NOT NULL)
        AND (state = 'FAILED') = (error IS NOT NULL)
        AND (state = 'RUNNING') = (completed_at IS NULL))
);

ALTER TABLE masterdata.rate_import ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.rate_import FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.rate_import
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.rate_import TO masterdata_runtime;

-- The stale-record sweep (V202611071020) also finds the tenants that hold a stale RUNNING rate import.
create policy stale_record_sys_select on masterdata.rate_import for select to masterdata_sys using (true);
grant select (tenant_id, state, started_at) on masterdata.rate_import to masterdata_sys;

drop function masterdata.stale_record_tenants(timestamptz);
create function masterdata.stale_record_tenants(p_before timestamptz)
returns table (stale_tenant uuid)
language sql stable security definer set search_path = pg_catalog, masterdata, pg_temp as $$
    select r.tenant_id from masterdata.gap_report r where r.state = 'RUNNING' and r.started_at < p_before
    union
    select r.tenant_id from masterdata.counterparty_import_dry_run r where r.state = 'RUNNING' and r.started_at < p_before
    union
    select r.tenant_id from masterdata.counterparty_import r where r.state = 'RUNNING' and r.started_at < p_before
    union
    select r.tenant_id from masterdata.rate_import r where r.state = 'RUNNING' and r.started_at < p_before
$$;
grant create on schema masterdata to masterdata_sys;
alter function masterdata.stale_record_tenants(timestamptz) owner to masterdata_sys;
revoke create on schema masterdata from masterdata_sys;
revoke all on function masterdata.stale_record_tenants(timestamptz) from public;
revoke all on function masterdata.stale_record_tenants(timestamptz) from masterdata_sys;
grant execute on function masterdata.stale_record_tenants(timestamptz) to masterdata_runtime;
